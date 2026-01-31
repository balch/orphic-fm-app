package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun OrpheusVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
)
