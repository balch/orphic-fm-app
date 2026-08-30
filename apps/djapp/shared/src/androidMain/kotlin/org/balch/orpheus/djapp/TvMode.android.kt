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
 * Design canvas width the TV chrome is authored against. Android TV reports 1080p as 960x540dp,
 * which is too tight for the dock: the panels have fixed content widths and do not reflow, so at
 * 960dp the gain label breaks to one letter per line and the right-hand knob clips. Widening the
 * canvas hands them the room instead. Verified on a Chromecast with Google TV by sweeping
 * `adb shell wm density`: 960dp clips, 1097dp still breaks GAIN, 1280dp renders everything.
 *
 * Raising this makes the UI physically smaller and fits more; lowering it does the reverse.
 */
private const val TvDesignWidthDp = 1280f

/**
 * Density multiplier that widens the dp canvas on TV hardware. Callers fold this into
 * [androidx.compose.ui.unit.Density.density] and never into fontScale, which would compound the
 * user's own accessibility text-size setting. Returns 1f — a no-op — off television hardware.
 *
 * Scaling below 1f grows the reported canvas, so the [DjLayoutMode] LargeScreen thresholds stay
 * satisfied with room to spare. A scale above 1f would shrink it toward that cliff instead.
 */
@Composable
fun tvDensityScale(): Float {
    val context = LocalContext.current
    if (!context.isTelevision()) return 1f
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return screenWidthDp / TvDesignWidthDp
}
