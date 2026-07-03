package org.balch.orpheus.djapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.OrpheusSlideUpSheet

/**
 * Presents [content] as an overlay whose shape adapts to orientation:
 * - Portrait: a tall modal bottom sheet ([OrpheusSlideUpSheet]).
 * - Landscape: a right-edge side sheet sliding in over a tap-to-dismiss scrim,
 *   leaving the nav rail and the panel behind it visible.
 *
 * The same [content] is used in both — only the container differs.
 */
@Composable
fun AdaptiveAiSheet(
    isLandscape: Boolean,
    portraitPeekHeight: Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isLandscape) {
        AiSideSheet(onDismiss = onDismiss, content = content)
    } else {
        OrpheusSlideUpSheet(
            onDismiss = onDismiss,
            inactivityTimeoutMs = null,
            skipPartiallyExpanded = true,
        ) {
            // ColumnScope receiver + kick lambda (unused — the AI panel manages its own state).
            // Fixed content height (≈2/3 screen) so the sheet opens at that footprint rather than
            // full-height, matching the VibeInfo sheet's smaller feel; drag-down still dismisses.
            Box(Modifier.fillMaxWidth().height(portraitPeekHeight)) {
                content()
            }
        }
    }
}

@Composable
private fun AiSideSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Starts hidden, flips visible on first composition so the panel animates in each open.
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visible.targetState = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Width = min(60% of the window, 400dp): generous on phones, capped so the panel
        // doesn't sprawl on tablets/foldables. (Computed here because chaining
        // fillMaxWidth(fraction) + widthIn does not honor the cap — fillMaxWidth wins.)
        val panelWidth = minOf(maxWidth * 0.6f, 400.dp)
        AnimatedVisibility(visibleState = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() },
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                color = OrpheusColors.deepPurple,
                contentColor = OrpheusColors.onSurfaceDark,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Right))
                    // Consume taps so they don't fall through to the scrim and dismiss.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                content()
            }
        }
    }
}
