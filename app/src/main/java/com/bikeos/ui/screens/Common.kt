package com.bikeos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.bikeos.ui.Navigator
import com.bikeos.ui.theme.BikeColors

@Composable
fun ScreenScaffold(
    title: String,
    nav: Navigator,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(BikeColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BikeColors.Text) }
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = BikeColors.Text, modifier = Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) { content() }
    }
}

/** Big touch-friendly tile used for apps on the home screen. */
@Composable
fun AppTile(
    label: String,
    color: Color,
    icon: android.graphics.drawable.Drawable?,
    badge: String?,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val bitmap = remember(icon) { icon?.let { runCatching { it.toBitmap(160, 160).asImageBitmap() }.getOrNull() } }
    Column(
        modifier.width(size).clip(RoundedCornerShape(22.dp)).then(
            if (onLongClick != null) Modifier.combinedClickableCompat(onClick, onLongClick) else Modifier.clickable(onClick = onClick),
        ).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(size - 24.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.45f)))),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, label, modifier = Modifier.size((size - 24.dp) * 0.62f))
            } else {
                Text(
                    label.take(1), fontSize = 44.sp, fontWeight = FontWeight.Black,
                    color = if (color.luminance() > 0.6f) Color.Black else Color.White,
                )
            }
            if (badge != null) {
                Text(
                    badge, fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Black,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = BikeColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** +/- control: avoids popping a keyboard on a bike. */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus) { Icon(Icons.Default.Remove, "Less", tint = BikeColors.Text) }
        Text(value, color = BikeColors.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(90.dp), textAlign = TextAlign.Center)
        IconButton(onClick = onPlus) { Icon(Icons.Default.Add, "More", tint = BikeColors.Text) }
    }
}

@Composable
fun StatBlock(label: String, value: String, unit: String = "", color: Color = BikeColors.Text, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BikeColors.TextDim)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = color, fontSize = 34.sp, fontFamily = com.bikeos.ui.theme.BikeFonts.Display, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp)
            if (unit.isNotEmpty()) Text(unit, color = BikeColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
