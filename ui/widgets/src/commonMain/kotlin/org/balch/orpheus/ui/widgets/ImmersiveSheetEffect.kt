package org.balch.orpheus.ui.widgets

import androidx.compose.runtime.Composable

/**
 * Keeps an immersive host immersive while a slide-up sheet is open.
 *
 * A [androidx.compose.material3.ModalBottomSheet] renders in its own window, which does
 * NOT inherit the host activity's system-bar (immersive) flags — so on Android the
 * status/navigation bars reappear the moment the sheet opens. Call this inside the sheet
 * content to re-hide the system bars on the sheet's own window. No-op on non-Android targets.
 */
@Composable
expect fun ImmersiveSheetEffect()
