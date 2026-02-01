package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors

@Composable
actual fun OrpheusVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(state),
        style = defaultScrollbarStyle().copy(
            unhoverColor = OrpheusColors.neonCyan.copy(alpha = 0.3f),
            hoverColor = OrpheusColors.neonCyan.copy(alpha = 0.6f),
            shape = RoundedCornerShape(2.dp)
        )
    )
}
