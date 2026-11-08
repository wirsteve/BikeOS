package com.bikeos

import android.os.Binder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bikeos.core.peloton.BlockedCallException
import com.bikeos.core.peloton.DexFile
import com.bikeos.core.peloton.PelotonCodeInspector
import com.bikeos.core.peloton.PelotonProtocol
import com.bikeos.core.peloton.PelotonService
import com.bikeos.resistance.BikePlusAutoResistance
import com.bikeos.resistance.ManualResistance
import com.bikeos.sensor.BikeCheck
import com.bikeos.sensor.guardedTransact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class OnDeviceSafetyTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** The bike check's DEX reader, run on real AIDL compiler output inside a real APK on a real Android runtime. */
    @Test fun codeScannerReadsRealAidlStubs() {
        val apk = testContext.applicationInfo.sourceDir
        val dexes = ZipFile(apk).use { z ->
            z.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.map { e -> DexFile(z.getInputStream(e).use { it.readBytes() }) }
        }
        assertTrue("no dex in test apk", dexes.isNotEmpty())
        val r = PelotonCodeInspector.inspect(dexes)
        assertTrue(r.bikePlusInterfaceFound)
        assertTrue(r.v1InterfaceFound)
        // AIDL numbers methods from FIRST_CALL_TRANSACTION (1) in declaration order.
        assertEquals(mapOf("getBikeData" to 1, "setTargetResistance" to 2, "startCalibration" to 3), r.transactions["IBikeInterface"])
        assertEquals(1, r.protocol.bikePlusGetBikeData)
        assertEquals(2, r.protocol.bikePlusSetResistance)
        assertEquals(1, r.protocol.v1RegisterCallback)
        assertEquals(2, r.protocol.v1UnregisterCallback)
        assertFalse(r.resistanceCandidates.any { it.first.contains("Calibration") })
    }

    @Test fun bikeCheckIsHarmlessWithoutPeloton() {
        val r = BikeCheck.run(appContext)
        assertTrue("got $r", r is BikeCheck.Result.NotInstalled)
    }

    /** A fake Peloton service: records every transaction code it receives. */
    private class RecordingBinder : Binder() {
        val received = mutableListOf<Int>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            received += code
            reply?.writeNoException()
            return true
        }
    }

    @Test fun guardBlocksEveryNonAllowlistedCallBeforeItIsSent() {
        val b = RecordingBinder()
        val protocol = PelotonProtocol()
        for (code in 1..100) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                guardedTransact(b, PelotonService.BIKE_PLUS_INTERFACE, code, data, reply, protocol)
                if (code != protocol.bikePlusGetBikeData) fail("call #$code should have been blocked")
            } catch (_: BlockedCallException) {
                // expected for everything except the read call
            } finally {
                data.recycle(); reply.recycle()
            }
        }
        assertEquals(listOf(protocol.bikePlusGetBikeData), b.received)
    }

    @Test fun motorControlIsNeverCreatedOffABikePlus() {
        val c = BikePlusAutoResistance.createIfSafe({ RecordingBinder() }, 22, 75) { PelotonProtocol() }
        assertSame(ManualResistance, c)
    }
}
