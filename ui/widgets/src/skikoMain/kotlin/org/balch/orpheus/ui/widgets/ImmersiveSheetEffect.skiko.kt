package org.balch.orpheus.ui.widgets

import androidx.compose.runtime.Composable

/** No-op: only Android's ModalBottomSheet opens a separate window that loses immersion. */
@Composable
actual fun ImmersiveSheetEffect() { /* no-op */ }
