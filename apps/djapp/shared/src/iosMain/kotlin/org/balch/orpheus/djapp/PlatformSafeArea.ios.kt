package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/**
 * iOS maps UIKit's `view.safeAreaInsets` onto [WindowInsets.safeDrawing]. On a Dynamic Island /
 * notched device the top inset stays non-zero even with the status bar hidden, which is exactly
 * what the header needs to clear the Island.
 */
@Composable
actual fun platformSafeAreaInsets(): WindowInsets = WindowInsets.safeDrawing
