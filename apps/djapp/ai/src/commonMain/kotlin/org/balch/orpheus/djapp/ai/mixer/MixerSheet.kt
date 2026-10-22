package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** The panel's corners inside the sheet. */
internal val MixerSheetShape = RoundedCornerShape(20.dp)

/**
 * A full-stage sheet for the mixer: a scrim and the panel inset 8dp, which draws its own opaque [MixerSheetShape]
 * surface so it can stop short of the bar's chrome. Not AdaptiveSheet, whose landscape side sheet caps at 400dp.
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
