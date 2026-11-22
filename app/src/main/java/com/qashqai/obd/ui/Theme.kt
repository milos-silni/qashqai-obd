package com.qashqai.obd.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale

object Palette {
    val bg = Color(0xFF0B0F14)
    val card = Color(0xFF151C25)
    val cardHi = Color(0xFF1C2631)
    val text = Color(0xFFECEFF4)
    val dim = Color(0xFF8A97A8)
    val accent = Color(0xFFFF9800)
    val good = Color(0xFF4CAF50)
    val warn = Color(0xFFFFC107)
    val bad = Color(0xFFF44336)
    val cold = Color(0xFF42A5F5)
}

@Composable
fun QashqaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.Black,
            secondary = Palette.cold,
            background = Palette.bg,
            surface = Palette.card,
            surfaceVariant = Palette.cardHi,
            onBackground = Palette.text,
            onSurface = Palette.text,
            onSurfaceVariant = Palette.dim,
        ),
        content = content,
    )
}

fun Double?.fmt(decimals: Int = 0): String =
    if (this == null || isNaN()) "—" else String.format(Locale.US, "%.${decimals}f", this)

fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    return String.format(Locale.US, "%d:%02d", s / 3600, (s % 3600) / 60)
}
