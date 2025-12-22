package org.balch.songe.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun SongeVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier
) {
    // No-op on Android
}
