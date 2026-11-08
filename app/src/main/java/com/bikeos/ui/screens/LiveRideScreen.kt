package com.bikeos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.bikeos.ui.components.Card
import com.bikeos.ui.components.LineChart
import com.bikeos.ui.components.Metric
import com.bikeos.ui.components.ProgressBar
import com.bikeos.ui.components.SectionTitle
import com.bikeos.ui.theme.BikeColors
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
        // Ride finished: show the summary, then go home.
        val rec = ride.lastFinished
        if (rec != null) {
            RideSummaryDialog(rec, onDismiss = { g.ride.dismissSummary(); nav.back() }, onDetails = {
                g.ride.dismissSummary(); nav.replace(com.bikeos.ui.Screen.RideDetail(rec.id))
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

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ride.workout?.title ?: "Ride", style = MaterialTheme.typography.headlineMedium, color = BikeColors.Text)
                val trainer = ride.trainerTarget
                val sub = when {
                    trainer is TrainerTarget.Erg -> "Controlled by app · ERG ${trainer.watts} W"
                    trainer is TrainerTarget.Simulation -> "Controlled by app · grade ${"%.1f".format(trainer.gradePct)}%"
                    ride.phase == RidePhase.PAUSED -> "Paused"
                    else -> ride.companionApp?.let { "Riding with $it" } ?: "Recording"
                }
                Text(sub, color = if (ride.phase == RidePhase.PAUSED) BikeColors.Warn else BikeColors.TextDim)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.clock(stats.elapsedSec), fontSize = 48.sp, fontWeight = FontWeight.Black, color = BikeColors.Text)
                if (structured && p != null) Text("${Format.clock(p.totalRemainingSec)} left", color = BikeColors.TextDim)
            }
        }
        if (structured && p != null) {
            Spacer(Modifier.height(8.dp))
            SegmentStrip(ride.workout!!.segments, p.totalFraction)
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // ---- Main metrics ----
            Column(Modifier.weight(0.68f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigMetric("Cadence", s.cadenceRpm.roundToInt().toString(), "rpm", BikeColors.Cadence, seg?.cadence?.let { "$it rpm" }, eval?.cadence, Modifier.weight(1f))
                    BigMetric("Resistance", s.resistancePct.roundToInt().toString(), "%", BikeColors.Resistance, seg?.resistance?.let { "$it" }, eval?.resistance, Modifier.weight(1f))
                    BigMetric(
                        "Power · Zone ${ride.zone.number}", s.powerWatts.roundToInt().toString(), "W", zoneColor,
                        eval?.powerBand?.let { "${it.first}-${it.last} W" }, eval?.power, Modifier.weight(1f),
                    )
                }
                ride.suggestion?.let { sug ->
                    val diff = sug.resistance - s.resistancePct
                    if (sug.automatic || abs(diff) >= 3f) {
                        Card(Modifier.fillMaxWidth(), color = if (sug.automatic) Color(0xFF0D2A22) else Color(0xFF2E2208), padding = 12.dp) {
                            Text(
                                if (sug.automatic) "Auto resistance set to ${sug.resistance.roundToInt()} · ${sug.reason}"
                                else "${if (diff > 0) "▲ Turn the knob UP" else "▼ Turn the knob DOWN"} to ${sug.resistance.roundToInt()} · ${sug.reason}",
                                color = if (sug.automatic) BikeColors.Accent else BikeColors.Warn, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    Column {
                        SectionTitle("Power · last 3 minutes")
                        val ftp = settings.ftp
                        val bands = (2..6).map { z ->
                            val b = PowerZones.targetBand(z, ftp)
                            b.first.toFloat()..b.last.toFloat() to BikeColors.zoneColors[z - 1].copy(alpha = 0.06f)
                        }
                        LineChart(ride.powerHistory, zoneColor, Modifier.fillMaxSize(), maxValue = maxOf(ftp * 1.6f, ride.powerHistory.maxOrNull() ?: 0f), bands = bands)
                    }
                }
            }
            // ---- Side stats ----
            // Scrollable so the ride controls stay reachable on shorter screens.
            Column(Modifier.weight(0.32f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (seg != null && p != null) {
                    Card(Modifier.fillMaxWidth(), color = BikeColors.SurfaceHigh) {
                        Column {
                            Text(seg.name, fontSize = 26.sp, fontWeight = FontWeight.Black, color = BikeColors.Text)
                            seg.cue?.let { Text(it, color = BikeColors.TextDim) }
                            Spacer(Modifier.height(6.dp))
                            Text(Format.clock(p.segmentRemainingSec), fontSize = 40.sp, fontWeight = FontWeight.Black, color = BikeColors.Accent)
                            ProgressBar(p.segmentFraction, BikeColors.Accent)
                            p.next?.let {
                                Spacer(Modifier.height(8.dp))
                                Text("Next: ${it.name} · ${describe(it, settings.ftp)}", color = BikeColors.TextDim)
                            }
                            ride.targetScore?.let { Text("On target $it%", color = BikeColors.Good, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            StatBlock("Output", Format.int(stats.workKj), "kJ", BikeColors.Accent)
                            StatBlock("Calories", Format.int(stats.calories), "kcal", BikeColors.Accent2)
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            StatBlock("Speed", Format.speed(s.speedMph, settings.units), Format.speedUnit(settings.units))
                            StatBlock("Distance", Format.distance(stats.distanceMiles, settings.units), Format.distanceUnit(settings.units))
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            StatBlock("Avg power", Format.int(stats.avgPower), "W")
                            StatBlock("Heart rate", s.heartRateBpm?.toString() ?: "--", "bpm", BikeColors.Heart)
                        }
                    }
                }
                Button(
                    onClick = { pickApp = true }, modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BikeColors.Cadence),
                ) {
                    Icon(Icons.Default.Tv, null); Spacer(Modifier.width(8.dp)); Text("Watch something while riding")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { g.ride.togglePause() }, modifier = Modifier.weight(1f).height(56.dp)) {
                        Icon(if (ride.phase == RidePhase.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (ride.phase == RidePhase.PAUSED) "Resume" else "Pause")
                    }
                    Button(
                        onClick = { confirmEnd = true }, modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BikeColors.Bad),
                    ) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("End ride") }
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
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
            title = { Text("Ride with…") },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    apps.forEach { app ->
                        AppTile(app.label, app.color, g.catalog.installedPackage(app)?.let(g.catalog::icon), null, size = 112.dp, onClick = {
                            pickApp = false
                            g.ride.setCompanionApp(app.label)
                            g.catalog.launch(app)
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickApp = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BigMetric(label: String, value: String, unit: String, color: Color, target: String?, status: TargetStatus?, modifier: Modifier) {
    val border = when (status) {
        TargetStatus.IN_RANGE -> BikeColors.Good
        TargetStatus.BELOW, TargetStatus.ABOVE -> BikeColors.Warn
        else -> BikeColors.Outline
    }
    Box(
        modifier.clip(RoundedCornerShape(24.dp)).background(BikeColors.Surface)
            .then(Modifier.background(border.copy(alpha = if (status == TargetStatus.NONE || status == null) 0f else 0.08f)))
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Metric(label, value, unit, color, valueSize = 76.sp, target = target, status = status ?: TargetStatus.NONE)
    }
}

/** Coloured overview of the whole class with a playhead. */
@Composable
private fun SegmentStrip(segments: List<Segment>, fraction: Float) {
    val total = segments.sumOf { it.durationSec }.toFloat()
    Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxSize()) {
            segments.forEach { seg ->
                val intensity = seg.powerZone?.let { (it - 1) / 6f }
                    ?: seg.resistance?.let { ((it.mid - 15f) / 50f).coerceIn(0f, 1f) } ?: 0.2f
                val c = BikeColors.zoneColors[(intensity * 6).roundToInt().coerceIn(0, 6)]
                Box(Modifier.weight(seg.durationSec / total).fillMaxHeight().padding(horizontal = 0.5.dp).background(c.copy(alpha = 0.75f)))
            }
        }
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Color.Black.copy(alpha = 0.45f)))
    }
}

fun describe(seg: Segment, ftp: Int): String = buildList {
    seg.cadence?.let { add("$it rpm") }
    seg.resistance?.let { add("res $it") }
    seg.powerZone?.let { z -> PowerZones.targetBand(z, ftp).let { add("Z$z ${it.first}-${it.last} W") } }
    add(Format.clock(seg.durationSec))
}.joinToString(" · ")
