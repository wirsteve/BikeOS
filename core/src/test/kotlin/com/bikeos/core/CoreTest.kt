package com.bikeos.core

import com.bikeos.core.ble.CyclingPowerEncoder
import com.bikeos.core.ble.FtmsCodec
import com.bikeos.core.ble.HeartRateCodec
import com.bikeos.core.ble.IndoorBikeFrame
import com.bikeos.core.export.RideRecord
import com.bikeos.core.export.TcxWriter
import com.bikeos.core.metrics.Calories
import com.bikeos.core.metrics.PowerZones
import com.bikeos.core.metrics.ResistanceModel
import com.bikeos.core.metrics.RideAccumulator
import com.bikeos.core.metrics.SpeedModel
import com.bikeos.core.metrics.SpikeFilter
import com.bikeos.core.metrics.TerrainMapping
import com.bikeos.core.model.BikeSample
import com.bikeos.core.peloton.ParcelSource
import com.bikeos.core.peloton.PelotonBikeData
import com.bikeos.core.stats.Streaks
import com.bikeos.core.workout.ComplianceTracker
import com.bikeos.core.workout.Range
import com.bikeos.core.workout.Segment
import com.bikeos.core.workout.TargetStatus
import com.bikeos.core.workout.WorkoutLibrary
import com.bikeos.core.workout.WorkoutRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

class SpeedModelTest {
    @Test fun zeroPowerIsZeroSpeed() = assertEquals(0f, SpeedModel.mphFromPower(0f), 0f)

    @Test fun typicalPowerGivesPlausibleSpeed() {
        val mph = SpeedModel.mphFromPower(150f)
        assertTrue("150 W should be ~18-21 mph, was $mph", mph in 17f..22f)
    }

    @Test fun speedIsMonotonic() {
        var last = 0f
        for (w in 1..800) {
            val v = SpeedModel.mphFromPower(w.toFloat())
            assertTrue("speed dropped at $w W", v >= last - 0.05f)
            last = v
        }
    }
}

class PowerZonesTest {
    @Test fun zonesForFtp200() {
        assertEquals(1, PowerZones.zoneFor(100f, 200).number)
        assertEquals(2, PowerZones.zoneFor(120f, 200).number)
        assertEquals(4, PowerZones.zoneFor(200f, 200).number)
        assertEquals(7, PowerZones.zoneFor(400f, 200).number)
        assertEquals(150..180, PowerZones.targetBand(3, 200))
    }

    @Test fun ftpFromTest() = assertEquals(190, PowerZones.ftpFrom20MinTest(200f))
}

class RideAccumulatorTest {
    private fun ride(seconds: Int, power: Float, cadence: Float, res: Float, hr: Int? = null): RideAccumulator {
        val acc = RideAccumulator()
        for (i in 0..seconds * 4) acc.add(BikeSample(i * 250L, cadence, power, res, hr))
        return acc
    }

    @Test fun integratesWorkAndTime() {
        val s = ride(600, 200f, 90f, 40f).stats()
        assertEquals(600, s.elapsedSec)
        assertEquals(600, s.movingSec)
        assertEquals(120.0, s.workKj, 0.5) // 200 W * 600 s
        assertEquals(200f, s.avgPower, 0.01f)
        assertEquals(200f, s.normalizedPower, 0.5f)
        assertEquals(40f, s.avgResistance, 0.01f)
        val expectedMiles = SpeedModel.mphFromPower(200f) * 600 / 3600.0
        assertEquals(expectedMiles, s.distanceMiles, 0.01)
        assertEquals(120.0 / (4.184 * 0.24), s.calories, 0.5)
    }

    @Test fun traceIsOneHz() {
        val acc = ride(120, 150f, 85f, 35f, hr = 140)
        val t = acc.trace()
        assertEquals(121, t.size)
        assertEquals(140, t.last().hr)
        assertEquals(140, acc.stats().avgHr)
    }

    @Test fun pauseStopsTheClock() {
        val acc = RideAccumulator()
        acc.add(BikeSample(0, 90f, 100f, 30f))
        acc.add(BikeSample(1000, 90f, 100f, 30f))
        acc.paused = true
        acc.add(BikeSample(60_000, 90f, 100f, 30f))
        acc.paused = false
        acc.add(BikeSample(61_000, 90f, 100f, 30f))
        acc.add(BikeSample(62_000, 90f, 100f, 30f))
        // 0->1 s, then the first sample after resuming only re-anchors the clock: 61->62 s
        assertEquals(2, acc.stats().elapsedSec)
    }

    @Test fun dropoutsAreClamped() {
        val acc = RideAccumulator()
        acc.add(BikeSample(0, 90f, 100f, 30f))
        acc.add(BikeSample(30_000, 90f, 100f, 30f))
        assertEquals(2, acc.stats().elapsedSec)
    }

    @Test fun bestAverage() {
        val acc = RideAccumulator()
        var t = 0L
        repeat(100) { acc.add(BikeSample(t, 90f, 100f, 30f)); t += 1000 }
        repeat(60) { acc.add(BikeSample(t, 90f, 300f, 30f)); t += 1000 }
        repeat(100) { acc.add(BikeSample(t, 90f, 100f, 30f)); t += 1000 }
        assertEquals(300f, RideAccumulator.bestAverage(acc.trace(), 60), 1f)
    }

    @Test fun spikeFilterRemovesSingleSpikes() {
        val f = SpikeFilter(3)
        listOf(30f, 30f, 95f, 31f, 31f).map(f::push).forEach { assertTrue(it <= 31f) }
    }
}

class WorkoutTest {
    @Test fun libraryWorkoutsAreWellFormed() {
        for (w in WorkoutLibrary.builtIns) {
            assertTrue(w.id, w.segments.isNotEmpty())
            assertTrue(w.id, w.segments.all { it.durationSec > 0 })
            w.segments.forEach { s ->
                s.cadence?.let { assertTrue(it.min <= it.max) }
                s.resistance?.let { assertTrue(it.min in 0..100 && it.max in 0..100) }
                s.powerZone?.let { assertTrue(it in 1..7) }
            }
        }
        assertEquals(45, WorkoutLibrary.builtIns.first { it.id == "pz-max-45" }.durationMin)
        assertEquals(30, WorkoutLibrary.builtIns.first { it.id == "climb-30" }.durationMin)
    }

    @Test fun runnerFindsSegments() {
        val w = WorkoutLibrary.ftpTest
        val runner = WorkoutRunner(w)
        assertEquals(0, runner.progressAt(0).segmentIndex)
        val inTest = runner.progressAt(10 * 60 + 5)
        assertEquals("FTP TEST", inTest.segment.name)
        assertEquals(5, inTest.segmentElapsedSec)
        val done = runner.progressAt(w.durationSec + 10)
        assertTrue(done.finished)
        assertEquals(w.segments.lastIndex, done.segmentIndex)
    }

    @Test fun evaluationAndCompliance() {
        val seg = Segment("x", 60, cadence = Range(80, 90), resistance = Range(30, 40))
        val good = WorkoutRunner.evaluate(seg, BikeSample(0, 85f, 150f, 35f), 200)
        assertTrue(good.onTarget)
        val slow = WorkoutRunner.evaluate(seg, BikeSample(0, 70f, 150f, 35f), 200)
        assertEquals(TargetStatus.BELOW, slow.cadence)
        val c = ComplianceTracker(1)
        c.record(0, good, 30.0)
        c.record(0, slow, 10.0)
        assertEquals(75, c.score())
    }

    @Test fun jsonRoundTrip() {
        val w = WorkoutLibrary.builtIns[2]
        val parsed = WorkoutLibrary.parse(WorkoutLibrary.toJson(w)).single()
        assertEquals(w, parsed)
    }

    @Test fun parsesHandWrittenJson() {
        val text = """
            [{"id":"mine","title":"My ride","description":"d","category":"CUSTOM",
              "segments":[{"name":"go","durationSec":120,"cadence":{"min":80,"max":90},"futureField":1}]}]
        """
        val w = WorkoutLibrary.parse(text).single()
        assertEquals(120, w.durationSec)
    }
}

class ResistanceModelTest {
    @Test fun inverseIsConsistent() {
        val m = ResistanceModel()
        val r = m.resistanceFor(200f, 90f)
        assertEquals(200f, m.predictPower(r, 90f), 6f)
    }

    @Test fun learnsTowardsObservedBike() {
        val m = ResistanceModel()
        repeat(500) { m.learn(40f, 90f, 200f) } // this bike is "harder" than the seed curve at 40
        assertEquals(200f, m.predictPower(40f, 90f), 10f)
        assertTrue(m.snapshot().wattsAt90.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun terrain() {
        val t = TerrainMapping(30f, 3f)
        assertEquals(45f, t.resistanceForGrade(5f), 0.001f)
        assertEquals(0f, t.resistanceForGrade(-20f), 0.001f)
    }
}

class GattCodecTest {
    @Test fun indoorBikeDataLayout() {
        val bytes = FtmsCodec.indoorBikeData(
            IndoorBikeFrame(speedKmh = 30f, cadenceRpm = 90f, totalDistanceM = 1000, resistanceLevel = 42f,
                powerW = 250, totalEnergyKcal = 100, heartRate = 150, elapsedSec = 600),
        )
        // flags(2) speed(2) cad(2) dist(3) res(2) pwr(2) energy(2+2+1) hr(1) elapsed(2)
        assertEquals(21, bytes.size)
        val flags = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        assertEquals(0, flags and 1) // speed present
        assertEquals(3000, (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8))
        assertEquals(180, (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8))
        assertEquals(250, (bytes[11].toInt() and 0xFF) or ((bytes[12].toInt() and 0xFF) shl 8))
        assertEquals(150, bytes[18].toInt() and 0xFF)
    }

    @Test fun controlPoint() {
        assertEquals(FtmsCodec.ControlRequest.RequestControl, FtmsCodec.parseControlPoint(byteArrayOf(0)))
        val p = FtmsCodec.parseControlPoint(byteArrayOf(0x05, 0xC8.toByte(), 0x00))
        assertEquals(200, (p as FtmsCodec.ControlRequest.SetTargetPower).watts)
        // grade 5.00% = 500 = 0x01F4
        val sim = FtmsCodec.parseControlPoint(byteArrayOf(0x11, 0, 0, 0xF4.toByte(), 0x01, 40, 51))
        assertEquals(5f, (sim as FtmsCodec.ControlRequest.SetSimulation).gradePct, 0.001f)
        val neg = FtmsCodec.parseControlPoint(byteArrayOf(0x11, 0, 0, 0x0C, 0xFE.toByte(), 40, 51))
        assertEquals(-5f, (neg as FtmsCodec.ControlRequest.SetSimulation).gradePct, 0.001f)
        assertTrue(FtmsCodec.parseControlPoint(byteArrayOf(0x05)) is FtmsCodec.ControlRequest.Malformed)
    }

    @Test fun cyclingPowerCrankRevs() {
        val enc = CyclingPowerEncoder()
        var last = byteArrayOf()
        for (i in 0..60) last = enc.measurement(200, 60f, i * 1000L) // 60 rpm for 60 s => 60 revs
        val revs = (last[4].toInt() and 0xFF) or ((last[5].toInt() and 0xFF) shl 8)
        assertTrue("revs=$revs", abs(revs - 60) <= 1)
        assertEquals(200, (last[2].toInt() and 0xFF) or ((last[3].toInt() and 0xFF) shl 8))
    }

    @Test fun heartRate() {
        assertEquals(72, HeartRateCodec.parse(byteArrayOf(0, 72)))
        assertEquals(300, HeartRateCodec.parse(byteArrayOf(1, 0x2C, 0x01)))
        assertNull(HeartRateCodec.parse(byteArrayOf(0)))
    }
}

/** Fake parcel that replays a scripted sequence of values. */
private class ScriptedParcel(vararg values: Any?) : ParcelSource {
    private val q = ArrayDeque(values.toList())
    private fun next(): Any? = q.removeFirst()
    override fun readLong() = next() as Long
    override fun readInt() = next() as Int
    override fun readFloat() = next() as Float
    override fun readString() = next() as String?
    override fun createByteArray() = next() as ByteArray?
    override fun createIntArray() = next() as IntArray?
}

class PelotonBikeDataTest {
    @Test fun decodesRequiredFieldsEvenIfTailIsTruncated() {
        val d = PelotonBikeData.decode(ScriptedParcel(85L, 15_050L, 1L, 2L, 37, 40))
        assertEquals(85L, d.rpm)
        assertEquals(150.5f, d.powerWatts, 0.001f)
        assertEquals(37, d.currentResistance)
        assertEquals(40, d.targetResistance)
        assertNull(d.firmwareVersion)
    }

    @Test fun decodesFullParcel() {
        val tail = mutableListOf<Any?>(
            "fw1.2", byteArrayOf(1), "t", 0, 100, 2, 7, 3, 0,
        )
        repeat(5) { tail += 0; tail += null }
        tail.addAll(listOf(IntArray(15), null, 0, "SERIAL", null, null, null, 0f, 0, null, null, 0, 0, 0, 0f, 0, 0,
            33.5f, null, 0, 0, "HW2", 0, 1, 180, 42.5f, 1))
        val d = PelotonBikeData.decode(ScriptedParcel(90L, 20_000L, 5L, 6L, 30, 31, *tail.toTypedArray()))
        assertEquals("fw1.2", d.firmwareVersion)
        assertEquals("SERIAL", d.serial)
        assertEquals("HW2", d.hardwareVersion)
        assertEquals(33.5f, d.v1Resistance, 0f)
        assertEquals(180, d.powerZoneAutoFollowPowerSetPoint)
        assertEquals(42.5f, d.powerZoneAutoFollowTargetResistance, 0f)
    }
}

class ExportAndStatsTest {
    private fun record(dayOffset: Long, kj: Double = 300.0): RideRecord {
        val acc = RideAccumulator()
        for (i in 0..120) acc.add(BikeSample(i * 1000L, 90f, 200f, 40f, 130))
        val start = LocalDate.of(2026, 11, 8).minusDays(dayOffset).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return RideRecord("r$dayOffset", start, "Ride & <test>", stats = acc.stats().copy(workKj = kj), trace = acc.trace())
    }

    @Test fun tcxIsWellFormedXml() {
        val xml = TcxWriter.write(record(0))
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(121, doc.getElementsByTagNameNS("*", "Trackpoint").length)
        assertTrue(xml.contains("<ns3:Watts>200</ns3:Watts>"))
        assertTrue(xml.contains("Ride &amp; &lt;test&gt;"))
    }

    @Test fun recordJsonRoundTrip() {
        val r = record(0)
        assertEquals(r, RideRecord.decode(RideRecord.encode(r)))
    }

    @Test fun streaks() {
        val rides = listOf(record(0), record(1), record(2), record(5))
        val s = Streaks.summarize(rides, ZoneOffset.UTC, LocalDate.of(2026, 11, 8))
        assertEquals(3, s.dayStreak)
        assertEquals(4, s.totalRides)
        assertNotNull(s)
        assertTrue(s.todayCalories > 0)
    }

    @Test fun keytelIsSane() {
        val kcalMin = Calories.keytelKcalPerMinute(150, 80.0, 35, Calories.Sex.MALE)
        assertTrue("kcal/min=$kcalMin", kcalMin in 10.0..16.0)
    }
}
