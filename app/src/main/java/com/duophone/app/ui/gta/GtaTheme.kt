package com.duophone.app.ui.gta

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val GtaMidnight = Color(0xFF090714)
val GtaDeepHorizon = Color(0xFF140D28)
val GtaPanel = Color(0xFF151029)
val GtaPanelElevated = Color(0xFF21193D)
val GtaBorder = Color(0xFF352861)
val GtaNeonPink = Color(0xFFFF4D9D)
val GtaSunsetOrange = Color(0xFFFF8A47)
val GtaMiamiCyan = Color(0xFF2CE6F5)
val GtaPalmGreen = Color(0xFF33E89C)
val GtaAlertRed = Color(0xFFFF4B5C)
val GtaGold = Color(0xFFFFD166)
val GtaTextPrimary = Color(0xFFF9F7FF)
val GtaTextMuted = Color(0xFFA79EC7)

/**
 * Grounded Rockstar GTA 6 handheld console palette supporting both nocturnal dark
 * asphalt charcoal and daylight smartphone frost surfaces.
 */
@Immutable
data class GtaDeckPalette(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHighlight: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentPrimary: Color,
    val accentOnPrimary: Color,
    val accentTeal: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val accentRed: Color,
    val accentAmber: Color,
    val bubbleOutgoing: Color,
    val bubbleOutgoingText: Color,
)

val GtaDarkDeckPalette = GtaDeckPalette(
    isDark = true,
    canvas = Color(0xFF0C0E12),
    surface = Color(0xFF14171D),
    surfaceElevated = Color(0xFF1C2029),
    surfaceHighlight = Color(0xFF252B36),
    border = Color(0xFF2A313E),
    borderStrong = Color(0xFF3F495C),
    textPrimary = Color(0xFFF2F4F8),
    textSecondary = Color(0xFF94A0B4),
    accentPrimary = Color(0xFFF2F4F8),
    accentOnPrimary = Color(0xFF0E1116),
    accentTeal = Color(0xFF32C4A8),
    accentBlue = Color(0xFF4F8FE8),
    accentGreen = Color(0xFF2EB85C),
    accentRed = Color(0xFFE5484D),
    accentAmber = Color(0xFFE5A93C),
    bubbleOutgoing = Color(0xFF2C518E),
    bubbleOutgoingText = Color(0xFFF2F6FC),
)

val GtaLightDeckPalette = GtaDeckPalette(
    isDark = false,
    canvas = Color(0xFFE3E7EE),
    surface = Color(0xFFEEF1F6),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceHighlight = Color(0xFFDCE3EE),
    border = Color(0xFFCCD4E0),
    borderStrong = Color(0xFFAAB6C8),
    textPrimary = Color(0xFF14181F),
    textSecondary = Color(0xFF5E6A7D),
    accentPrimary = Color(0xFF14181F),
    accentOnPrimary = Color(0xFFFFFFFF),
    accentTeal = Color(0xFF16987E),
    accentBlue = Color(0xFF2F74D0),
    accentGreen = Color(0xFF25A252),
    accentRed = Color(0xFFD9383E),
    accentAmber = Color(0xFFC67E16),
    bubbleOutgoing = Color(0xFFB8D2F8),
    bubbleOutgoingText = Color(0xFF111827),
)

val LocalGtaDeckPalette = staticCompositionLocalOf { GtaDarkDeckPalette }

private val GtaDarkColorScheme = darkColorScheme(
    primary = GtaDarkDeckPalette.accentBlue,
    onPrimary = Color.White,
    secondary = GtaDarkDeckPalette.accentTeal,
    onSecondary = Color(0xFF061B16),
    tertiary = GtaDarkDeckPalette.accentGreen,
    background = GtaDarkDeckPalette.canvas,
    surface = GtaDarkDeckPalette.surface,
    surfaceVariant = GtaDarkDeckPalette.surfaceElevated,
    onBackground = GtaDarkDeckPalette.textPrimary,
    onSurface = GtaDarkDeckPalette.textPrimary,
    onSurfaceVariant = GtaDarkDeckPalette.textSecondary,
    outline = GtaDarkDeckPalette.borderStrong,
    error = GtaDarkDeckPalette.accentRed,
)

private val GtaLightColorScheme = lightColorScheme(
    primary = GtaLightDeckPalette.accentBlue,
    onPrimary = Color.White,
    secondary = GtaLightDeckPalette.accentTeal,
    onSecondary = Color.White,
    tertiary = GtaLightDeckPalette.accentGreen,
    background = GtaLightDeckPalette.canvas,
    surface = GtaLightDeckPalette.surface,
    surfaceVariant = GtaLightDeckPalette.surfaceElevated,
    onBackground = GtaLightDeckPalette.textPrimary,
    onSurface = GtaLightDeckPalette.textPrimary,
    onSurfaceVariant = GtaLightDeckPalette.textSecondary,
    outline = GtaLightDeckPalette.borderStrong,
    error = GtaLightDeckPalette.accentRed,
)

@Composable
fun GtaPhoneTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) GtaDarkDeckPalette else GtaLightDeckPalette
    val colorScheme = if (darkTheme) GtaDarkColorScheme else GtaLightColorScheme
    CompositionLocalProvider(LocalGtaDeckPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
