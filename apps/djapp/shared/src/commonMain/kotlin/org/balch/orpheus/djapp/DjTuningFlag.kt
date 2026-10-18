package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.mixer.MixerFeature
import org.balch.orpheus.features.pulsar.mixer.MixerPanel

/**
 * Whether the Mix tab offers the per-track TRACKS view beside its bus faders.
 * On unless built with `-PshowTuning=false`; tests provide it to render either way.
 */
val LocalShowTuning = staticCompositionLocalOf { DjBuildKonfig.SHOW_TUNING }

/** The Mix tab's panel: the bus mixer, plus the TRACKS view unless [LocalShowTuning] is off. */
@Composable
internal fun DjMixPanel(
    feature: MixerFeature,
    trackVizFlows: List<StateFlow<FloatArray>>,
    modifier: Modifier = Modifier,
    masterOutVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    showExpandedTitle: Boolean = true,
    fillHeight: Boolean = true,
    startOnTracks: Boolean = false,
) {
    MixerPanel(
        feature = feature,
        trackVizFlows = trackVizFlows,
        masterOutVizFlow = masterOutVizFlow,
        modifier = modifier,
        isExpanded = true,
        onExpandedChange = {},
        showCollapsedHeader = false,
        showExpandedTitle = showExpandedTitle,
        fillHeight = fillHeight,
        showTuning = LocalShowTuning.current,
        startOnTracks = startOnTracks,
    )
}
