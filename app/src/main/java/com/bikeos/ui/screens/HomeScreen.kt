package com.bikeos.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.Permissions
import com.bikeos.apps.AppCatalog
import com.bikeos.apps.AppSection
import com.bikeos.apps.CatalogApp
import com.bikeos.ble.HrState
import com.bikeos.core.export.RideRecord
import com.bikeos.core.stats.Streaks
import com.bikeos.graph
import com.bikeos.sensor.SensorState
import com.bikeos.ui.Format
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Card
import com.bikeos.ui.components.Metric
import com.bikeos.ui.components.Pill
import com.bikeos.ui.components.SectionTitle
import com.bikeos.ui.theme.BikeColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val sensor by g.sensors.status.collectAsStateWithLifecycle()
    val hrState by g.heartRate.state.collectAsStateWithLifecycle()
    val ble by g.peripheral.status.collectAsStateWithLifecycle()
    val rides by g.rides.summaries.collectAsStateWithLifecycle()
    val summary = remember(rides) { Streaks.summarize(rides) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var needsInstall by remember { mutableStateOf<CatalogApp?>(null) }
    var refreshTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis(); refreshTick++ } }

    fun open(app: CatalogApp) {
        g.ride.setCompanionApp(app.label.takeIf { app.section == AppSection.WATCH || app.section == AppSection.LISTEN })
        when (val r = g.catalog.launch(app)) {
            is AppCatalog.LaunchResult.ShowInfo -> nav.go(Screen.AppleFitness)
            is AppCatalog.LaunchResult.NeedsInstall -> needsInstall = r.app
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
        // ---- Status bar ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bike", fontSize = 30.sp, fontWeight = FontWeight.Black, color = BikeColors.Text)
            Text("OS", fontSize = 30.sp, fontWeight = FontWeight.Black, color = BikeColors.Accent, modifier = Modifier.padding(start = 0.dp))
            Spacer(Modifier.width(12.dp))
            Text(Format.time(now), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = BikeColors.TextDim)
            Spacer(Modifier.weight(1f))
            Pill(
                when (sensor.state) {
                    SensorState.CONNECTED -> "Bike connected"
                    SensorState.CONNECTING, SensorState.NO_DATA -> "Bike: waiting"
                    else -> "No bike sensor"
                },
                when (sensor.state) {
                    SensorState.CONNECTED -> BikeColors.Good
                    SensorState.CONNECTING, SensorState.NO_DATA -> BikeColors.Warn
                    else -> BikeColors.Bad
                },
                onClick = { nav.go(Screen.Settings) },
            )
            Pill(
                ride.sample.heartRateBpm?.let { "$it bpm" } ?: if (hrState == HrState.DISABLED) "No HR strap" else "HR: ${hrState.name.lowercase()}",
                if (ride.sample.heartRateBpm != null) BikeColors.Heart else BikeColors.TextDim,
                onClick = { nav.go(Screen.Settings) },
            )
            Pill(
                when {
                    ble.connectedDevices.isNotEmpty() -> "Sharing to ${ble.connectedDevices.size} device(s)"
                    ble.advertising -> "Bluetooth: broadcasting"
                    else -> "Bluetooth: off"
                },
                if (ble.advertising) BikeColors.Cadence else BikeColors.TextDim,
                onClick = { nav.go(Screen.Settings) },
            )
            IconButton(onClick = { nav.go(Screen.History) }) { Icon(Icons.Default.History, "History", tint = BikeColors.Text) }
            IconButton(onClick = { nav.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings", tint = BikeColors.Text) }
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // ---- Left: ride + stats ----
            Column(Modifier.weight(0.36f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SetupCard()
                RideCard(nav)
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SectionTitle("This week")
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            StatBlock("Rides", summary.weekRides.toString())
                            StatBlock("Minutes", summary.weekMinutes.toString())
                            StatBlock("Output", summary.weekKj.toString(), "kJ", BikeColors.Accent)
                            StatBlock("Streak", summary.dayStreak.toString(), "days", BikeColors.Accent2)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Burned today", style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
                                Text("${summary.todayCalories} kcal", fontSize = 22.sp, fontWeight = FontWeight.Black, color = BikeColors.Accent2)
                            }
                            val mfp = g.catalog.entries.first { it.id == "mfp" }
                            OutlinedButton(onClick = { open(mfp) }) { Text("Log in MyFitnessPal") }
                        }
                    }
                }
                rides.firstOrNull()?.let { last -> LastRideCard(last) { nav.go(Screen.RideDetail(last.id)) } }
            }

            // ---- Right: apps ----
            Column(Modifier.weight(0.64f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                if (settings.pinnedApps.isNotEmpty()) {
                    SectionTitle("Your apps")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        settings.pinnedApps.forEach { pkg ->
                            AppTile(g.catalog.label(pkg), BikeColors.SurfaceHigh, g.catalog.icon(pkg), null, onClick = {
                                g.ride.setCompanionApp(g.catalog.label(pkg))
                                g.catalog.launchPackage(pkg)
                            })
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
                AppSection.entries.forEach { section ->
                    val apps = g.catalog.entries.filter { it.section == section && it.id !in settings.hiddenCatalogIds }
                    if (apps.isEmpty()) return@forEach
                    SectionTitle(section.title)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        apps.forEach { app ->
                            val pkg = remember(app, refreshTick) { g.catalog.installedPackage(app) }
                            val badge = when {
                                pkg != null || app.special != null -> null
                                app.webUrl != null -> "WEB"
                                else -> "GET"
                            }
                            AppTile(app.label, app.color, pkg?.let(g.catalog::icon), badge, onClick = { open(app) })
                        }
                        if (section == AppSection.TOOLS) {
                            AppTile("All apps", BikeColors.SurfaceHigh, null, null, onClick = { nav.go(Screen.AllApps) })
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }

    needsInstall?.let { app ->
        AlertDialog(
            onDismissRequest = { needsInstall = null },
            title = { Text("${app.label} isn't installed") },
            text = {
                Text(
                    (app.note?.let { "$it\n\n" } ?: "") +
                        "Peloton tablets don't include Google Play. Install ${app.label} with Aurora Store, or sideload the APK " +
                        "with adb (see the BikeOS README).",
                )
            },
            confirmButton = {
                Button(onClick = { app.packages.firstOrNull()?.let(g.catalog::openStorePage); needsInstall = null }) { Text("Find app") }
            },
            dismissButton = { TextButton(onClick = { needsInstall = null }) { Text("Close") } },
        )
    }
    ride.lastFinished?.let { rec -> RideSummaryDialog(rec, onDismiss = { g.ride.dismissSummary() }, onDetails = { g.ride.dismissSummary(); nav.go(Screen.RideDetail(rec.id)) }) }
}

@Composable
private fun RideCard(nav: Navigator) {
    val g = LocalContext.current.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val s = ride.sample
    Card(Modifier.fillMaxWidth(), color = BikeColors.SurfaceHigh) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ride.active) ride.workout?.title ?: "Riding" else "Ready to ride", style = MaterialTheme.typography.titleLarge, color = BikeColors.Text, modifier = Modifier.weight(1f))
                if (ride.active) Text(Format.clock(ride.stats.elapsedSec), fontSize = 24.sp, fontWeight = FontWeight.Black, color = BikeColors.Accent)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Metric("Cadence", s.cadenceRpm.roundToInt().toString(), "rpm", BikeColors.Cadence, valueSize = 40.sp)
                Metric("Resistance", s.resistancePct.roundToInt().toString(), "%", BikeColors.Resistance, valueSize = 40.sp)
                Metric("Power", s.powerWatts.roundToInt().toString(), "W", BikeColors.zoneColors[ride.zone.number - 1], valueSize = 40.sp)
            }
            Spacer(Modifier.height(12.dp))
            if (ride.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { nav.go(Screen.Live) }, modifier = Modifier.weight(1f)) { Text("Open ride") }
                    OutlinedButton(onClick = { g.ride.finish() }) { Text("End") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { g.ride.justRide() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsBike, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Just Ride")
                    }
                    Button(
                        onClick = { nav.go(Screen.Workouts) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = BikeColors.Accent2),
                    ) {
                        Icon(Icons.Default.FitnessCenter, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Workouts")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (settings.autoStartRide) "Or just start pedaling — BikeOS records automatically, even while you watch other apps."
                    else "Auto-start is off. Tap Just Ride to record.",
                    color = BikeColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("FTP ${settings.ftp} W", color = BikeColors.TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    val activity = ctx as? Activity ?: return
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    val overlayOk = remember(tick) { Permissions.canDrawOverlays(ctx) }
    val btOk = remember(tick) { Permissions.hasBluetooth(ctx) }
    if (overlayOk && btOk) return
    Card(Modifier.fillMaxWidth(), color = Color(0xFF2A1A0C)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium, color = BikeColors.Warn)
            if (!overlayOk) {
                Text("Allow BikeOS to draw over other apps so your stats float on top of YouTube, Netflix, etc.", color = BikeColors.Text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { Permissions.requestOverlay(activity) }) { Text("Allow overlay") }
                    Spacer(Modifier.width(12.dp))
                    Text("or: ${Permissions.ADB_OVERLAY}", color = BikeColors.TextDim, fontSize = 12.sp)
                }
            }
            if (!btOk) {
                Text("Allow Bluetooth so BikeOS can pair heart-rate straps and share your bike with Zwift and Apple Watch.", color = BikeColors.Text)
                Button(onClick = { Permissions.requestBluetooth(activity) }) { Text("Allow Bluetooth") }
            }
        }
    }
}

@Composable
private fun LastRideCard(r: RideRecord, onClick: () -> Unit) {
    val units = LocalContext.current.graph.settings.current.units
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            SectionTitle("Last ride")
            Text(r.title, style = MaterialTheme.typography.titleMedium, color = BikeColors.Text)
            Text(Format.date(r.startedAtMs), color = BikeColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                StatBlock("Time", Format.clock(r.stats.elapsedSec))
                StatBlock("Output", Format.int(r.stats.workKj), "kJ", BikeColors.Accent)
                StatBlock("Avg power", Format.int(r.stats.avgPower), "W")
                StatBlock("Distance", Format.distance(r.stats.distanceMiles, units), Format.distanceUnit(units))
            }
        }
    }
}

@Composable
fun RideSummaryDialog(r: RideRecord, onDismiss: () -> Unit, onDetails: () -> Unit) {
    val units = LocalContext.current.graph.settings.current.units
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nice ride! · ${r.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    StatBlock("Time", Format.clock(r.stats.elapsedSec))
                    StatBlock("Output", Format.int(r.stats.workKj), "kJ", BikeColors.Accent)
                    StatBlock("Calories", Format.int(r.stats.calories), "kcal", BikeColors.Accent2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    StatBlock("Avg power", Format.int(r.stats.avgPower), "W")
                    StatBlock("Avg cadence", Format.int(r.stats.avgCadence), "rpm")
                    StatBlock("Distance", Format.distance(r.stats.distanceMiles, units), Format.distanceUnit(units))
                }
                r.targetScore?.let { Text("On target $it% of the time", color = BikeColors.Good, fontWeight = FontWeight.Bold) }
                r.newFtp?.let { Text("New FTP: $it W (was ${r.ftpAtRide} W). Your power zones were updated.", color = BikeColors.Accent, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = { Button(onClick = onDetails) { Text("Details & export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
