package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable

/** iOS has no television hardware target. */
@Composable
actual fun isTelevisionHardware(): Boolean = false
