package org.balch.orpheus.ui.widgets

import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Re-hides the system bars on the ModalBottomSheet's own dialog window so an immersive
 * host (see `MainActivity`, which hides system bars) stays immersive while the sheet is open.
 * Restores nothing on dispose — the sheet's window is torn down on close, leaving the host
 * activity's immersion untouched.
 */
@Composable
actual fun ImmersiveSheetEffect() {
    val view = LocalView.current
    // SideEffect (not LaunchedEffect) so the bars are hidden while the FIRST composition is
    // applied — before the frame is drawn — instead of a frame+ later on the coroutine
    // dispatcher. That removes the flash of system bars when the sheet's window first appears.
    // Idempotent, so re-running on recomposition is harmless.
    SideEffect {
        val window = view.findDialogWindow() ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Walk up the view hierarchy to the sheet's [DialogWindowProvider] and return its window. */
private fun View.findDialogWindow(): Window? {
    var p: ViewParent? = parent
    while (p != null) {
        if (p is DialogWindowProvider) return p.window
        p = (p as? View)?.parent
    }
    return null
}
