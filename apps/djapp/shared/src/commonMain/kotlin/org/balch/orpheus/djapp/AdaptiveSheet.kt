package org.balch.orpheus.djapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
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
fun AdaptiveSheet(
    isLandscape: Boolean,
    portraitPeekHeight: Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isLandscape) {
        SideSheet(onDismiss = onDismiss, content = content)
    } else {
        OrpheusSlideUpSheet(
            onDismiss = onDismiss,
            inactivityTimeoutMs = null,
            skipPartiallyExpanded = true,
        ) {
            // ColumnScope receiver + kick lambda (unused — the content manages its own state).
            // Fixed content height (≈2/3 screen) so the sheet opens at that footprint rather than
            // full-height, matching the VibeInfo sheet's smaller feel; drag-down still dismisses.
            Box(Modifier.fillMaxWidth().height(portraitPeekHeight)) {
                content()
            }
        }
    }
}

/** The stage's own sheet, shown while [open]: [SideSheet] with the rail and the dock, else [StagePanel]. */
@Composable
internal fun StageSheet(isLandscape: Boolean, open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (isLandscape) SideSheet(onDismiss, open, content) else StagePanel(onDismiss, open, content)
}

/**
 * A sheet's enter and exit: in on first composition while [open], out when [open] turns false. Null
 * once that exit has run, so a caller keeping the sheet composed while closed draws nothing.
 */
@Composable
private fun rememberSheetVisibility(open: Boolean): MutableTransitionState<Boolean>? {
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(open) { visible.targetState = open }
    return visible.takeUnless { !open && it.isIdle && !it.currentState }
}

/**
 * The landscape sheet: a right-edge panel over a scrim, both inside the stage. A tap on the scrim or
 * Back (Esc on desktop) dismisses it.
 */
@Composable
internal fun SideSheet(
    onDismiss: () -> Unit,
    open: Boolean = true,
    content: @Composable () -> Unit,
) {
    val visible = rememberSheetVisibility(open) ?: return
    // Composed after the stage's NavDisplay, so this handler is the one Back reaches while the sheet is up.
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = open, onBackCompleted = onDismiss)

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
                    // Inert while it fades out: the sheet is already closing.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
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

/** How much of the stage the portrait panel covers: the header, the chips and several rows at 360x780. */
private const val StagePanelHeightFraction = 0.85f

private val StagePanelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * The portrait counterpart of [SideSheet]: a panel sliding up inside the stage, so the phone bar,
 * drawn after the stage, keeps its raised ring over the panel's bottom edge. A tap on
 * the dimmed stage or Back (Esc on desktop) dismisses it.
 */
@Composable
internal fun StagePanel(onDismiss: () -> Unit, open: Boolean = true, content: @Composable () -> Unit) {
    val visible = rememberSheetVisibility(open) ?: return
    // Composed after the stage's NavDisplay, so this handler is the one Back reaches while the panel is up.
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = open, onBackCompleted = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    // Inert while it fades out, as the side sheet's.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
                        onClickLabel = "Close",
                    ) { onDismiss() },
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // A plain Surface already keeps its taps from the scrim behind it.
            Surface(
                color = OrpheusColors.deepPurple,
                contentColor = OrpheusColors.onSurfaceDark,
                shape = StagePanelShape,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(StagePanelHeightFraction),
            ) {
                Box(Modifier.padding(top = 12.dp)) { content() }
            }
        }
    }
}
