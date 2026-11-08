package com.bikeos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bikeos.core.workout.TargetStatus

object BikeColors {
    val Background = Color(0xFF07090D)
    val Surface = Color(0xFF12161D)
    val SurfaceHigh = Color(0xFF1B212B)
    val Outline = Color(0xFF2A3240)
    val Text = Color(0xFFF2F5F9)
    val TextDim = Color(0xFF8E99AB)
    val Accent = Color(0xFF2EE6A8) // mint
    val Accent2 = Color(0xFFFF7A2F) // ember
    val Power = Color(0xFFFFC233)
    val Cadence = Color(0xFF4FC3FF)
    val Resistance = Color(0xFFFF5C8A)
    val Heart = Color(0xFFFF4D4D)
    val Good = Color(0xFF2EE6A8)
    val Warn = Color(0xFFFFB020)
    val Bad = Color(0xFFFF4D6A)

    val zoneColors = listOf(
        Color(0xFF8E99AB), Color(0xFF4FC3FF), Color(0xFF2EE6A8), Color(0xFFFFD84D),
        Color(0xFFFF9F43), Color(0xFFFF5C5C), Color(0xFFC86BFF),
    )

    fun status(s: TargetStatus): Color = when (s) {
        TargetStatus.IN_RANGE -> Good
        TargetStatus.BELOW, TargetStatus.ABOVE -> Warn
        TargetStatus.NONE -> TextDim
    }
}

private val scheme = darkColorScheme(
    primary = BikeColors.Accent,
    onPrimary = Color.Black,
    secondary = BikeColors.Accent2,
    background = BikeColors.Background,
    onBackground = BikeColors.Text,
    surface = BikeColors.Surface,
    onSurface = BikeColors.Text,
    surfaceVariant = BikeColors.SurfaceHigh,
    onSurfaceVariant = BikeColors.TextDim,
    outline = BikeColors.Outline,
    error = BikeColors.Bad,
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
)

@Composable
fun BikeOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
