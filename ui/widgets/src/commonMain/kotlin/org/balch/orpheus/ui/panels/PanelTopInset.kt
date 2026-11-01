package org.balch.orpheus.ui.panels

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far the host's own chrome (the phone's prev/next header, a slot's pin and close buttons)
 * reaches down over the top of a panel's body. A panel with controls along its top edge starts
 * them below this.
 */
val LocalPanelTopInset = staticCompositionLocalOf<Dp> { 0.dp }
