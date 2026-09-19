package org.balch.orpheus.djapp

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * True on a physical television (Chromecast with Google TV, Android TV boxes, etc.), never on a
 * phone or tablet. UiModeManager's mode type is the canonical runtime signal; the leanback
 * feature flag is an acceptable secondary. Verified on an attached Chromecast with Google TV via
 * `adb shell dumpsys uimode` (mCurUiMode reports UI_MODE_TYPE_TELEVISION) and
 * `adb shell pm list features` (android.software.leanback present) — both agree there, so either
 * alone would suffice, but OR-ing them costs nothing.
 */
fun Context.isTelevision(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isUiModeTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return isUiModeTv || hasLeanback
}

/**
 * Density multiplier that widens the dp canvas so the dock's fixed-width panels fit. Callers
 * fold this into [androidx.compose.ui.unit.Density.density] and never into fontScale, which
 * would compound the user's own accessibility text-size setting.
 *
 * All policy lives in the shared [largeScreenDensityScale]; this only reads the platform
 * configuration and hands it over. [LocalConfiguration] reports the SYSTEM configuration, which
 * a `CompositionLocalProvider(LocalDensity ...)` above does not alter, so feeding the result
 * back into the density creates no loop.
 *
 * Raising [LargeScreenDesignWidthDp] makes the UI physically smaller and fits more; lowering it
 * does the reverse.
 *
 * A tabletop [hinge] keeps native density (see [largeScreenDensityScale]).
 */
@Composable
fun largeScreenCanvasScale(hinge: Hinge?): Float {
    val isTv = LocalContext.current.isTelevision()
    val configuration = LocalConfiguration.current
    return largeScreenDensityScale(
        widthDp = configuration.screenWidthDp.toFloat(),
        heightDp = configuration.screenHeightDp.toFloat(),
        smallestWidthDp = configuration.smallestScreenWidthDp,
        isTelevision = isTv,
        tabletop = hinge != null,
    )
}
