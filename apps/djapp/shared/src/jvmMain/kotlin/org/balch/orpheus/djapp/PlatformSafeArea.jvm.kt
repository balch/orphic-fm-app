package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/** Desktop has no notch or home indicator, so the chrome needs no safe-area inset. */
@Composable
actual fun platformSafeAreaInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)
