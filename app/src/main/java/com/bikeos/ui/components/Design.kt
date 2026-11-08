package com.bikeos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bikeos.core.workout.Range
import com.bikeos.core.workout.TargetStatus
import com.bikeos.core.workout.Workout
import com.bikeos.core.workout.WorkoutCategory
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeFonts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Class artwork: original, procedurally generated "studio light" compositions per category.
// ---------------------------------------------------------------------------------------------

private data class Palette(val deep: Color, val mid: Color, val glow: Color)

private fun paletteFor(category: WorkoutCategory): Palette = when (category) {
    WorkoutCategory.INTERVALS -> Palette(Color(0xFF1A0508), Color(0xFF7A1222), Color(0xFFFF4F3A))
    WorkoutCategory.ENDURANCE -> Palette(Color(0xFF041218), Color(0xFF0D4A63), Color(0xFF41C9FF))
    WorkoutCategory.CLIMB -> Palette(Color(0xFF140A02), Color(0xFF6B3208), Color(0xFFFFA23A))
    WorkoutCategory.POWER_ZONE -> Palette(Color(0xFF0B0716), Color(0xFF3B1E73), Color(0xFFB66BFF))
    WorkoutCategory.LOW_IMPACT -> Palette(Color(0xFF03130D), Color(0xFF0E5A3D), Color(0xFF3DDC97))
    WorkoutCategory.TEST -> Palette(Color(0xFF15120A), Color(0xFF6A5A10), Color(0xFFFFD84D))
    WorkoutCategory.CUSTOM -> Palette(Color(0xFF0E0E12), Color(0xFF34343F), Color(0xFFDADAE6))
}

fun categoryColor(category: WorkoutCategory): Color = paletteFor(category).glow

/** Dark gradient with soft light beams and a stylised intensity profile drawn from the class itself. */
@Composable
fun ClassArt(workout: Workout, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val p = paletteFor(workout.category)
    val seed = workout.id.hashCode()
    val profile = workout.segments.map { s ->
        val v = s.powerZone?.let { it / 7f } ?: s.resistance?.let { (it.mid / 70f).coerceIn(0.1f, 1f) } ?: 0.25f
        s.durationSec to v
    }
    Box(modifier.clip(RoundedCornerShape(20.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(p.deep, p.mid), start = Offset(0f, size.height), end = Offset(size.width, 0f)))
            // light beams
            repeat(3) { i ->
                val x = size.width * (0.35f + 0.22f * i + ((seed shr (i * 3)) and 7) / 60f)
                rotate(-28f + i * 6f, pivot = Offset(x, size.height / 2)) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, p.glow.copy(alpha = 0.16f - i * 0.03f), Color.Transparent),
                            startX = x - size.width * 0.08f, endX = x + size.width * 0.08f,
                        ),
                        topLeft = Offset(x - size.width * 0.08f, -size.height),
                        size = Size(size.width * 0.16f, size.height * 3),
                    )
                }
            }
            // glow orb
            drawCircle(
                Brush.radialGradient(listOf(p.glow.copy(alpha = 0.35f), Color.Transparent), center = Offset(size.width * 0.82f, size.height * 0.2f), radius = size.minDimension * 0.7f),
                radius = size.minDimension * 0.7f, center = Offset(size.width * 0.82f, size.height * 0.2f),
            )
            // intensity profile along the bottom
            val total = profile.sumOf { it.first }.coerceAtLeast(1).toFloat()
            val baseY = size.height
            val maxH = size.height * 0.42f
            val path = Path().apply {
                moveTo(0f, baseY)
                var x = 0f
                profile.forEach { (d, v) ->
                    val w = size.width * d / total
                    lineTo(x, baseY - maxH * v)
                    lineTo(x + w, baseY - maxH * v)
                    x += w
                }
                lineTo(size.width, baseY)
                close()
            }
            drawPath(path, Brush.verticalGradient(listOf(p.glow.copy(alpha = 0.45f), p.glow.copy(alpha = 0.05f)), startY = baseY - maxH, endY = baseY))
            // legibility scrim
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.55f)), startY = size.height * 0.35f, endY = size.height))
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Target gauge: 240° dial with the target band highlighted and a live marker.
// ---------------------------------------------------------------------------------------------

@Composable
fun TargetGauge(
    label: String,
    value: Float,
    unit: String,
    color: Color,
    min: Float,
    max: Float,
    target: Range?,
    status: TargetStatus,
    modifier: Modifier = Modifier,
) {
    val sweep = 240f
    val start = 150f
    fun angleOf(v: Float) = start + sweep * ((v - min) / (max - min)).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = size.minDimension * 0.075f
            val d = size.minDimension - stroke
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            val arcSize = Size(d, d)
            drawArc(BikeColors.SurfaceHigher, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (target != null) {
                val a0 = angleOf(target.min.toFloat())
                val a1 = angleOf(target.max.toFloat())
                drawArc(BikeColors.Good.copy(alpha = 0.35f), a0, (a1 - a0).coerceAtLeast(2f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.5f), color)), start, angleOf(value) - start, false, topLeft, arcSize,
                style = Stroke(stroke * 0.55f, cap = StrokeCap.Round),
            )
            // marker
            val a = angleOf(value) * PI.toFloat() / 180f
            val r = d / 2
            val c = Offset(size.width / 2 + r * cos(a), size.height / 2 + r * sin(a))
            drawCircle(Color.White, stroke * 0.62f, c)
            drawCircle(color, stroke * 0.38f, c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
            Text(value.toInt().toString(), fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 72.sp, color = BikeColors.Text, lineHeight = 72.sp)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = BikeColors.TextDim)
            if (target != null) {
                Spacer(Modifier.height(6.dp))
                TargetChip("$target", status)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Metric bar: the full-width row of big numbers along the bottom of the ride screen.
// ---------------------------------------------------------------------------------------------

data class MetricCell(val label: String, val value: String, val unit: String, val color: Color = BikeColors.Text, val sub: String? = null)

@Composable
fun MetricBar(cells: List<MetricCell>, modifier: Modifier = Modifier, valueSize: TextUnit = 54.sp) {
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(BikeColors.Surface).border(1.dp, BikeColors.Outline, RoundedCornerShape(24.dp))
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.width(1.dp).height(56.dp).background(BikeColors.Outline))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(c.value, fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = valueSize, color = c.color, maxLines = 1, lineHeight = valueSize)
                    if (c.unit.isNotEmpty()) {
                        Spacer(Modifier.width(3.dp))
                        Text(c.unit, style = MaterialTheme.typography.labelMedium, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                c.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BikeColors.TextFaint, maxLines = 1) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Zone bar: seven power zones with the current one lit.
// ---------------------------------------------------------------------------------------------

@Composable
fun ZoneBar(zone: Int, modifier: Modifier = Modifier, target: Int? = null) {
    Row(modifier.height(30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (z in 1..7) {
            val on = z == zone
            val c = BikeColors.zoneColors[z - 1]
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .background(if (on) c else c.copy(alpha = 0.18f))
                    .then(if (target == z) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("Z$z", color = if (on) Color.Black else c, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons & chrome
// ---------------------------------------------------------------------------------------------

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, height: Dp = 56.dp, onClick: () -> Unit) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(50)).background(BikeColors.primaryGradient).clickable(onClick = onClick).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, height: Dp = 56.dp, tint: Color = BikeColors.Text, onClick: () -> Unit) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, color = tint, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun IconCircleButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, size: Dp = 48.dp, tint: Color = BikeColors.Text, background: Color = Color.White.copy(alpha = 0.08f), onClick: () -> Unit) {
    Box(modifier.size(size).clip(RoundedCornerShape(50)).background(background).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}

/** Top navigation tabs, underline style. */
@Composable
fun TabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, t ->
            Column(Modifier.clickable { onSelect(i) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t, style = MaterialTheme.typography.titleMedium,
                    color = if (i == selected) BikeColors.Text else BikeColors.TextDim,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(50)).background(if (i == selected) BikeColors.Accent else Color.Transparent))
            }
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.55f), textColor: Color = Color.White) {
    Text(
        text.uppercase(), color = textColor, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun RowHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = BikeColors.Text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = BikeColors.TextDim, modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
        }
    }
}

@Composable
fun DifficultyDots(level: Int, color: Color = BikeColors.Text) {
    val tier = when { level <= 3 -> 1; level <= 6 -> 2; else -> 3 }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i -> Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (i < tier) color else color.copy(alpha = 0.25f))) }
    }
}

fun difficultyLabel(level: Int) = when { level <= 3 -> "Beginner"; level <= 6 -> "Intermediate"; else -> "Advanced" }

fun categoryLabel(c: WorkoutCategory) = when (c) {
    WorkoutCategory.INTERVALS -> "Intervals"
    WorkoutCategory.ENDURANCE -> "Endurance"
    WorkoutCategory.CLIMB -> "Climb"
    WorkoutCategory.POWER_ZONE -> "Power Zone"
    WorkoutCategory.LOW_IMPACT -> "Low Impact"
    WorkoutCategory.TEST -> "FTP Test"
    WorkoutCategory.CUSTOM -> "My Classes"
}

/** Class card: artwork with duration badge, title and meta below. */
@Composable
fun ClassCard(workout: Workout, modifier: Modifier = Modifier, artHeight: Dp = 150.dp, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        ClassArt(workout, Modifier.fillMaxWidth().height(artHeight)) {
            Badge("${workout.durationMin} min", Modifier.align(Alignment.TopStart).padding(10.dp))
            Text(
                categoryLabel(workout.category).uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(workout.title, style = MaterialTheme.typography.titleMedium, color = BikeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(difficultyLabel(workout.difficulty), style = MaterialTheme.typography.bodySmall, color = BikeColors.TextDim)
            DifficultyDots(workout.difficulty, BikeColors.TextDim)
        }
    }
}

/** Semi-circular goal meter (original design: single open arc, not a closed ring). */
@Composable
fun GoalMeter(label: String, value: Int, goal: Int, unit: String, color: Color, modifier: Modifier = Modifier) {
    val frac = if (goal > 0) (value.toFloat() / goal).coerceIn(0f, 1f) else 0f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 120.dp, height = 70.dp), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val d = size.width - stroke
                val tl = Offset(stroke / 2, stroke / 2)
                drawArc(BikeColors.SurfaceHigher, 180f, 180f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(color, 180f, 180f * frac, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(value.toString(), fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = BikeColors.Text)
        }
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim, textAlign = TextAlign.Center)
        Text("of $goal $unit", style = MaterialTheme.typography.bodySmall, color = BikeColors.TextFaint)
    }
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, padding: Dp = 20.dp, color: Color = BikeColors.Surface, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(color).border(1.dp, BikeColors.Outline, RoundedCornerShape(24.dp)).padding(padding)) { content() }
}
