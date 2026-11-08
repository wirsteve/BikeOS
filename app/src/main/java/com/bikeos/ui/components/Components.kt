package com.bikeos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bikeos.core.workout.TargetStatus
import com.bikeos.ui.theme.BikeColors

@Composable
fun Card(
    modifier: Modifier = Modifier,
    color: Color = BikeColors.Surface,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .border(1.dp, BikeColors.Outline, RoundedCornerShape(24.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
    ) { content() }
}

@Composable
fun Metric(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    valueSize: TextUnit = 44.sp,
    target: String? = null,
    status: TargetStatus = TargetStatus.NONE,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = valueSize, fontFamily = com.bikeos.ui.theme.BikeFonts.Display, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1, lineHeight = valueSize)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (target != null) TargetChip(target, status)
    }
}

@Composable
fun TargetChip(text: String, status: TargetStatus, compact: Boolean = false) {
    val c = BikeColors.status(status)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.16f))
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (status) {
            TargetStatus.BELOW -> Icons.Default.ArrowUpward
            TargetStatus.ABOVE -> Icons.Default.ArrowDownward
            TargetStatus.IN_RANGE -> Icons.Default.Check
            TargetStatus.NONE -> null
        }
        if (icon != null) Icon(icon, null, tint = c, modifier = Modifier.size(if (compact) 12.dp else 14.dp))
        Text(text, color = c, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(BikeColors.Outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Simple area line chart; used for live power and ride history. */
@Composable
fun LineChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float? = null,
    bands: List<Pair<ClosedFloatingPointRange<Float>, Color>> = emptyList(),
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maxV = (maxValue ?: values.max()).coerceAtLeast(1f) * 1.08f
        bands.forEach { (range, c) ->
            val top = size.height * (1 - range.endInclusive / maxV).coerceIn(0f, 1f)
            val bottom = size.height * (1 - range.start / maxV).coerceIn(0f, 1f)
            drawRect(c, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, bottom - top))
        }
        val stepX = size.width / (values.size - 1)
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height * (1 - v / maxV)
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, color.copy(alpha = 0.18f))
        drawPath(path, color, style = Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = BikeColors.TextDim, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(BikeColors.SurfaceHigh)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = BikeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
