package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable

/** Desktop is never television hardware, however wide or fullscreen the window gets. */
@Composable
actual fun isTelevisionHardware(): Boolean = false
