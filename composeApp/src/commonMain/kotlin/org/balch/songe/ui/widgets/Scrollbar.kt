package org.balch.songe.ui.widgets

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun SongeVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
)
