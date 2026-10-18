package org.balch.orpheus.djapp

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the DJ app's Pulsar grid selects a track on tap and opens its detail strip (mute toggle
 * and engine pickers). Off unless built with `-PtrackEdit=true`; tests provide it either way.
 */
val LocalShowTrackEdit = staticCompositionLocalOf { DjBuildKonfig.SHOW_TRACK_EDIT }
