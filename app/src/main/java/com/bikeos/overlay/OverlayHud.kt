package com.bikeos.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.core.workout.TargetStatus
import com.bikeos.core.workout.WorkoutLibrary
import com.bikeos.graph
import com.bikeos.ride.RidePhase
import com.bikeos.ui.Format
import com.bikeos.ui.components.ProgressBar
import com.bikeos.ui.components.TargetChip
import com.bikeos.ui.theme.BikeColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The floating stats bar shown on top of other apps during a ride. */
@Composable
fun OverlayHud(onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit, onOpenBikeOS: () -> Unit) {
    val g = LocalContext.current.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(confirmEnd) { if (confirmEnd) { delay(3_000); confirmEnd = false } }

    val sample = ride.sample
    val stats = ride.stats
    val eval = ride.evaluation
    val seg = ride.progress?.segment?.takeIf { ride.workout?.id != WorkoutLibrary.justRide.id }
    val zoneColor = BikeColors.zoneColors[ride.zone.number - 1]
    val compact = settings.overlayCompact

    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE607090D))
            .border(1.dp, BikeColors.Outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(max = 1100.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
            Icon(
                Icons.Default.DragIndicator, "Move", tint = BikeColors.TextDim,
                modifier = Modifier.size(28.dp).pointerInput(Unit) {
                    detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                },
            )
            HudValue(Format.clock(ride.progress?.takeIf { seg != null }?.totalRemainingSec ?: stats.elapsedSec), if (seg != null) "left" else "time", BikeColors.Text, compact)
            HudValue(sample.powerWatts.roundToInt().toString(), "W · Z${ride.zone.number}", zoneColor, compact, eval?.powerBand?.let { "${it.first}-${it.last}" }, eval?.power)
            HudValue(sample.cadenceRpm.roundToInt().toString(), "rpm", BikeColors.Cadence, compact, seg?.cadence?.toString(), eval?.cadence)
            HudValue(sample.resistancePct.roundToInt().toString(), "res", BikeColors.Resistance, compact, seg?.resistance?.toString(), eval?.resistance)
            if (!compact) {
                HudValue(stats.workKj.roundToInt().toString(), "kJ", BikeColors.Accent, false)
                HudValue(Format.speed(sample.speedMph, settings.units), Format.speedUnit(settings.units), BikeColors.Text, false)
                HudValue(Format.distance(stats.distanceMiles, settings.units), Format.distanceUnit(settings.units), BikeColors.Text, false)
                HudValue(stats.calories.roundToInt().toString(), "kcal", BikeColors.Accent2, false)
            }
            sample.heartRateBpm?.let { HudValue(it.toString(), "bpm", BikeColors.Heart, compact) }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ride.active) {
                    HudButton(if (ride.phase == RidePhase.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause, "Pause") { g.ride.togglePause() }
                    HudButton(Icons.Default.Stop, "End", tint = if (confirmEnd) BikeColors.Bad else BikeColors.Text) {
                        if (confirmEnd) { g.ride.finish(); confirmEnd = false } else confirmEnd = true
                    }
                }
                HudButton(if (compact) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess, "Size") {
                    g.settings.update { it.copy(overlayCompact = !it.overlayCompact) }
                }
                HudButton(Icons.Default.Home, "BikeOS", onClick = onOpenBikeOS)
            }
        }

        if (confirmEnd) {
            Text("Tap ■ again to end and save the ride", color = BikeColors.Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (ride.phase == RidePhase.PAUSED) {
            Text("PAUSED · pedal or tap ▶ to resume", color = BikeColors.Warn, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }

        val p = ride.progress
        if (seg != null && p != null && !compact) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(seg.name, color = BikeColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(Format.clock(p.segmentRemainingSec), color = BikeColors.Accent, fontWeight = FontWeight.Black, fontSize = 16.sp)
                seg.cue?.let { Text(it, color = BikeColors.TextDim, fontSize = 14.sp) }
                p.next?.let { Text("Next: ${it.name}", color = BikeColors.TextDim, fontSize = 13.sp) }
                ride.targetScore?.let { Text("On target $it%", color = BikeColors.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(700.dp)) { ProgressBar(p.segmentFraction, BikeColors.Accent, height = 4.dp) }
        }
        ride.suggestion?.let { sug ->
            val diff = sug.resistance - sample.resistancePct
            if (sug.automatic || kotlin.math.abs(diff) >= 3f) {
                Spacer(Modifier.height(4.dp))
                val text = if (sug.automatic) "Auto resistance → ${sug.resistance.roundToInt()}  (${sug.reason})"
                else "${if (diff > 0) "▲ Turn up" else "▼ Turn down"} resistance to ${sug.resistance.roundToInt()}  (${sug.reason})"
                Text(text, color = if (sug.automatic) BikeColors.Accent else BikeColors.Warn, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun HudValue(value: String, unit: String, color: Color, compact: Boolean, target: String? = null, status: TargetStatus? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = if (compact) 22.sp else 30.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = BikeColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (target != null && status != null) TargetChip(target, status, compact = true)
    }
}

@Composable
private fun HudButton(icon: ImageVector, label: String, tint: Color = BikeColors.Text, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(BikeColors.SurfaceHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp)) }
}
