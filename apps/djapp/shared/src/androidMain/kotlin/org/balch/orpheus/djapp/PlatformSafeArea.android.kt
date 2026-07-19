package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * Android hides the system bars in MainActivity and draws the chrome edge-to-edge into the
 * display cutout on purpose, so no extra safe-area inset is applied here. Side notches are handled
 * where needed via [WindowInsets.displayCutout].
 */
@Composable
actual fun platformSafeAreaInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)
