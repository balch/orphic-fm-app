package org.balch.songe.ui.widgets

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.balch.songe.ui.theme.SongeColors

@Composable
actual fun SongeVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(state),
        style = defaultScrollbarStyle().copy(
            unhoverColor = SongeColors.neonCyan.copy(alpha = 0.3f),
            hoverColor = SongeColors.neonCyan.copy(alpha = 0.6f),
            shape = RoundedCornerShape(2.dp)
        )
    )
}
