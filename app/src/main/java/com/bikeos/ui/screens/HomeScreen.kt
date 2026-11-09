package com.bikeos.ui.screens

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.Permissions
import com.bikeos.apps.AppCatalog
import com.bikeos.apps.AppSection
import com.bikeos.apps.CatalogApp
import com.bikeos.ble.HrState
import com.bikeos.core.export.RideRecord
import com.bikeos.core.stats.Streaks
import com.bikeos.core.workout.WorkoutLibrary
import com.bikeos.graph
import com.bikeos.sensor.SensorState
import com.bikeos.ui.Format
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Badge
import com.bikeos.ui.components.ClassArt
import com.bikeos.ui.components.ClassCard
import com.bikeos.ui.components.GhostButton
import com.bikeos.ui.components.GoalMeter
import com.bikeos.ui.components.IconCircleButton
import com.bikeos.ui.components.PrimaryButton
import com.bikeos.ui.components.RowHeader
import com.bikeos.ui.components.SurfaceCard
import com.bikeos.ui.components.TabBar
import com.bikeos.ui.components.WeekBars
import com.bikeos.ui.components.categoryLabel
import com.bikeos.ui.components.difficultyLabel
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeFonts
import java.util.Calendar
import kotlin.math.roundToInt

private val TABS = listOf("Home", "Classes", "Videos", "Apps")

@Composable
fun HomeScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    var needsInstall by remember { mutableStateOf<CatalogApp?>(null) }

    fun open(app: CatalogApp) {
        g.ride.setCompanionApp(app.label.takeIf { app.section == AppSection.WATCH || app.section == AppSection.LISTEN })
        if (app.special == null) g.applyProfile(app.id, app.label)
        when (val r = g.catalog.launch(app)) {
            is AppCatalog.LaunchResult.ShowInfo -> nav.go(Screen.AppleFitness)
            is AppCatalog.LaunchResult.NeedsInstall -> needsInstall = r.app
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(nav)
            if (ride.active) ActiveRideBanner(nav)
            when (nav.homeTab) {
                0 -> HomeTab(nav, ::open)
                1 -> Box(Modifier.padding(horizontal = 40.dp)) { ClassLibrary(nav) }
                2 -> VideosTab(nav)
                else -> AppsTab(nav, ::open)
            }
        }
        ride.lastFinished?.let { rec ->
            RideCompleteScreen(rec, onDone = { g.ride.dismissSummary() }, onDetails = { g.ride.dismissSummary(); nav.go(Screen.RideDetail(rec.id)) })
        }
    }

    needsInstall?.let { app ->
        AlertDialog(
            onDismissRequest = { needsInstall = null },
            containerColor = BikeColors.SurfaceHigh,
            title = { Text("Get ${app.label}") },
            text = {
                Text(
                    (app.note?.let { "$it\n\n" } ?: "") +
                        "Peloton tablets don't include Google Play. Install ${app.label} with Aurora Store, or sideload it with adb (see the README).",
                )
            },
            confirmButton = { Button(onClick = { app.packages.firstOrNull()?.let(g.catalog::openStorePage); needsInstall = null }) { Text("Find app") } },
            dismissButton = { TextButton(onClick = { needsInstall = null }) { Text("Not now") } },
        )
    }
}

// ------------------------------------------------------------------------------------------
// Top bar
// ------------------------------------------------------------------------------------------

@Composable
private fun TopBar(nav: Navigator) {
    val g = LocalContext.current.graph
    val sensor by g.sensors.status.collectAsStateWithLifecycle()
    val hrState by g.heartRate.state.collectAsStateWithLifecycle()
    val ride by g.ride.state.collectAsStateWithLifecycle()
    val ble by g.peripheral.status.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val now by g.clock.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 32.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("BIKE", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = BikeColors.Text, letterSpacing = 1.sp)
            Text("OS", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = BikeColors.Accent, letterSpacing = 1.sp)
        }
        Spacer(Modifier.width(48.dp))
        TabBar(TABS, nav.homeTab, { nav.homeTab = it })
        Spacer(Modifier.weight(1f))
        StatusIcon(
            Icons.AutoMirrored.Filled.DirectionsBike,
            when (sensor.state) {
                SensorState.CONNECTED -> BikeColors.Good
                SensorState.CONNECTING, SensorState.NO_DATA -> BikeColors.Warn
                else -> BikeColors.TextFaint
            },
            "Bike sensor: ${sensor.state.name.lowercase()}",
        ) { nav.go(Screen.Settings) }
        StatusIcon(
            Icons.Default.Favorite,
            if (ride.sample.heartRateBpm != null) BikeColors.Heart else if (hrState == HrState.DISABLED) BikeColors.TextFaint else BikeColors.Warn,
            ride.sample.heartRateBpm?.let { "$it bpm" } ?: "Heart rate",
            text = ride.sample.heartRateBpm?.toString(),
        ) { nav.go(Screen.Settings) }
        StatusIcon(
            Icons.Default.Bluetooth,
            when { ble.connectedDevices.isNotEmpty() -> BikeColors.Ice; ble.advertising -> BikeColors.Text; else -> BikeColors.TextFaint },
            "Bluetooth broadcast",
            text = ble.connectedDevices.size.takeIf { it > 0 }?.toString(),
        ) { nav.go(Screen.Settings) }
        Spacer(Modifier.width(12.dp))
        Text(Format.time(now), style = MaterialTheme.typography.titleMedium, color = BikeColors.TextDim)
        Spacer(Modifier.width(16.dp))
        IconCircleButton(Icons.Default.History, "History", size = 44.dp) { nav.go(Screen.History) }
        Spacer(Modifier.width(10.dp))
        IconCircleButton(Icons.Default.Settings, "Settings", size = 44.dp) { nav.go(Screen.Settings) }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(BikeColors.primaryGradient).clickable { nav.go(Screen.Settings) },
            contentAlignment = Alignment.Center,
        ) {
            Text(settings.riderName.trim().take(1).uppercase().ifEmpty { "R" }, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color, description: String, text: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp))
        if (text != null) { Spacer(Modifier.width(4.dp)); Text(text, color = tint, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun ActiveRideBanner(nav: Navigator) {
    val g = LocalContext.current.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    Row(
        Modifier.padding(horizontal = 40.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF3A120C), BikeColors.Surface))).clickable { nav.go(Screen.Live) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(BikeColors.Accent))
        Spacer(Modifier.width(12.dp))
        Text(ride.workout?.title ?: "Ride", style = MaterialTheme.typography.titleMedium, color = BikeColors.Text)
        Spacer(Modifier.width(16.dp))
        Text(
            "${Format.clock(ride.stats.elapsedSec)}  ·  ${ride.stats.workKj.roundToInt()} kJ  ·  ${ride.sample.powerWatts.roundToInt()} W",
            style = MaterialTheme.typography.bodyLarge, color = BikeColors.TextDim, modifier = Modifier.weight(1f),
        )
        Text("Open ride →", style = MaterialTheme.typography.labelLarge, color = BikeColors.Accent)
    }
}

// ------------------------------------------------------------------------------------------
// Home tab
// ------------------------------------------------------------------------------------------

@Composable
private fun HomeTab(nav: Navigator, open: (CatalogApp) -> Unit) {
    val g = LocalContext.current.graph
    val workouts by g.workouts.all.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val classes = workouts.filter { it.id != WorkoutLibrary.justRide.id }
    val featured = remember(classes) { classes.getOrNull(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % classes.size.coerceAtLeast(1)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 12.dp)) {
        SetupCard()
        Text(greeting(settings.riderName), style = MaterialTheme.typography.headlineLarge, color = BikeColors.Text)
        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth().height(460.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Featured class hero
            if (featured != null) {
                ClassArt(featured, Modifier.weight(0.62f).fillMaxHeight().clickable { nav.go(Screen.WorkoutDetail(featured.id)) }) {
                    Column(Modifier.align(Alignment.BottomStart).padding(32.dp)) {
                        Badge("Featured class", color = BikeColors.Accent)
                        Spacer(Modifier.height(12.dp))
                        Text(featured.title, fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp, color = Color.White, lineHeight = 56.sp)
                        Text(
                            "${featured.durationMin} min  ·  ${categoryLabel(featured.category)}  ·  ${difficultyLabel(featured.difficulty)}",
                            style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton("Start class", icon = Icons.Default.PlayArrow) { g.ride.startWorkout(featured) }
                            GhostButton("Just Ride", icon = Icons.AutoMirrored.Filled.DirectionsBike) { g.ride.justRide() }
                        }
                    }
                }
            }
            Column(Modifier.weight(0.38f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TodayCard(Modifier.weight(1f).fillMaxWidth())
                LiveBikeCard(Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(36.dp))
        RowHeader("Classes for you", action = "See all") { nav.homeTab = 1 }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            classes.forEach { w -> ClassCard(w, Modifier.width(300.dp)) { nav.go(Screen.WorkoutDetail(w.id)) } }
        }

        Spacer(Modifier.height(36.dp))
        RowHeader("Ride with video", action = "Browse") { nav.homeTab = 2 }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            (com.bikeos.core.video.VideoCollections.classes.take(3) + com.bikeos.core.video.VideoCollections.scenic.take(3)).forEach { c ->
                VideoPromoCard(c.title, if (c.scenic) "Scenic ride" else "Ride-along class", c.scenic) { nav.go(Screen.VideoSearch(c.query, c.scenic)) }
            }
        }

        listOf(
            "Train with" to listOf(AppSection.FITNESS),
            "Watch while you ride" to listOf(AppSection.WATCH),
            "Listen" to listOf(AppSection.LISTEN, AppSection.HEALTH),
        ).forEach { (title, sections) ->
            val apps = g.catalog.entries.filter { it.section in sections && it.id !in settings.hiddenCatalogIds }
            if (apps.isEmpty()) return@forEach
            Spacer(Modifier.height(36.dp))
            RowHeader(title, action = "All apps") { nav.homeTab = 3 }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                apps.forEach { app -> ServiceCard(app) { open(app) } }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

private fun greeting(name: String): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val part = when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
    return if (name.isBlank()) part else "$part, ${name.trim()}"
}

@Composable
private fun TodayCard(modifier: Modifier) {
    val g = LocalContext.current.graph
    val rides by g.rides.summaries.collectAsStateWithLifecycle()
    val sum = remember(rides) { Streaks.summarize(rides) }
    val todayMinutes = remember(rides) {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        rides.filter { it.startedAtMs >= start }.sumOf { it.stats.elapsedSec } / 60
    }
    val todayKj = remember(rides) {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        rides.filter { it.startedAtMs >= start }.sumOf { it.stats.workKj }.roundToInt()
    }
    SurfaceCard(modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = MaterialTheme.typography.titleLarge, color = BikeColors.Text, modifier = Modifier.weight(1f))
                Text("${sum.dayStreak}-day streak", style = MaterialTheme.typography.labelLarge, color = BikeColors.Accent2)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GoalMeter("Calories", sum.todayCalories, 500, "kcal", BikeColors.Accent)
                GoalMeter("Minutes", todayMinutes, 30, "min", BikeColors.Good)
                GoalMeter("Output", todayKj, 300, "kJ", BikeColors.Ice)
            }
            Spacer(Modifier.weight(1f))
            val week = remember(rides) {
                val cal = Calendar.getInstance()
                (6 downTo 0).map { back ->
                    val day = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -back); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
                    val start = day.timeInMillis
                    val end = start + 24 * 3600 * 1000L
                    val label = java.text.SimpleDateFormat("EEEEE", java.util.Locale.getDefault()).format(day.time)
                    rides.filter { it.startedAtMs in start until end }.sumOf { it.stats.elapsedSec } / 60 to label
                }
            }
            WeekBars(week.map { it.first }, week.map { it.second }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This week: ${sum.weekRides} rides · ${sum.weekMinutes} min · ${sum.weekKj} kJ", style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim, modifier = Modifier.weight(1f))
                val mfp = g.catalog.entries.first { it.id == "mfp" }
                Text("Log food →", style = MaterialTheme.typography.labelLarge, color = BikeColors.Accent, modifier = Modifier.clickable {
                    g.catalog.launch(mfp)
                })
            }
        }
    }
}

@Composable
private fun LiveBikeCard(modifier: Modifier) {
    val g = LocalContext.current.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    val s = ride.sample
    SurfaceCard(modifier, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveNumber("Cadence", s.cadenceRpm.roundToInt().toString(), BikeColors.Cadence, Modifier.weight(1f))
            LiveNumber("Resistance", s.resistancePct.roundToInt().toString(), BikeColors.Resistance, Modifier.weight(1f))
            LiveNumber("Output", s.powerWatts.roundToInt().toString(), BikeColors.zoneColors[ride.zone.number - 1], Modifier.weight(1f))
        }
    }
}

@Composable
private fun LiveNumber(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
        Text(value, fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, color = color, lineHeight = 44.sp)
    }
}

// ------------------------------------------------------------------------------------------
// Apps tab
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsTab(nav: Navigator, open: (CatalogApp) -> Unit) {
    val g = LocalContext.current.graph
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CatalogApp?>(null) }
    editing?.let { app -> RideProfileDialog(app.id, app.label) { editing = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 12.dp)) {
        if (settings.pinnedApps.isNotEmpty()) {
            RowHeader("Pinned")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                settings.pinnedApps.forEach { pkg ->
                    ServiceCardRaw(g.catalog.label(pkg), BikeColors.SurfaceHigher, g.catalog.icon(pkg), Icons.Default.Apps, "Open") {
                        g.ride.setCompanionApp(g.catalog.label(pkg)); g.catalog.launchPackage(pkg)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
        AppSection.entries.forEach { section ->
            val apps = g.catalog.entries.filter { it.section == section && it.id !in settings.hiddenCatalogIds }
            if (apps.isEmpty()) return@forEach
            RowHeader(section.title)
            if (section == AppSection.FITNESS) {
                Text("Long-press any app to set its ride profile: what the bike does while that app is open.",
                    style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 12.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                apps.forEach { app -> ServiceCard(app, onLongClick = { editing = app }) { open(app) } }
                if (section == AppSection.TOOLS) {
                    ServiceCardRaw("All apps", BikeColors.SurfaceHigher, null, Icons.Default.Apps, "Everything installed") { nav.go(Screen.AllApps) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------
// Service cards
// ------------------------------------------------------------------------------------------

private fun sectionIcon(s: AppSection): ImageVector = when (s) {
    AppSection.FITNESS -> Icons.Default.FitnessCenter
    AppSection.WATCH -> Icons.Default.PlayCircle
    AppSection.LISTEN -> Icons.Default.MusicNote
    AppSection.HEALTH -> Icons.Default.Restaurant
    AppSection.TOOLS -> Icons.Default.Build
}

@Composable
fun ServiceCard(app: CatalogApp, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val g = LocalContext.current.graph
    val tick by g.clock.collectAsStateWithLifecycle()
    val pkg = remember(app, tick / 10_000) { g.catalog.installedPackage(app) }
    val status = when {
        app.special != null -> "Set up"
        pkg != null -> "Open"
        app.webUrl != null -> "Web"
        else -> "Get app"
    }
    ServiceCardRaw(app.label, app.color, pkg?.let(g.catalog::icon), if (app.webUrl != null && pkg == null) Icons.Default.Language else sectionIcon(app.section), status, onLongClick = onLongClick, onClick = onClick)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ServiceCardRaw(
    label: String, color: Color, icon: android.graphics.drawable.Drawable?, fallback: ImageVector, status: String,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val bitmap = remember(icon) { icon?.let { runCatching { it.toBitmap(144, 144).asImageBitmap() }.getOrNull() } }
    Box(
        Modifier.width(232.dp).height(132.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.55f), BikeColors.SurfaceHigh, BikeColors.Surface)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(16.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, null, Modifier.size(36.dp)) else Icon(fallback, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.TopEnd))
        Text(
            label, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).widthIn(max = 200.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------
// Setup & ride complete
// ------------------------------------------------------------------------------------------

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    val activity = ctx as? Activity ?: return
    val tick by ctx.graph.clock.collectAsStateWithLifecycle()
    val overlayOk = remember(tick) { Permissions.canDrawOverlays(ctx) }
    val btOk = remember(tick) { Permissions.hasBluetooth(ctx) }
    if (overlayOk && btOk) return
    SurfaceCard(Modifier.fillMaxWidth().padding(bottom = 20.dp), color = Color(0xFF241A0E)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Finish setting up BikeOS", style = MaterialTheme.typography.titleMedium, color = BikeColors.Warn)
                Text(
                    buildList {
                        if (!overlayOk) add("Allow the stats overlay so your metrics float over YouTube, Netflix and more.")
                        if (!btOk) add("Allow Bluetooth for heart-rate straps, Zwift and Apple Watch.")
                    }.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim,
                )
                if (!overlayOk) Text("Or run: ${Permissions.ADB_OVERLAY}", style = MaterialTheme.typography.bodySmall, color = BikeColors.TextFaint)
            }
            Spacer(Modifier.width(16.dp))
            if (!overlayOk) GhostButton("Allow overlay", height = 44.dp) { Permissions.requestOverlay(activity) }
            if (!btOk) { Spacer(Modifier.width(8.dp)); GhostButton("Allow Bluetooth", height = 44.dp) { Permissions.requestBluetooth(activity) } }
        }
    }
}

/** Full-screen "workout complete" summary. */
@Composable
fun RideCompleteScreen(r: RideRecord, onDone: () -> Unit, onDetails: () -> Unit) {
    val units = LocalContext.current.graph.settings.current.units
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 980.dp).clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2A100B), BikeColors.Surface)))
                .border(1.dp, BikeColors.Outline, RoundedCornerShape(32.dp)).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Badge("Workout complete", color = BikeColors.Accent)
            Spacer(Modifier.height(14.dp))
            Text("Nice ride!", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 72.sp, color = Color.White, lineHeight = 72.sp)
            Text(r.title, style = MaterialTheme.typography.titleLarge, color = BikeColors.TextDim)
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigStat("Total output", Format.int(r.stats.workKj), "kJ", BikeColors.Output)
                BigStat("Time", Format.clock(r.stats.elapsedSec), "", Color.White)
                BigStat("Calories", Format.int(r.stats.calories), "kcal", BikeColors.Accent)
                BigStat("Distance", Format.distance(r.stats.distanceMiles, units), Format.distanceUnit(units), Color.White)
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigStat("Avg power", Format.int(r.stats.avgPower), "W", BikeColors.Power, small = true)
                BigStat("Avg cadence", Format.int(r.stats.avgCadence), "rpm", BikeColors.Cadence, small = true)
                BigStat("Avg resistance", Format.int(r.stats.avgResistance), "%", BikeColors.Resistance, small = true)
                BigStat("Avg heart rate", r.stats.avgHr?.toString() ?: "--", "bpm", BikeColors.Heart, small = true)
            }
            r.targetScore?.let {
                Spacer(Modifier.height(20.dp))
                Text("On target $it% of the class", style = MaterialTheme.typography.titleMedium, color = BikeColors.Good)
            }
            r.newFtp?.let {
                Spacer(Modifier.height(8.dp))
                Text("New FTP: $it W (was ${r.ftpAtRide} W) — power zones updated", style = MaterialTheme.typography.titleMedium, color = BikeColors.Accent2)
            }
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GhostButton("Done", Modifier.width(200.dp), onClick = onDone)
                PrimaryButton("Details & export", Modifier.width(260.dp), onClick = onDetails)
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String, color: Color, small: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = if (small) 40.sp else 60.sp, color = color, lineHeight = if (small) 40.sp else 60.sp)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.labelMedium, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun VideoPromoCard(title: String, kind: String, scenic: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(260.dp).height(132.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(if (scenic) Color(0xFF0E4A3A) else Color(0xFF4A0E1E), BikeColors.Surface)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(34.dp))
        Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.TopEnd))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** Per-app ride profile editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideProfileDialog(appKey: String, label: String, onDismiss: () -> Unit) {
    val g = LocalContext.current.graph
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val current = settings.appProfiles[appKey] ?: com.bikeos.core.control.AppProfile(appKey)
    var overlay by remember { mutableStateOf(current.overlay) }
    var program by remember { mutableStateOf(current.program) }
    var follow by remember { mutableStateOf(current.followTrainer) }
    val workouts by g.workouts.all.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BikeColors.SurfaceHigh,
        title = { Text("Ride profile · $label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("When you open $label from BikeOS:", color = BikeColors.TextDim)
                Text("STATS OVERLAY", style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.bikeos.core.control.OverlayStyle.entries.forEach { o ->
                        ChoiceChip(o.name.lowercase().replaceFirstChar(Char::uppercase), overlay == o) { overlay = o }
                    }
                }
                Text("THE BIKE", style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
                val power = (program as? com.bikeos.core.control.ProfileProgram.Control)?.mode as? com.bikeos.core.control.ControlMode.TargetPower
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("Nothing", program is com.bikeos.core.control.ProfileProgram.None) { program = com.bikeos.core.control.ProfileProgram.None }
                    ChoiceChip("Record a ride", program is com.bikeos.core.control.ProfileProgram.JustRide) { program = com.bikeos.core.control.ProfileProgram.JustRide }
                    ChoiceChip("Hold power", power != null) {
                        program = com.bikeos.core.control.ProfileProgram.Control(com.bikeos.core.control.ControlMode.TargetPower((settings.ftp * 0.65f).roundToInt()))
                    }
                    ChoiceChip("Heart-rate zone", (program as? com.bikeos.core.control.ProfileProgram.Control)?.mode is com.bikeos.core.control.ControlMode.HeartRateZone) {
                        program = com.bikeos.core.control.ProfileProgram.Control(com.bikeos.core.control.ControlMode.HeartRateZone(125, 145))
                    }
                    ChoiceChip("Run a class", program is com.bikeos.core.control.ProfileProgram.Workout) {
                        program = com.bikeos.core.control.ProfileProgram.Workout(workouts.first { it.id != WorkoutLibrary.justRide.id }.id)
                    }
                }
                if (power != null) {
                    Stepper("${power.watts} W", {
                        program = com.bikeos.core.control.ProfileProgram.Control(power.nudge(false))
                    }, { program = com.bikeos.core.control.ProfileProgram.Control(power.nudge(true)) })
                }
                (program as? com.bikeos.core.control.ProfileProgram.Workout)?.let { wp ->
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        workouts.filter { it.id != WorkoutLibrary.justRide.id }.forEach { w ->
                            ChoiceChip(w.title, wp.workoutId == w.id) { program = com.bikeos.core.control.ProfileProgram.Workout(w.id) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Let trainer apps control resistance", color = BikeColors.Text)
                        Text("ERG / hills from Zwift, Rouvy and other Bluetooth apps", style = MaterialTheme.typography.bodySmall, color = BikeColors.TextDim)
                    }
                    androidx.compose.material3.Switch(follow, { follow = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                g.settings.update { it.copy(appProfiles = it.appProfiles + (appKey to com.bikeos.core.control.AppProfile(appKey, overlay, program, follow))) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else BikeColors.Text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else BikeColors.SurfaceHigher)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
