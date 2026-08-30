package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Delegates to Context.isTelevision() (see TvMode.android.kt) — real UiModeManager/leanback detection. */
@Composable
actual fun isTelevisionHardware(): Boolean = LocalContext.current.isTelevision()
