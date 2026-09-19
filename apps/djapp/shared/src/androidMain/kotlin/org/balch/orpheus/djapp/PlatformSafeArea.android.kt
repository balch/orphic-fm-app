package org.balch.orpheus.djapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * Zero on a phone or tablet in fullscreen (MainActivity hides the system bars). Multi-window, such
 * as Samsung's flex panel, brings the bars back and this returns them; only chrome that pads by
 * them stays clear. A television reserves [OverscanFraction] of each edge, in real pixels.
 */
@Composable
actual fun platformSafeAreaInsets(): WindowInsets {
    val context = LocalContext.current
    if (context.isTelevision()) {
        val metrics = context.resources.displayMetrics
        val insetX = (metrics.widthPixels * OverscanFraction).roundToInt()
        val insetY = (metrics.heightPixels * OverscanFraction).roundToInt()
        return WindowInsets(left = insetX, top = insetY, right = insetX, bottom = insetY)
    }
    // The configuration changes whenever the window does, so this re-reads on entering flex mode.
    val configuration = LocalConfiguration.current
    val inMultiWindow = remember(context, configuration) {
        context.findActivity()?.isInMultiWindowMode == true
    }
    return if (inMultiWindow) WindowInsets.systemBars else WindowInsets(0, 0, 0, 0)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
