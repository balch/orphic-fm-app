package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Reusable bottom-sheet host for Orpheus slide-up panels. Wraps [ModalBottomSheet]
 * with the project's deep-purple surface, [CosmicDragHandle], and an optional
 * inactivity auto-dismiss timer.
 *
 * @param onDismiss Called when the sheet should close — either by user drag or
 *   by the inactivity timer firing.
 * @param modifier Applied to the [ModalBottomSheet] container.
 * @param inactivityTimeoutMs If non-null, the sheet auto-dismisses after this
 *   many milliseconds of no user interaction. Pass `null` to disable auto-dismiss
 *   (e.g. for informational sheets with no interactive controls). Any user
 *   interaction should call the `kick` lambda exposed to [content] to reset the
 *   timer.
 * @param dragHandle Composable rendered as the sheet's drag handle. Defaults to
 *   [CosmicDragHandle].
 * @param content The sheet body. Receives a `kick` lambda — call it on every
 *   user interaction to restart the inactivity timer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrpheusSlideUpSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    inactivityTimeoutMs: Long? = null,
    skipPartiallyExpanded: Boolean = false,
    dragHandle: @Composable () -> Unit = { CosmicDragHandle() },
    content: @Composable ColumnScope.(kick: () -> Unit) -> Unit,
) {
    // M3 replaced the skipPartiallyExpanded boolean with an explicit detent set; keep the
    // boolean as this widget's contract and map it here so call sites stay M3-agnostic.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = if (skipPartiallyExpanded) {
            setOf(SheetValue.Hidden, SheetValue.Expanded)
        } else {
            setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
        },
    )
    var interactionTick by remember { mutableIntStateOf(0) }
    if (inactivityTimeoutMs != null) {
        LaunchedEffect(interactionTick, inactivityTimeoutMs) {
            delay(inactivityTimeoutMs)
            onDismiss()
        }
    }
    val kick: () -> Unit = remember { { interactionTick++ } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OrpheusColors.deepPurple,
        contentColor = OrpheusColors.onSurfaceDark,
        dragHandle = dragHandle,
        modifier = modifier,
    ) {
        // Re-hide the system bars on the sheet's own window (Android only) so an
        // immersive host doesn't flash the status/nav bars when the sheet opens.
        ImmersiveSheetEffect()
        content(kick)
    }
}

/**
 * Cosmic-purple pill shown as the drag handle on Orpheus slide-up sheets.
 * Extracted here so both [OrpheusSlideUpSheet] (default) and any custom
 * [dragHandle] override can reference the same composable.
 */
@Composable
fun CosmicDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 8.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(OrpheusColors.cosmicPurple.copy(alpha = 0.5f)),
    )
}
