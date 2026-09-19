package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/**
 * Widens the dp canvas to the dock's design width the way the Android host does: the window
 * content is measured in pixels, its native dp derived, and the density shrunk by the shared
 * [largeScreenDensityScale] while the window is landscape and at least 896dp wide (see
 * [DesktopMinimumDensityScale]). Without it a 900dp window gets the dock at a canvas its
 * fixed-width panels cannot fit.
 */
@Composable
fun DesktopCanvasScale(content: @Composable () -> Unit) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val native = LocalDensity.current
    Box(Modifier.fillMaxSize().onSizeChanged { measured = it }) {
        // The first frame reports no size and renders at native density; the next one scales.
        val widthDp = measured.width / native.density
        val heightDp = measured.height / native.density
        val scale = if (measured == IntSize.Zero) {
            1f
        } else {
            largeScreenDensityScale(
                widthDp, heightDp, min(widthDp, heightDp).toInt(), isTelevision = false,
                minScale = DesktopMinimumDensityScale,
            )
        }
        CompositionLocalProvider(LocalDensity provides Density(native.density * scale, native.fontScale)) {
            content()
        }
    }
}
