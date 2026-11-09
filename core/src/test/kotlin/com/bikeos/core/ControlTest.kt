package com.bikeos.core

import com.bikeos.core.control.AppProfile
import com.bikeos.core.control.AppProfiles
import com.bikeos.core.control.AutoPilot
import com.bikeos.core.control.BridgeCommand
import com.bikeos.core.control.ControlMode
import com.bikeos.core.control.OverlayStyle
import com.bikeos.core.control.ProfileProgram
import com.bikeos.core.metrics.ResistanceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoPilotTest {
    /** A "real" bike that is 15% harder than the model thinks, to check the integral correction. */
    private fun realPower(model: ResistanceModel, r: Float, cad: Float) = model.predictPower(r, cad) * 1.15f

    @Test fun targetPowerConvergesEvenWhenTheCurveIsOff() {
        val model = ResistanceModel()
        val ap = AutoPilot(model)
        var r = 30f
        repeat(600) { // 10 minutes at 1 Hz
            val p = realPower(model, r, 90f)
            r = ap.update(ControlMode.TargetPower(200), p, 90f, r, null, 1.0)!!.resistance
        }
        assertTrue("power ${realPower(model, r, 90f)}", abs(realPower(model, r, 90f) - 200f) < 12f)
    }

    @Test fun heartRateZoneStepsResistance() {
        val ap = AutoPilot(ResistanceModel(), hrStepSec = 10.0, hrStepPoints = 3f)
        val zone = ControlMode.HeartRateZone(130, 145)
        var out = ap.update(zone, 150f, 85f, 40f, 120, 10.0)!!
        assertEquals(43f, out.resistance, 0.01f) // below zone -> harder
        out = ap.update(zone, 150f, 85f, out.resistance, 160, 10.0)!!
        assertEquals(40f, out.resistance, 0.01f) // above zone -> easier
        out = ap.update(zone, 150f, 85f, out.resistance, 138, 10.0)!!
        assertEquals(40f, out.resistance, 0.01f) // in zone -> hold
        assertEquals("Waiting for heart rate", ap.update(zone, 150f, 85f, 40f, null, 1.0)!!.reason)
    }

    @Test fun offAndFixed() {
        val ap = AutoPilot(ResistanceModel())
        assertNull(ap.update(ControlMode.Off, 0f, 0f, 0f, null, 1.0))
        assertEquals(42f, ap.update(ControlMode.FixedResistance(42), 0f, 0f, 0f, null, 1.0)!!.resistance, 0f)
    }

    @Test fun nudgeAndCycle() {
        assertEquals(ControlMode.TargetPower(160), ControlMode.TargetPower(150).nudge(true))
        assertEquals(ControlMode.TargetPower(50), ControlMode.TargetPower(55).nudge(false))
        assertEquals(ControlMode.FixedResistance(100), ControlMode.FixedResistance(99).nudge(true))
        assertTrue(ControlMode.cycle(ControlMode.Off, 200, false, 30) is ControlMode.TargetPower)
        assertEquals(ControlMode.Off, ControlMode.cycle(ControlMode.FixedResistance(30), 200, false, 30))
        assertTrue(ControlMode.cycle(ControlMode.FixedResistance(30), 200, true, 30) is ControlMode.HeartRateZone)
    }
}

class ProfilesAndBridgeTest {
    @Test fun profilesRoundTripAndDefaults() {
        val custom = mapOf("netflix" to AppProfile("netflix", OverlayStyle.HIDDEN, ProfileProgram.Control(ControlMode.TargetPower(140))))
        assertEquals(custom, AppProfiles.decode(AppProfiles.encode(custom)))
        assertTrue(AppProfiles.decode(null).containsKey("zwift"))
        assertTrue(AppProfiles.decode("not json").containsKey("zwift"))
    }

    @Test fun bridgeParsesAndRejectsOutOfRange() {
        assertEquals(BridgeCommand.SetPower(250), BridgeCommand.parse("power", 250.0))
        assertEquals(BridgeCommand.SetResistance(45), BridgeCommand.parse("RESISTANCE", 44.6))
        assertEquals(BridgeCommand.SetGrade(-4.5f), BridgeCommand.parse("grade", -4.5))
        assertEquals(BridgeCommand.Clear, BridgeCommand.parse("clear", null))
        assertNotNull(BridgeCommand.parse("end", null))
        assertNull(BridgeCommand.parse("power", 5000.0))
        assertNull(BridgeCommand.parse("resistance", 150.0))
        assertNull(BridgeCommand.parse("power", null))
        assertNull(BridgeCommand.parse("calibrate", 1.0))
        assertNull(BridgeCommand.parse(null, null))
    }
}
