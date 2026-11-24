package com.duophone.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DuoBg = Color(0xFF0B1020)
val DuoSurface = Color(0xFF131C31)
val DuoSurfaceAlt = Color(0xFF1B2742)
val DuoAccent = Color(0xFF38BDF8)
val DuoCyan = Color(0xFF22D3EE)
val DuoGreen = Color(0xFF34D399)
val DuoAmber = Color(0xFFFBBF24)
val DuoRed = Color(0xFFF87171)
val DuoTextPrimary = Color(0xFFF1F5F9)
val DuoTextSecondary = Color(0xFF94A3B8)

private val DuoColorScheme = darkColorScheme(
    primary = DuoAccent,
    onPrimary = Color(0xFF04121F),
    secondary = DuoCyan,
    background = DuoBg,
    surface = DuoSurface,
    surfaceVariant = DuoSurfaceAlt,
    onBackground = DuoTextPrimary,
    onSurface = DuoTextPrimary,
    onSurfaceVariant = DuoTextSecondary,
    error = DuoRed,
)

@Composable
fun DuoPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DuoColorScheme,
        content = content,
    )
}
