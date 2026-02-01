package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun OrpheusVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier
) {
    // No-op on WasmJs
}
