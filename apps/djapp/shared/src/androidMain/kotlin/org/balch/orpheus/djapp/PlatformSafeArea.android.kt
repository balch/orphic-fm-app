package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * Android hides the system bars in MainActivity and draws handheld chrome edge-to-edge into the
 * display cutout on purpose, so phones and tablets get zero inset here (side notches are handled
 * separately via [WindowInsets.displayCutout]). A physical television overscan-crops the outer
 * band of the picture instead of merely occluding it, so there this reserves [OverscanFraction]
 * of each edge — the same margin [DjPanelDock] already keeps clear of docked panels. The margin
 * is computed from real display pixels rather than dp, so it stays exactly 5% of the physical
 * screen whatever density is active in the tree.
 */
@Composable
actual fun platformSafeAreaInsets(): WindowInsets {
    val context = LocalContext.current
    if (!context.isTelevision()) return WindowInsets(0, 0, 0, 0)
    val metrics = context.resources.displayMetrics
    val insetX = (metrics.widthPixels * OverscanFraction).roundToInt()
    val insetY = (metrics.heightPixels * OverscanFraction).roundToInt()
    return WindowInsets(left = insetX, top = insetY, right = insetX, bottom = insetY)
}
