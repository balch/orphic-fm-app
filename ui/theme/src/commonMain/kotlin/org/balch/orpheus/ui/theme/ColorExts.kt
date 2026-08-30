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

/**
 * Minimum relative luminance for text drawn over this app's dark chrome.
 *
 * Tuned against the darkest visualization title colors (Swirly's purple, Galaxy's rim blue,
 * Aquarium's teal), which are chosen to sit in their own scene and are too dark to read as
 * type over it.
 *
 * High because the chrome this type sits on is usually tinted from the *same* accent — the top
 * bar's idle plate and the bottom bar's docked wash both are — so merely clearing "dark" left
 * accent sitting on accent and still reading badly. It has to clear the plate, not the page.
 * Above ~0.7 the hues wash out to pastel and stop identifying the visualization at all.
 */
const val ReadableTextMinLuminance = 0.62f

/**
 * Lifts a color until it is bright enough to read as text on dark chrome, keeping its hue.
 *
 * A visualization picks its title color to belong to its own scene, which is a different job
 * from being legible type on top of that scene — several of them land dark enough that the
 * title greys out. Lightening toward white preserves the hue that ties the chrome to the
 * visualization while restoring contrast; a color already above the floor is returned
 * untouched, so the bright visualizations look exactly as they did.
 */
fun Color.readableOnDark(minLuminance: Float = ReadableTextMinLuminance): Color {
    // Rec. 709 luma; cheap and good enough to rank "is this dark", which is all this needs.
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    if (luminance >= minLuminance) return this
    // Solve for the lighten fraction that reaches the floor: lightening moves every channel
    // toward 1 by the same fraction, so luma moves the same way.
    val fraction = ((minLuminance - luminance) / (1f - luminance)).coerceIn(0f, 1f)
    return lighten(fraction)
}
