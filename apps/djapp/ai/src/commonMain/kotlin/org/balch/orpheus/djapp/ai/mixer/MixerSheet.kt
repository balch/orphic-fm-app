package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.balch.orpheus.djapp.variant.LocalStageChromeOverhang

/** The panel's corners inside the sheet. */
internal val MixerSheetShape = RoundedCornerShape(20.dp)

internal const val SHEET_MIXER_TAG = "ai-sheet-mixer"

/**
 * A full-stage sheet for the mixer: a scrim and the panel inset 8dp, which draws its own opaque [MixerSheetShape]
 * surface so it can stop short of the bar's chrome. Not AdaptiveSheet, whose landscape side sheet is a narrow column.
 */
@Composable
fun MixerSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // Composed after the stage's NavDisplay, so Back and Esc reach this handler first.
    NavigationBackHandler(
        rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = onDismiss,
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close",
                ) { onDismiss() },
        )
        // The panel's Surface keeps its taps from the scrim; the margin around it reaches the scrim.
        Box(Modifier.fillMaxSize().padding(8.dp)) { content() }
    }
}

/**
 * The open sheet: the mixer, then [beside] to its right 8dp on. The bar's chrome (the dock's dome pill, the phone
 * bar's ring) reaches [LocalStageChromeOverhang] over the stage's bottom edge; the panel takes it whole, and [beside]
 * gets it to keep its own content clear.
 */
@Composable
internal fun MixerSheetContent(
    feature: AiMixerFeature,
    onDismiss: () -> Unit,
    beside: @Composable RowScope.(bottomOverhang: Dp) -> Unit = {},
) {
    val overhang = LocalStageChromeOverhang.current
    MixerSheet(onDismiss) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiMixerPanel(
                feature,
                Modifier.weight(1f).fillMaxHeight().testTag(SHEET_MIXER_TAG),
                shape = MixerSheetShape,
                bottomOverhang = overhang,
            )
            beside(overhang)
        }
    }
}
