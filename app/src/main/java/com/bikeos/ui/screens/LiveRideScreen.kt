package com.bikeos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.apps.AppSection
import com.bikeos.ble.TrainerTarget
import com.bikeos.core.metrics.PowerZones
import com.bikeos.core.workout.Segment
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
import com.bikeos.ui.components.LineChart
import com.bikeos.ui.components.MetricBar
import com.bikeos.ui.components.MetricCell
import com.bikeos.ui.components.ProgressBar
import com.bikeos.ui.components.SurfaceCard
import com.bikeos.ui.components.TargetGauge
import com.bikeos.ui.components.ZoneBar
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeFonts
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LiveRideScreen(nav: Navigator) {
    val g = LocalContext.current.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var confirmEnd by remember { mutableStateOf(false) }
    var pickApp by remember { mutableStateOf(false) }

    if (!ride.active) {
        val rec = ride.lastFinished
        if (rec != null) {
            RideCompleteScreen(rec, onDone = { g.ride.dismissSummary(); nav.back() }, onDetails = {
                g.ride.dismissSummary(); nav.replace(Screen.RideDetail(rec.id))
            })
        } else {
            LaunchedEffect(Unit) { nav.back() }
        }
        return
    }

    val s = ride.sample
    val stats = ride.stats
    val p = ride.progress
    val eval = ride.evaluation
    val structured = ride.workout?.id != WorkoutLibrary.justRide.id
    val seg = p?.segment?.takeIf { structured }
    val zoneColor = BikeColors.zoneColors[ride.zone.number - 1]

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(Icons.Default.Home, "Home") { nav.back() }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(ride.workout?.title ?: "Ride", style = MaterialTheme.typography.headlineMedium, color = BikeColors.Text)
                val trainer = ride.trainerTarget
                val sub = when {
                    trainer is TrainerTarget.Erg -> "Controlled by app · ERG ${trainer.watts} W"
                    trainer is TrainerTarget.Simulation -> "Controlled by app · grade ${"%.1f".format(trainer.gradePct)}%"
                    ride.phase == RidePhase.PAUSED -> "Paused"
                    else -> ride.companionApp?.let { "Riding with $it" } ?: "Recording"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(50)).background(if (ride.phase == RidePhase.PAUSED) BikeColors.Warn else BikeColors.Accent))
                    Spacer(Modifier.width(8.dp))
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = if (ride.phase == RidePhase.PAUSED) BikeColors.Warn else BikeColors.TextDim)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.clock(stats.elapsedSec), fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 52.sp, color = BikeColors.Text, lineHeight = 52.sp)
                Text(if (structured && p != null) "${Format.clock(p.totalRemainingSec)} remaining" else "elapsed", style = MaterialTheme.typography.labelMedium, color = BikeColors.TextDim)
            }
        }
        if (structured && p != null) {
            Spacer(Modifier.height(14.dp))
            SegmentStrip(ride.workout!!.segments, p.totalFraction)
        }
        Spacer(Modifier.height(18.dp))

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // ---- Left: current segment ----
            Column(Modifier.weight(0.28f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SurfaceCard(Modifier.fillMaxWidth().weight(1f), color = BikeColors.SurfaceHigh) {
                    Column {
                        if (seg != null && p != null) {
                            Badge("Now", color = BikeColors.Accent)
                            Spacer(Modifier.height(10.dp))
                            Text(seg.name, fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, color = BikeColors.Text, lineHeight = 42.sp)
                            seg.cue?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = BikeColors.TextDim) }
                            Spacer(Modifier.weight(1f))
                            Text(Format.clock(p.segmentRemainingSec), fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 96.sp, color = BikeColors.Text, lineHeight = 96.sp)
                            ProgressBar(p.segmentFraction, BikeColors.Accent, height = 6.dp)
                            p.next?.let {
                                Spacer(Modifier.height(14.dp))
                                Text("UP NEXT", style = MaterialTheme.typography.labelSmall, color = BikeColors.TextFaint)
                                Text(it.name, style = MaterialTheme.typography.titleMedium, color = BikeColors.Text)
                                Text(describe(it, settings.ftp), style = MaterialTheme.typography.bodySmall, color = BikeColors.TextDim)
                            }
                        } else {
                            Badge("Free ride", color = BikeColors.Ice, textColor = Color.Black)
                            Spacer(Modifier.height(10.dp))
                            Text("Ride your way", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, color = BikeColors.Text)
                            Text("Every pedal stroke is recorded. Open any app and your stats follow you.", style = MaterialTheme.typography.bodyLarge, color = BikeColors.TextDim)
                            Spacer(Modifier.weight(1f))
                            Text("AVG POWER", style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
                            Text("${stats.avgPower.roundToInt()} W", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp, color = BikeColors.Power)
                        }
                    }
                }
                ride.targetScore?.let {
                    SurfaceCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("On target", style = MaterialTheme.typography.titleMedium, color = BikeColors.Text, modifier = Modifier.weight(1f))
                            Text("$it%", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = BikeColors.Good)
                        }
                    }
                }
            }

            // ---- Center: gauges ----
            Column(Modifier.weight(0.44f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ride.suggestion?.let { sug ->
                    val diff = sug.resistance - s.resistancePct
                    if (sug.automatic || abs(diff) >= 3f) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(Brush.horizontalGradient(listOf(if (sug.automatic) Color(0xFF0E3326) else Color(0xFF3A2808), BikeColors.Surface)))
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (sug.automatic) "Auto resistance → ${sug.resistance.roundToInt()}"
                                else "${if (diff > 0) "▲ Turn resistance UP" else "▼ Turn resistance DOWN"} to ${sug.resistance.roundToInt()}",
                                style = MaterialTheme.typography.titleLarge, color = if (sug.automatic) BikeColors.Good else BikeColors.Warn, modifier = Modifier.weight(1f),
                            )
                            Text(sug.reason, style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SurfaceCard(Modifier.weight(1f).fillMaxHeight(), padding = 4.dp) {
                        TargetGauge("Cadence", s.cadenceRpm, "rpm", BikeColors.Cadence, 0f, 130f, seg?.cadence, eval?.cadence ?: TargetStatus.NONE, Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                    SurfaceCard(Modifier.weight(1f).fillMaxHeight(), padding = 4.dp) {
                        TargetGauge("Resistance", s.resistancePct, "%", BikeColors.Resistance, 0f, 100f, seg?.resistance, eval?.resistance ?: TargetStatus.NONE, Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                }
                SurfaceCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("POWER", style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 8.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("${s.powerWatts.roundToInt()}", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, color = zoneColor, lineHeight = 44.sp)
                            Text(" W", style = MaterialTheme.typography.labelMedium, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 6.dp))
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Zone ${ride.zone.number} · ${ride.zone.name}" + (eval?.powerBand?.let { "   target ${it.first}-${it.last} W" } ?: ""),
                                style = MaterialTheme.typography.labelLarge, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ZoneBar(ride.zone.number, Modifier.fillMaxWidth(), target = seg?.powerZone)
                    }
                }
            }

            // ---- Right: chart + controls (scrolls on short screens) ----
            Column(Modifier.weight(0.28f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SurfaceCard(Modifier.fillMaxWidth().height(260.dp)) {
                    Column {
                        Text("OUTPUT · LAST 3 MIN", style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
                        Spacer(Modifier.height(10.dp))
                        val ftp = settings.ftp
                        val bands = (2..6).map { z ->
                            val b = PowerZones.targetBand(z, ftp)
                            b.first.toFloat()..b.last.toFloat() to BikeColors.zoneColors[z - 1].copy(alpha = 0.06f)
                        }
                        LineChart(ride.powerHistory, zoneColor, Modifier.fillMaxSize(), maxValue = maxOf(ftp * 1.6f, ride.powerHistory.maxOrNull() ?: 0f), bands = bands)
                    }
                }
                GhostButton("Watch while riding", Modifier.fillMaxWidth(), icon = Icons.Default.Tv) { pickApp = true }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        if (ride.phase == RidePhase.PAUSED) "Resume" else "Pause", Modifier.weight(1f),
                        icon = if (ride.phase == RidePhase.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                    ) { g.ride.togglePause() }
                    GhostButton("End ride", Modifier.weight(1f), icon = Icons.Default.Stop, tint = BikeColors.Accent) { confirmEnd = true }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        MetricBar(
            listOf(
                MetricCell("Output", Format.int(stats.workKj), "kJ", BikeColors.Output),
                MetricCell("Cadence", s.cadenceRpm.roundToInt().toString(), "rpm", BikeColors.Cadence, seg?.cadence?.let { "target $it" }),
                MetricCell("Resistance", s.resistancePct.roundToInt().toString(), "%", BikeColors.Resistance, seg?.resistance?.let { "target $it" }),
                MetricCell("Power", s.powerWatts.roundToInt().toString(), "W", zoneColor, "avg ${stats.avgPower.roundToInt()}"),
                MetricCell("Speed", Format.speed(s.speedMph, settings.units), Format.speedUnit(settings.units)),
                MetricCell("Distance", Format.distance(stats.distanceMiles, settings.units), Format.distanceUnit(settings.units)),
                MetricCell("Calories", Format.int(stats.calories), "kcal", BikeColors.Accent),
                MetricCell("Heart rate", s.heartRateBpm?.toString() ?: "--", "bpm", BikeColors.Heart),
            ),
            Modifier.fillMaxWidth(),
            valueSize = 46.sp,
        )
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            containerColor = BikeColors.SurfaceHigh,
            title = { Text("End this ride?") },
            text = { Text("Rides longer than a minute are saved to your history.") },
            confirmButton = { Button(onClick = { confirmEnd = false; g.ride.finish() }) { Text("End & save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmEnd = false; g.ride.finish(save = false) }) { Text("Discard", color = BikeColors.Bad) }
                    TextButton(onClick = { confirmEnd = false }) { Text("Keep riding") }
                }
            },
        )
    }
    if (pickApp) {
        val apps = g.catalog.entries.filter { it.section == AppSection.WATCH || it.section == AppSection.LISTEN || it.id == "zwift" }
        AlertDialog(
            onDismissRequest = { pickApp = false },
            containerColor = BikeColors.SurfaceHigh,
            title = { Text("Ride with…") },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    apps.forEach { app ->
                        ServiceCard(app) {
                            pickApp = false
                            g.ride.setCompanionApp(app.label)
                            g.catalog.launch(app)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickApp = false }) { Text("Cancel") } },
        )
    }
}

/** Class timeline: every segment coloured by intensity, played portion dimmed, with a playhead. */
@Composable
private fun SegmentStrip(segments: List<Segment>, fraction: Float) {
    val total = segments.sumOf { it.durationSec }.toFloat()
    Box(Modifier.fillMaxWidth().height(34.dp)) {
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), verticalAlignment = Alignment.Bottom) {
            segments.forEach { seg ->
                val intensity = seg.powerZone?.let { (it - 1) / 6f }
                    ?: seg.resistance?.let { ((it.mid - 15f) / 50f).coerceIn(0f, 1f) } ?: 0.2f
                val c = BikeColors.zoneColors[(intensity * 6).roundToInt().coerceIn(0, 6)]
                Box(
                    Modifier.weight(seg.durationSec / total).fillMaxHeight(0.35f + 0.65f * intensity)
                        .padding(horizontal = 1.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(c.copy(alpha = 0.85f)),
                )
            }
        }
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f)))
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.002f, 1f)).fillMaxHeight()) {
            Box(Modifier.align(Alignment.CenterEnd).width(3.dp).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White))
        }
    }
}

fun describe(seg: Segment, ftp: Int): String = buildList {
    seg.cadence?.let { add("$it rpm") }
    seg.resistance?.let { add("res $it") }
    seg.powerZone?.let { z -> PowerZones.targetBand(z, ftp).let { add("Z$z ${it.first}-${it.last} W") } }
    add(Format.clock(seg.durationSec))
}.joinToString(" · ")
