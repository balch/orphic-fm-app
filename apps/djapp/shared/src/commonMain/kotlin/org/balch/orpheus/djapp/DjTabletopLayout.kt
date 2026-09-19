package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity

/**
 * Splits the stage at [hinge]: [top] fills the upright half, [bottom] the flat half, with a gap
 * the height of the fold between. The hinge is in window pixels, so this measures its own offset.
 */
@Composable
internal fun DjTabletopLayout(
    hinge: Hinge,
    top: @Composable (Modifier) -> Unit,
    bottom: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var windowTopPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val topHeight = with(density) { (hinge.topPx - windowTopPx).coerceAtLeast(0f).toDp() }
    val foldHeight = with(density) { (hinge.bottomPx - hinge.topPx).coerceAtLeast(0).toDp() }
    Column(modifier.fillMaxSize().onGloballyPositioned { windowTopPx = it.positionInWindow().y }) {
        top(Modifier.fillMaxWidth().height(topHeight))
        Spacer(Modifier.height(foldHeight))
        bottom(Modifier.fillMaxWidth().weight(1f))
    }
}
