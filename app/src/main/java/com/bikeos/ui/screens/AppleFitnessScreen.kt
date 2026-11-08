package com.bikeos.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.Permissions
import com.bikeos.apps.AppCatalog
import com.bikeos.graph
import com.bikeos.ui.Navigator
import com.bikeos.ui.components.Card
import com.bikeos.ui.components.SectionTitle
import com.bikeos.ui.theme.BikeColors
import kotlinx.coroutines.delay

/**
 * Apple Fitness+ has no Android app, so BikeOS assembles the experience from parts that do work:
 *  - the bike broadcasts as a Bluetooth power meter + cadence sensor that an Apple Watch pairs with,
 *    so Fitness+ shows (and Apple Health records) your real power and cadence;
 *  - the class is shown on the bike screen via an AirPlay receiver app, or stays on an iPad/Apple TV;
 *  - BikeOS keeps recording the ride and floats its own stats on top.
 */
@Composable
fun AppleFitnessScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val ble by g.peripheral.status.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val ride by g.ride.state.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val receiver = remember(tick) { g.catalog.firstInstalled(AppCatalog.AIRPLAY_RECEIVERS) }
    val btPermission = remember(tick) { Permissions.hasBluetooth(ctx) }
    val name = remember(tick, ble.advertising) { g.peripheral.advertisedName ?: settings.bleName }
    val dashUrl = remember(tick, settings.dashboardEnabled) { g.dashboardUrl }

    ScreenScaffold("Apple Fitness+ on your bike", nav) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(0.55f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Apple doesn't make Fitness+ for Android, so BikeOS connects the bike to your Apple devices instead. " +
                        "Your Apple Watch records the bike's real power and cadence, Fitness+ shows them on screen, and BikeOS " +
                        "saves the ride too.",
                    color = BikeColors.TextDim,
                )

                Step(
                    done = btPermission && ble.advertising,
                    title = "1 · Bike is broadcasting over Bluetooth",
                    body = when {
                        !btPermission -> "Bluetooth permission is needed."
                        ble.error != null -> ble.error!!
                        ble.advertising -> "Visible as \"$name\" (power meter + cadence sensor + smart trainer)."
                        else -> "Turn on Broadcast in Settings → Bluetooth."
                    },
                ) {
                    if (!btPermission) Button(onClick = { (ctx as? Activity)?.let(Permissions::requestBluetooth) }) { Text("Allow Bluetooth") }
                    else if (!ble.advertising) Button(onClick = {
                        g.settings.update { it.copy(bleBroadcast = true) }
                        g.restartBluetoothBroadcast()
                    }) { Text("Turn on") }
                }

                Step(
                    done = ble.connectedDevices.isNotEmpty(),
                    title = "2 · Pair your Apple Watch (one time)",
                    body = if (ble.connectedDevices.isNotEmpty()) "Connected: ${ble.connectedDevices.joinToString()}"
                    else "On the watch (watchOS 10 or later): Settings → Bluetooth → wait for \"$name\" under Health Devices → tap it. " +
                        "Then on the watch start Workout → Indoor Cycle, or start a Cycling class in Fitness+ on your iPhone/iPad.",
                )

                Step(
                    done = receiver != null,
                    title = "3 · Put the class on the big screen (optional)",
                    body = if (receiver != null) "AirPlay receiver installed. On your iPhone or iPad: Control Center → Screen Mirroring → this bike. " +
                        "Both must be on the same Wi-Fi."
                    else "Install an AirPlay receiver app (e.g. AirScreen) with Aurora Store. Or keep the class on your iPad / Apple TV — " +
                        "the watch pairing still records power and cadence.",
                ) {
                    if (receiver == null) OutlinedButton(onClick = { g.catalog.openStorePage(AppCatalog.AIRSCREEN) }) { Text("Get AirScreen") }
                }

                Step(
                    done = dashUrl != null,
                    title = "4 · Bike stats on your iPhone / iPad (optional)",
                    body = dashUrl?.let { "Open in Safari on any device on this Wi-Fi:\n$it" }
                        ?: "Shows live power, cadence, resistance, targets and HR in a browser — handy when the class plays on an Apple TV.",
                    mono = dashUrl != null,
                ) {
                    if (dashUrl == null) OutlinedButton(onClick = { g.settings.update { it.copy(dashboardEnabled = true) } }) { Text("Turn on") }
                }
            }

            Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Card(Modifier.fillMaxWidth(), color = BikeColors.SurfaceHigh) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Ready?", style = MaterialTheme.typography.titleLarge, color = BikeColors.Text)
                        Text(
                            "Start recording, then open the AirPlay receiver. BikeOS switches its overlay to compact so it " +
                                "doesn't cover Fitness+'s own metrics. Drag it anywhere.",
                            color = BikeColors.TextDim,
                        )
                        Button(
                            onClick = {
                                if (!ride.active) g.ride.justRide("Apple Fitness+") else g.ride.setCompanionApp("Apple Fitness+")
                                g.settings.update { it.copy(overlayCompact = true) }
                                receiver?.let(g.catalog::launchPackage)
                            },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB4FF39), contentColor = Color.Black),
                        ) {
                            Text(if (receiver != null) "Start ride & open AirPlay" else "Start Fitness+ ride", fontSize = 20.sp, fontWeight = FontWeight.Black)
                        }
                        Text(
                            "Tip: Fitness+ \"Burn Bar\" and your watch rings use the watch's data; BikeOS's overlay and history use " +
                                "the bike's data directly. They'll agree because they're the same sensor.",
                            color = BikeColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle("Also works with")
                        Text("• Apple Watch Workout app (Indoor Cycle) without Fitness+", color = BikeColors.Text)
                        Text("• Garmin / Wahoo / Coros watches & bike computers (power + cadence)", color = BikeColors.Text)
                        Text("• Zwift, Rouvy, MyWhoosh, TrainerRoad, Kinomap on iPhone, iPad, Apple TV or Mac (smart trainer)", color = BikeColors.Text)
                        Text("• Peloton App, Strava and others on your phone (power meter)", color = BikeColors.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(done: Boolean, title: String, body: String, mono: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                tint = if (done) BikeColors.Good else BikeColors.TextDim, modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = BikeColors.Text)
                Text(body, color = BikeColors.TextDim, fontFamily = if (mono) FontFamily.Monospace else null)
                if (action != null) Row(Modifier.padding(top = 8.dp)) { action() }
            }
        }
    }
}
