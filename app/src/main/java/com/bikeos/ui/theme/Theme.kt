package com.bikeos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bikeos.R
import com.bikeos.core.workout.TargetStatus

/** Inter for interface text, Barlow Condensed for athletic numerals and display type (both SIL OFL). */
object BikeFonts {
    val Body = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
        Font(R.font.inter_extrabold, FontWeight.ExtraBold),
        Font(R.font.inter_extrabold, FontWeight.Black),
    )
    val Display = FontFamily(
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
        Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
        Font(R.font.barlow_condensed_extrabold, FontWeight.Black),
    )
}

object BikeColors {
    // Surfaces: near-black with a faint cool tint, layered in steps.
    val Background = Color(0xFF09090C)
    val Surface = Color(0xFF131318)
    val SurfaceHigh = Color(0xFF1C1C23)
    val SurfaceHigher = Color(0xFF26262F)
    val Outline = Color(0xFF2B2B35)
    val Text = Color(0xFFF7F7FA)
    val TextDim = Color(0xFF9B9BAA)
    val TextFaint = Color(0xFF63636F)

    // Brand: a warm ember primary and a cool ice secondary.
    val Accent = Color(0xFFFF4F3A)
    val Accent2 = Color(0xFFFFA23A)
    val Ice = Color(0xFF41C9FF)

    // Metric colours, consistent everywhere (HUD, gauges, charts, dashboard).
    val Power = Color(0xFFFFC53D)
    val Cadence = Color(0xFF41C9FF)
    val Resistance = Color(0xFFFF4F8B)
    val Output = Color(0xFFFF7A3A)
    val Heart = Color(0xFFFF4D5E)

    val Good = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFB020)
    val Bad = Color(0xFFFF4D5E)

    val zoneColors = listOf(
        Color(0xFF7C8496), Color(0xFF41C9FF), Color(0xFF3DDC97), Color(0xFFFFD84D),
        Color(0xFFFF9F43), Color(0xFFFF5C5C), Color(0xFFB66BFF),
    )

    val primaryGradient = Brush.horizontalGradient(listOf(Color(0xFFFF4F3A), Color(0xFFFF8A3A)))

    fun status(s: TargetStatus): Color = when (s) {
        TargetStatus.IN_RANGE -> Good
        TargetStatus.BELOW, TargetStatus.ABOVE -> Warn
        TargetStatus.NONE -> TextDim
    }
}

private val scheme = darkColorScheme(
    primary = BikeColors.Accent,
    onPrimary = Color.White,
    secondary = BikeColors.Ice,
    onSecondary = Color.Black,
    background = BikeColors.Background,
    onBackground = BikeColors.Text,
    surface = BikeColors.Surface,
    onSurface = BikeColors.Text,
    surfaceVariant = BikeColors.SurfaceHigh,
    onSurfaceVariant = BikeColors.TextDim,
    surfaceContainerHigh = BikeColors.SurfaceHigh,
    surfaceContainerHighest = BikeColors.SurfaceHigher,
    outline = BikeColors.Outline,
    outlineVariant = BikeColors.Outline,
    error = BikeColors.Bad,
)

private fun body(size: Int, weight: FontWeight, spacing: Float = 0f) =
    TextStyle(fontFamily = BikeFonts.Body, fontSize = size.sp, fontWeight = weight, letterSpacing = spacing.sp)

private fun display(size: Int, weight: FontWeight = FontWeight.ExtraBold, spacing: Float = 0f) =
    TextStyle(fontFamily = BikeFonts.Display, fontSize = size.sp, fontWeight = weight, letterSpacing = spacing.sp)

private val typography = Typography(
    displayLarge = display(88, spacing = -1f),
    displayMedium = display(56),
    displaySmall = display(40),
    headlineLarge = body(34, FontWeight.ExtraBold, -0.5f),
    headlineMedium = body(28, FontWeight.Bold, -0.3f),
    headlineSmall = body(22, FontWeight.Bold, -0.2f),
    titleLarge = body(20, FontWeight.Bold, -0.1f),
    titleMedium = body(16, FontWeight.SemiBold),
    titleSmall = body(14, FontWeight.SemiBold),
    bodyLarge = body(16, FontWeight.Normal),
    bodyMedium = body(14, FontWeight.Normal),
    bodySmall = body(12, FontWeight.Normal),
    labelLarge = body(14, FontWeight.SemiBold, 0.2f),
    labelMedium = body(12, FontWeight.SemiBold, 0.6f),
    labelSmall = body(11, FontWeight.Bold, 1.2f),
)

@Composable
fun BikeOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
