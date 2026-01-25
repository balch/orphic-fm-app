package org.balch.orpheus.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Lightens the color by the given fraction (0.0 to 1.0).
 */
fun Color.lighten(fraction: Float = 0.2f): Color {
    val red = (red + (1f - red) * fraction).coerceIn(0f, 1f)
    val green = (green + (1f - green) * fraction).coerceIn(0f, 1f)
    val blue = (blue + (1f - blue) * fraction).coerceIn(0f, 1f)
    return Color(red, green, blue, alpha)
}

/**
 * Darkens the color by the given fraction (0.0 to 1.0).
 */
fun Color.darken(fraction: Float = 0.2f): Color {
    val red = (red * (1f - fraction)).coerceIn(0f, 1f)
    val green = (green * (1f - fraction)).coerceIn(0f, 1f)
    val blue = (blue * (1f - fraction)).coerceIn(0f, 1f)
    return Color(red, green, blue, alpha)
}

/**
 * Blends this color (as source) over the background color.
 * Result is opaque if background is opaque.
 */
fun Color.compositeOver(background: Color): Color {
    val a = alpha
    val r = red * a + background.red * (1f - a)
    val g = green * a + background.green * (1f - a)
    val b = blue * a + background.blue * (1f - a)
    return Color(r, g, b, 1f)
}
