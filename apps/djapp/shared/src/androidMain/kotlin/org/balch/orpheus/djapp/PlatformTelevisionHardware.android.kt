package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Delegates to Context.isTelevision() (see TvMode.android.kt) — real UiModeManager/leanback
 * detection. Both checks are live binder round-trips (UiModeManager.getCurrentModeType() and
 * PackageManager.hasSystemFeature()), and the answer cannot change at runtime, so it's computed
 * once per Context rather than on every recomposition of every caller.
 */
@Composable
actual fun isTelevisionHardware(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isTelevision() }
}
