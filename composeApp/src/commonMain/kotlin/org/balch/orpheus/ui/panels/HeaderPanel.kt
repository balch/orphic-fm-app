package org.balch.orpheus.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.ai.AiOptionsPanel
import org.balch.orpheus.features.ai.PanelId
import org.balch.orpheus.features.delay.DelayFeature
import org.balch.orpheus.features.delay.ModDelayPanel
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.evo.EvoFeature
import org.balch.orpheus.features.evo.EvoPanel
import org.balch.orpheus.features.lfo.HyperLfoPanel
import org.balch.orpheus.features.lfo.LfoFeature
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.midi.MidiPanel
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsPanel
import org.balch.orpheus.features.stereo.StereoFeature
import org.balch.orpheus.features.stereo.StereoPanel
import org.balch.orpheus.features.tidal.ui.LiveCodeFeature
import org.balch.orpheus.features.tidal.ui.LiveCodePanel
import org.balch.orpheus.features.viz.VizFeature
import org.balch.orpheus.features.viz.VizPanel

/**
 * A container for the top header panel row that manages expansion state
 * and applies weight-based layout to expanded panels.
 */
@Composable
fun HeaderPanel(
    modifier: Modifier = Modifier,
    headerFeature: HeaderFeature,
    presetsFeature: PresetsFeature,
    midiFeature: MidiFeature,
    stereoFeature: StereoFeature,
    vizFeature: VizFeature,
    evoFeature: EvoFeature,
    lfoFeature: LfoFeature,
    delayFeature: DelayFeature,
    distortionFeature: DistortionFeature,
    liveCodeFeature: LiveCodeFeature,
    aiOptionsFeature: AiOptionsFeature,
    height: Dp = 260.dp,
    onDialogActiveChange: (Boolean) -> Unit = {}
) {
    val uiState by headerFeature.stateFlow.collectAsState()
    val headerActions = headerFeature.actions

    fun setExpanded(panelId: PanelId, expanded: Boolean) {
        headerActions.setExpanded(panelId, expanded)
    }

    fun isExpanded(panelId: PanelId): Boolean = uiState.isExpanded(panelId)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PresetsPanel(
            feature = presetsFeature,
            isExpanded = isExpanded(PanelId.PRESETS),
            onExpandedChange = { setExpanded(PanelId.PRESETS, it) },
            modifier = panelModifier(isExpanded(PanelId.PRESETS))
        )
        MidiPanel(
            feature = midiFeature,
            isExpanded = isExpanded(PanelId.MIDI),
            onExpandedChange = { setExpanded(PanelId.MIDI, it) },
            modifier = panelModifier(isExpanded(PanelId.MIDI))
        )
        StereoPanel(
            feature = stereoFeature,
            isExpanded = isExpanded(PanelId.STEREO),
            onExpandedChange = { setExpanded(PanelId.STEREO, it) },
            modifier = panelModifier(isExpanded(PanelId.STEREO))
        )
        VizPanel(
            feature = vizFeature,
            isExpanded = isExpanded(PanelId.VIZ),
            onExpandedChange = { setExpanded(PanelId.VIZ, it) },
            modifier = panelModifier(isExpanded(PanelId.VIZ))
        )
        EvoPanel(
            evoFeature = evoFeature,
            isExpanded = isExpanded(PanelId.EVO),
            onExpandedChange = { setExpanded(PanelId.EVO, it) },
            modifier = panelModifier(isExpanded(PanelId.EVO))
        )
        HyperLfoPanel(
            feature = lfoFeature,
            isExpanded = isExpanded(PanelId.LFO),
            onExpandedChange = { setExpanded(PanelId.LFO, it) },
            modifier = panelModifier(isExpanded(PanelId.LFO))
        )
        ModDelayPanel(
            feature = delayFeature,
            isExpanded = isExpanded(PanelId.DELAY),
            onExpandedChange = { setExpanded(PanelId.DELAY, it) },
            modifier = panelModifier(isExpanded(PanelId.DELAY))
        )
        DistortionPanel(
            feature = distortionFeature,
            isExpanded = isExpanded(PanelId.DISTORTION),
            onExpandedChange = { setExpanded(PanelId.DISTORTION, it) },
            modifier = panelModifier(isExpanded(PanelId.DISTORTION), maxWidth = 220.dp)
        )
        // Live Coding panel
        LiveCodePanel(
            feature = liveCodeFeature,
            isExpanded = isExpanded(PanelId.CODE),
            onExpandedChange = { setExpanded(PanelId.CODE, it) },
            modifier = panelModifier(isExpanded(PanelId.CODE))
        )
        // AI Options panel
        AiOptionsPanel(
            feature = aiOptionsFeature,
            isExpanded = isExpanded(PanelId.AI),
            onExpandedChange = { setExpanded(PanelId.AI, it) },
            modifier = panelModifier(isExpanded(PanelId.AI), maxWidth = 200.dp)
        )
    }
}

/**
 * Returns the appropriate modifier for a panel based on its expansion state.
 * Expanded panels get weight(1f) to share available space equally.
 * Collapsed panels use their intrinsic width (just the header).
 */
@Composable
private fun RowScope.panelModifier(isExpanded: Boolean, maxWidth: Dp? = null): Modifier {
    return if (isExpanded) {
        val base = Modifier.weight(1f).fillMaxHeight()
        if (maxWidth != null) base.widthIn(max = maxWidth) else base
    } else {
        Modifier.fillMaxHeight()
    }
}
