package com.bikeos.core

import com.bikeos.core.ble.CrankCounter
import com.bikeos.core.ble.CscEncoder
import com.bikeos.core.ble.CyclingPowerEncoder
import com.bikeos.core.peloton.PelotonCallPolicy
import com.bikeos.core.peloton.PelotonService
import com.bikeos.core.peloton.ResistanceLimiter
import com.bikeos.core.remote.DashboardServer
import com.bikeos.core.remote.LiveSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class PelotonCallPolicyTest {
    @Test fun allowsOnlyKnownReadAndListenerCalls() {
        assertNotNull(PelotonCallPolicy.check(PelotonService.V1_INTERFACE, 1))
        assertNotNull(PelotonCallPolicy.check(PelotonService.V1_INTERFACE, 2))
        assertNotNull(PelotonCallPolicy.check(PelotonService.BIKE_PLUS_INTERFACE, 14))
        assertEquals(PelotonCallPolicy.Kind.READ_ONLY, PelotonCallPolicy.check(PelotonService.BIKE_PLUS_INTERFACE, 14)!!.kind)
    }

    @Test fun blocksEverythingElse() {
        for (code in 3..200) assertNull("V1 #$code", PelotonCallPolicy.check(PelotonService.V1_INTERFACE, code))
        for (code in (1..200) - 14) assertNull("Bike+ #$code", PelotonCallPolicy.check(PelotonService.BIKE_PLUS_INTERFACE, code))
        assertNull(PelotonCallPolicy.check("com.onepeloton.something.IUpdater", 1))
    }

    @Test fun userResistanceCodeOnlyOnBikePlusAndNeverAReadCode() {
        assertEquals(PelotonCallPolicy.Kind.MOTOR_WRITE, PelotonCallPolicy.check(PelotonService.BIKE_PLUS_INTERFACE, 20, userResistanceCode = 20)!!.kind)
        assertNull(PelotonCallPolicy.check(PelotonService.V1_INTERFACE, 20, userResistanceCode = 20))
        assertNull(PelotonCallPolicy.check(PelotonService.BIKE_PLUS_INTERFACE, 21, userResistanceCode = 20))
        assertFalse(PelotonCallPolicy.isValidResistanceCode(14))
        assertFalse(PelotonCallPolicy.isValidResistanceCode(0))
        assertFalse(PelotonCallPolicy.isValidResistanceCode(999))
        assertFalse(PelotonCallPolicy.isValidResistanceCode(null))
    }
}

class ResistanceLimiterTest {
    @Test fun rampsInSmallStepsAndRespectsCeiling() {
        val l = ResistanceLimiter(maxResistance = 60, maxStepPerCommand = 10, minIntervalMs = 1000)
        var t = 0L
        val cmds = mutableListOf<Int>()
        var cur = 20f
        repeat(10) {
            l.next(95f, cur, 90f, true, t)?.let { cmds += it; cur = it.toFloat() }
            t += 1000
        }
        assertEquals(listOf(30, 40, 50, 60), cmds)
    }

    @Test fun rateLimited() {
        val l = ResistanceLimiter()
        assertNotNull(l.next(50f, 30f, 90f, true, 0))
        assertNull(l.next(55f, 40f, 90f, true, 500))
        assertNotNull(l.next(55f, 40f, 90f, true, 1000))
    }

    @Test fun neverIncreasesWhenNotPedalingOrDataStale() {
        val l = ResistanceLimiter()
        assertNull(l.next(70f, 30f, 0f, true, 0))
        assertNull(l.next(70f, 30f, 90f, false, 2000))
        assertEquals(20, l.next(10f, 30f, 0f, false, 4000)) // decreasing is always allowed
    }

    @Test fun disablesAfterFailures() {
        val l = ResistanceLimiter(maxFailures = 3)
        repeat(3) { l.reportFailure() }
        assertTrue(l.disabled)
        assertNull(l.next(50f, 30f, 90f, true, 10_000))
        l.rearm()
        assertNotNull(l.next(50f, 30f, 90f, true, 20_000))
    }
}

class CscTest {
    @Test fun sharedCrankCounterKeepsBothServicesInSync() {
        val crank = CrankCounter()
        val cps = CyclingPowerEncoder(crank)
        val csc = CscEncoder(crank)
        var a = byteArrayOf()
        var b = byteArrayOf()
        for (i in 0..30) {
            crank.update(90f, i * 1000L)
            a = cps.measurement(200, 90f, i * 1000L, advanceCrank = false)
            b = csc.measurement(90f, i * 1000L, advanceCrank = false)
        }
        val cpsRevs = (a[4].toInt() and 0xFF) or ((a[5].toInt() and 0xFF) shl 8)
        val cscRevs = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)
        assertEquals(cpsRevs, cscRevs)
        assertTrue("revs=$cscRevs", cscRevs in 44..46) // 90 rpm * 30 s
        assertEquals(2, b[0].toInt()) // crank data present flag
        assertEquals(5, b.size)
    }
}

class DashboardServerTest {
    private fun snap() = LiveSnapshot(
        riding = true, paused = false, workout = "Test", elapsedSec = 61, power = 210, zone = 3, cadence = 88,
        resistance = 41, heartRate = 140, speed = 20.5f, speedUnit = "mph", distance = 1.2f, distanceUnit = "mi",
        outputKj = 50, calories = 50,
    )

    private fun get(port: Int, path: String, method: String = "GET"): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to body
    }

    @Test fun servesPageAndJsonAndIsReadOnly() {
        val server = DashboardServer(0, ::snap)
        server.start()
        try {
            val port = server.boundPort
            val (pc, page) = get(port, "/")
            assertEquals(200, pc)
            assertTrue(page.contains("BikeOS Live"))
            assertFalse("unescaped Kotlin template in page", page.contains("\${'"))
            val (jc, json) = get(port, "/api/live")
            assertEquals(200, jc)
            assertTrue(json.contains("\"power\":210"))
            assertEquals(405, get(port, "/api/live", "POST").first)
            assertEquals(404, get(port, "/nope").first)
        } finally {
            server.stop()
        }
    }
}
