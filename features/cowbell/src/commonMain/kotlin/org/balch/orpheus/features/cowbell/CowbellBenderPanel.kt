package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.widgets.BenderFaderWidget

/** The bell beside a spring-back fader on its own bend: no knobs, just strike and bend. */
@Composable
fun CowbellBenderPanel(
    feature: CowbellFeature,
    modifier: Modifier = Modifier,
    outLevel: StateFlow<Float> = NoLevel,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val state = feature.stateFlow.collectAsStateWithLifecycle()
    val mode by remember(state) { derivedStateOf { state.value.mode } }
    val fill by remember(state) { derivedStateOf { state.value.fill } }
    val motionOn by remember(state) { derivedStateOf { state.value.motion } }
    val actions = feature.actions
    val morphStyle by feature.morphStyle.collectAsStateWithLifecycle()
    DisposableEffect(actions) {
        onDispose { actions.releaseAll() }
    }

    CollapsibleColumnPanel(
        title = "BELL BEND",
        color = CowbellPanelColor,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
        fillHeight = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CowbellBell3D(
                mode = mode,
                actions = actions,
                bend = { state.value.bend },
                fill = fill,
                outLevel = outLevel,
                morphStyle = morphStyle,
                motionOn = motionOn,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            BellBendFader(state = state, actions = actions)
        }
    }
}

/** Orpheus's fixed fader track, in dp. */
internal const val BELL_FADER_TRACK = 120

/**
 * Reads the bend at drag rate, so only this fader recomposes as it moves. It bends pitch only:
 * the plunge in SHAKE belongs to the bell's own second finger. [showArrows] false drops the
 * direction arrows, so the widget is exactly [trackHeight] tall.
 */
@Composable
internal fun BellBendFader(
    state: State<CowbellUiState>,
    actions: CowbellPanelActions,
    modifier: Modifier = Modifier.padding(end = 6.dp),
    trackHeight: Int = BELL_FADER_TRACK,
    thumbWidth: Int = 52,
    showArrows: Boolean = true,
) {
    BenderFaderWidget(
        value = state.value.bend,
        onValueChange = actions.onBend,
        onRelease = actions.onBendDone,
        trackHeight = trackHeight,
        thumbWidth = thumbWidth,
        accentColor = CowbellPanelColor,
        modifier = modifier,
        showArrows = showArrows,
    )
}
