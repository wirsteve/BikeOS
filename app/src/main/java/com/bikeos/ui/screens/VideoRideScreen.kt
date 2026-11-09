package com.bikeos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.core.video.ScenicPacer
import com.bikeos.core.video.VideoRide
import com.bikeos.core.video.VideoSource
import com.bikeos.core.workout.TargetStatus
import com.bikeos.core.workout.WorkoutLibrary
import com.bikeos.graph
import com.bikeos.ride.RidePhase
import com.bikeos.ui.Format
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Badge
import com.bikeos.ui.components.GhostButton
import com.bikeos.ui.components.IconCircleButton
import com.bikeos.ui.components.MetricBar
import com.bikeos.ui.components.MetricCell
import com.bikeos.ui.components.PrimaryButton
import com.bikeos.ui.components.ProgressBar
import com.bikeos.ui.components.TargetChip
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeFonts
import com.bikeos.ui.video.MediaVideoPlayer
import com.bikeos.ui.video.VideoControl
import com.bikeos.ui.video.YouTubeVideoPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen video with the ride on top: metric bar, class targets and coaching, scenic speed sync.
 * The ride starts automatically and is recorded like any other.
 */
@Composable
fun VideoRideScreen(nav: Navigator, initial: VideoRide) {
    val g = LocalContext.current.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var scenic by remember { mutableStateOf(initial.scenic) }
    var control by remember { mutableStateOf<VideoControl?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var pickClass by remember { mutableStateOf(false) }
    val pacer = remember { ScenicPacer() }
    val title = initial.source.title

    // Start (or join) a ride, and remember the video.
    LaunchedEffect(Unit) {
        g.videos.remember(initial)
        val w = initial.workoutId?.let(g.workouts::find)
        if (!g.ride.state.value.active) {
            if (w != null) g.ride.startWorkout(w, companionApp = title) else g.ride.justRide(title)
        } else {
            g.ride.setCompanionApp(title)
        }
    }

    // Scenic mode: video speed follows the bike. Otherwise the video simply plays, pausing with the ride.
    LaunchedEffect(scenic, control) {
        val c = control ?: return@LaunchedEffect
        if (!scenic) c.setRate(1f)
        while (true) {
            val st = g.ride.state.value
            if (scenic) {
                val r = pacer.update(st.sample.speedMph, st.sample.isPedaling && st.phase != RidePhase.PAUSED)
                c.setRate(if (initial.source is VideoSource.YouTube && r > 0f) pacer.nearestYouTubeRate(r) else r)
            } else if (st.phase == RidePhase.PAUSED) {
                c.pause()
            }
            delay(if (initial.source is VideoSource.YouTube) 1_500 else 500)
        }
    }

    val s = ride.sample
    val stats = ride.stats
    val structured = ride.workout?.id != WorkoutLibrary.justRide.id
    val seg = ride.progress?.segment?.takeIf { structured && ride.active }
    val eval = ride.evaluation
    val zoneColor = BikeColors.zoneColors[ride.zone.number - 1]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val src = initial.source) {
            is VideoSource.YouTube -> YouTubeVideoPlayer(
                src.videoId, Modifier.fillMaxSize(), onControl = { control = it }, onError = { error = it },
                onTitle = { t -> if (t.isNotBlank()) g.videos.rename(src, t) },
            )
            is VideoSource.Media -> MediaVideoPlayer(src.uri, Modifier.fillMaxSize(), onControl = { control = it }, onError = { error = "Couldn't play this video ($it)." })
        }

        // Top chrome
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", background = Color.Black.copy(alpha = 0.4f)) { nav.back() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (ride.workout?.takeIf { structured }?.title?.let { "$it · " } ?: "") +
                        (if (scenic) "Scenic: video speed follows you" else "Ride-along video"),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f),
                )
            }
            Text(Format.clock(stats.elapsedSec), fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, color = Color.White, lineHeight = 44.sp)
            Spacer(Modifier.width(18.dp))
            ChromeToggle(Icons.Default.Landscape, "Scenic", scenic) { scenic = !scenic }
            Spacer(Modifier.width(8.dp))
            ChromeToggle(Icons.Default.Timeline, "Class", structured) { pickClass = true }
            Spacer(Modifier.width(8.dp))
            IconCircleButton(
                if (ride.phase == RidePhase.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                if (ride.phase == RidePhase.PAUSED) "Resume" else "Pause", background = Color.Black.copy(alpha = 0.4f),
            ) {
                g.ride.togglePause()
                if (ride.phase == RidePhase.PAUSED) control?.play() else control?.pause()
            }
            Spacer(Modifier.width(8.dp))
            GhostButton("End ride", icon = Icons.Default.Stop, height = 48.dp, tint = BikeColors.Accent) { confirmEnd = true }
        }

        // Class card (when a BikeOS class runs alongside the video)
        if (seg != null) {
            val p = ride.progress!!
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 28.dp).widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(20.dp),
            ) {
                Badge("Now", color = BikeColors.Accent)
                Spacer(Modifier.height(8.dp))
                Text(seg.name, fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = Color.White, lineHeight = 34.sp)
                Text(Format.clock(p.segmentRemainingSec), fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, color = Color.White, lineHeight = 64.sp)
                ProgressBar(p.segmentFraction, BikeColors.Accent, height = 4.dp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    seg.cadence?.let { TargetChip("$it rpm", eval?.cadence ?: TargetStatus.NONE) }
                    seg.resistance?.let { TargetChip("res $it", eval?.resistance ?: TargetStatus.NONE) }
                }
                eval?.powerBand?.let { Spacer(Modifier.height(6.dp)); TargetChip("${it.first}-${it.last} W", eval.power) }
                p.next?.let { Text("Next: ${it.name}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp)) }
            }
        }

        // Coaching + metric bar
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
            ride.suggestion?.let { sug ->
                val diff = sug.resistance - s.resistancePct
                if (sug.automatic || abs(diff) >= 3f) {
                    Text(
                        if (sug.automatic) "Auto resistance → ${sug.resistance.roundToInt()}  ·  ${sug.reason}"
                        else "${if (diff > 0) "▲ Turn resistance UP" else "▼ Turn resistance DOWN"} to ${sug.resistance.roundToInt()}  ·  ${sug.reason}",
                        style = MaterialTheme.typography.titleMedium, color = if (sug.automatic) BikeColors.Good else BikeColors.Warn,
                        modifier = Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
            MetricBar(
                listOf(
                    MetricCell("Output", Format.int(stats.workKj), "kJ", BikeColors.Output),
                    MetricCell("Cadence", s.cadenceRpm.roundToInt().toString(), "rpm", BikeColors.Cadence),
                    MetricCell("Resistance", s.resistancePct.roundToInt().toString(), "%", BikeColors.Resistance),
                    MetricCell("Power", s.powerWatts.roundToInt().toString(), "W", zoneColor),
                    MetricCell("Speed", Format.speed(s.speedMph, settings.units), Format.speedUnit(settings.units)),
                    MetricCell("Distance", Format.distance(stats.distanceMiles, settings.units), Format.distanceUnit(settings.units)),
                    MetricCell("Heart rate", s.heartRateBpm?.toString() ?: "--", "bpm", BikeColors.Heart),
                ),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.35f)),
                valueSize = 40.sp,
            )
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 620.dp).clip(RoundedCornerShape(24.dp)).background(BikeColors.SurfaceHigh).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play this video here", style = MaterialTheme.typography.titleLarge, color = BikeColors.Text)
                Spacer(Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodyLarge, color = BikeColors.TextDim)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    (initial.source as? VideoSource.YouTube)?.let { yt ->
                        PrimaryButton("Open in YouTube") {
                            val app = g.catalog.entries.first { it.id == "youtube" }
                            val pkg = g.catalog.installedPackage(app)
                            if (pkg != null) {
                                g.context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=${yt.videoId}"))
                                        .setPackage(pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } else {
                                g.catalog.openWeb("https://m.youtube.com/watch?v=${yt.videoId}", false, "YouTube")
                            }
                        }
                    }
                    GhostButton("Keep riding") { error = null }
                }
            }
        }

        if (!ride.active) {
            ride.lastFinished?.let { rec ->
                LaunchedEffect(rec.id) { control?.pause() }
                RideCompleteScreen(rec, onDone = { g.ride.dismissSummary(); nav.back() }, onDetails = {
                    g.ride.dismissSummary(); nav.replace(Screen.RideDetail(rec.id))
                })
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            containerColor = BikeColors.SurfaceHigh,
            title = { Text("End this ride?") },
            text = { Text("Rides longer than a minute are saved to your history.") },
            confirmButton = { Button(onClick = { confirmEnd = false; g.ride.finish() }) { Text("End & save") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep riding") } },
        )
    }
    if (pickClass) {
        val classes = g.workouts.all.collectAsStateWithLifecycle().value.filter { it.id != WorkoutLibrary.justRide.id }
        AlertDialog(
            onDismissRequest = { pickClass = false },
            containerColor = BikeColors.SurfaceHigh,
            title = { Text("Ride a BikeOS class with this video") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Targets, countdowns and resistance coaching appear over the video.", color = BikeColors.TextDim)
                    Spacer(Modifier.height(6.dp))
                    classes.forEach { w ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BikeColors.Surface).clickable {
                                pickClass = false
                                g.ride.startWorkout(w, companionApp = title)
                            }.padding(14.dp),
                        ) {
                            Text(w.title, style = MaterialTheme.typography.titleSmall, color = BikeColors.Text, modifier = Modifier.weight(1f))
                            Text("${w.durationMin} min", style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickClass = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ChromeToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.Black else Color.White)
    }
}
