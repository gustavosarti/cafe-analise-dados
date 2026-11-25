package br.com.cafeanalise.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFF2B233)
val Bg = Color(0xFF0E0C0A)
val CardBg = Color(0xFF1A1714)
val CardBorder = Color(0x26FFFFFF)
val TextMain = Color(0xFFF3EEE8)
val Muted = Color(0xFF9A938A)
val Up = Color(0xFF5BC46A)
val Down = Color(0xFFE5675F)
val Blue = Color(0xFF6CB4F0)
val Purple = Color(0xFFC08BE0)

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    secondary = Color(0xFFC98A3C),
    background = Bg,
    onBackground = TextMain,
    surface = CardBg,
    onSurface = TextMain,
    surfaceVariant = Color(0xFF26211C),
    onSurfaceVariant = Muted,
    secondaryContainer = Color(0xFF3A2E1C),
    onSecondaryContainer = Gold,
    outline = Color(0xFF4A423A),
)

@Composable
fun CafeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

fun changeColor(v: Double?): Color = when {
    v == null -> Muted
    kotlin.math.abs(v) < 0.05 -> Muted
    v > 0 -> Up
    v < 0 -> Down
    else -> Muted
}

fun biasColor(b: Int): Color = when {
    b > 0 -> Up
    b < 0 -> Down
    else -> Muted
}
