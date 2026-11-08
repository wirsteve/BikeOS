package com.bikeos

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.bikeos.settings.SensorSourceChoice
import com.bikeos.ui.HomeActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket

/**
 * End-to-end on a real Android runtime: the launcher, every main screen, a simulated ride that is
 * recorded, saved and exported, auto-start, and the Wi-Fi dashboard.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    private val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION))
        .around(compose)

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val g get() = ctx.graph

    @Before fun setUp() {
        // What the README's one-time adb setup does on a real bike.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow").close()
        g.settings.update { it.copy(sensorSource = SensorSourceChoice.SIMULATOR, autoStartRide = false, dashboardEnabled = false) }
        if (g.ride.state.value.active) runOnMain { g.ride.finish(save = false) }
        runOnMain { g.ride.dismissSummary() }
    }

    private fun runOnMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun shot(name: String) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File(ctx.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(dir, "api${Build.VERSION.SDK_INT}_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun everyScreenOpens() {
        waitForText("Just Ride")
        compose.waitUntil(10_000) { g.sensors.status.value.state == com.bikeos.sensor.SensorState.CONNECTED }
        shot("home")

        compose.onNodeWithText("Workouts").performScrollTo().performClick()
        waitForText("Power Zone")
        shot("workouts")
        compose.onNodeWithText("20 min Low Impact").performClick() // 2nd card: visible without scrolling
        waitForText("Segments")
        shot("workout_detail")
        back(); back()

        compose.onNodeWithContentDescription("Settings").performClick()
        waitForText("Safety & Peloton")
        compose.onNodeWithText("Run bike check again").assertExists()
        shot("settings")
        back()

        compose.onNodeWithContentDescription("History").performClick()
        waitForText("Ride history")
        back()

        compose.onNodeWithText("Apple Fitness+").performScrollTo().performClick()
        waitForText("Apple Fitness+ on your bike")
        compose.onNodeWithText("Pair your Apple Watch", substring = true).assertExists()
        shot("apple_fitness")
        back()

        compose.onNodeWithText("All apps").performScrollTo().performClick()
        waitForText("Long-press to pin")
        back()
        waitForText("Just Ride")
    }

    @Test fun simulatedRideIsRecordedSavedAndExported() {
        waitForText("Just Ride")
        compose.waitUntil(10_000) { g.sensors.status.value.state == com.bikeos.sensor.SensorState.CONNECTED }
        compose.onNodeWithText("Just Ride").performScrollTo().performClick()
        waitForText("End ride")
        // Rides shorter than a minute are discarded by design, so ride for just over one.
        compose.waitUntil(80_000) { g.ride.state.value.stats.movingSec >= 62 }
        shot("live_ride")
        val st = g.ride.state.value
        assertTrue("power should be flowing: ${st.stats}", st.stats.avgPower > 20f)
        assertTrue("distance should accumulate", st.stats.distanceMiles > 0.05)

        compose.onNodeWithText("End ride").performScrollTo().performClick()
        compose.onNodeWithText("End & save").performClick()
        waitForText("Nice ride!")
        shot("summary")
        compose.waitUntil(10_000) { g.rides.summaries.value.isNotEmpty() }
        val saved = g.rides.summaries.value.first()
        assertTrue(saved.stats.workKj > 1.0)

        compose.onNodeWithText("Details & export").performClick()
        waitForText("Share TCX")
        val tcx = runBlocking { g.rides.exportTcxToSharedFolder(saved.id) }!!
        val xml = tcx.readText()
        assertTrue(xml.contains("<Trackpoint>") && xml.contains("<ns3:Watts>"))
        shot("ride_detail")
    }

    @Test fun pedalingAutoStartsARide() {
        runOnMain { g.settings.update { it.copy(autoStartRide = true) } }
        compose.waitUntil(20_000) { g.ride.state.value.active }
        assertTrue(g.ride.state.value.autoStarted)
        runOnMain { g.ride.finish(save = false) }
    }

    @Test fun wifiDashboardServesLiveData() {
        runOnMain { g.settings.update { it.copy(dashboardEnabled = true, dashboardPort = 8089) } }
        var body = ""
        compose.waitUntil(10_000) {
            body = runCatching { httpGet(8089, "/api/live") }.getOrDefault("")
            body.contains("\"power\"")
        }
        assertTrue(body.contains("\"cadence\""))
        assertTrue(httpGet(8089, "/").contains("BikeOS Live"))
        runOnMain { g.settings.update { it.copy(dashboardEnabled = false) } }
    }

    /** Raw socket so the app's cleartext policy (which applies to outgoing app traffic) isn't involved. */
    private fun httpGet(port: Int, path: String): String = Socket("127.0.0.1", port).use { s ->
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        s.getInputStream().bufferedReader().readText()
    }
}
