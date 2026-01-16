package org.balch.orpheus.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.ai.AiOptionsPanel
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.ai.PanelId
import org.balch.orpheus.features.beats.DrumBeatsFeature
import org.balch.orpheus.features.beats.DrumBeatsPanel
import org.balch.orpheus.features.beats.DrumBeatsViewModel
import org.balch.orpheus.features.delay.DelayFeature
import org.balch.orpheus.features.delay.DelayFeedbackPanel
import org.balch.orpheus.features.delay.DelayViewModel
import org.balch.orpheus.features.distortion.DistortionFeature
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.distortion.DistortionViewModel
import org.balch.orpheus.features.drums808.DrumFeature
import org.balch.orpheus.features.drums808.DrumViewModel
import org.balch.orpheus.features.drums808.DrumsPanel
import org.balch.orpheus.features.evo.EvoFeature
import org.balch.orpheus.features.evo.EvoPanel
import org.balch.orpheus.features.evo.EvoViewModel
import org.balch.orpheus.features.grains.GrainsFeature
import org.balch.orpheus.features.grains.GrainsPanel
import org.balch.orpheus.features.grains.GrainsViewModel
import org.balch.orpheus.features.lfo.DuoLfoPanel
import org.balch.orpheus.features.lfo.LfoFeature
import org.balch.orpheus.features.lfo.LfoViewModel
import org.balch.orpheus.features.midi.MidiFeature
import org.balch.orpheus.features.midi.MidiPanel
import org.balch.orpheus.features.midi.MidiViewModel
import org.balch.orpheus.features.presets.PresetsFeature
import org.balch.orpheus.features.presets.PresetsPanel
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.resonator.ResonatorFeature
import org.balch.orpheus.features.resonator.ResonatorPanel
import org.balch.orpheus.features.resonator.ResonatorViewModel
import org.balch.orpheus.features.stereo.StereoFeature
import org.balch.orpheus.features.stereo.StereoPanel
import org.balch.orpheus.features.stereo.StereoViewModel
import org.balch.orpheus.features.tidal.LiveCodeFeature
import org.balch.orpheus.features.tidal.LiveCodePanel
import org.balch.orpheus.features.tidal.LiveCodeViewModel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizPanel
import org.balch.orpheus.features.visualizations.VizViewModel

/**
 * A container for the top header panel row that manages expansion state
 * and applies weight-based layout to expanded panels.
 */
@Composable
fun HeaderPanel(
    modifier: Modifier = Modifier,
    headerFeature: HeaderFeature = HeaderViewModel.feature(),
    presetsFeature: PresetsFeature = PresetsViewModel.feature(),
    midiFeature: MidiFeature = MidiViewModel.feature(),
    stereoFeature: StereoFeature = StereoViewModel.feature(),
    vizFeature: VizFeature = VizViewModel.feature(),
    evoFeature: EvoFeature = EvoViewModel.feature(),
    lfoFeature: LfoFeature = LfoViewModel.feature(),
    delayFeature: DelayFeature = DelayViewModel.feature(),
    distortionFeature: DistortionFeature = DistortionViewModel.feature(),
    resonatorFeature: ResonatorFeature = ResonatorViewModel.feature(),
    grainsFeature: GrainsFeature = GrainsViewModel.feature(),
    liveCodeFeature: LiveCodeFeature = LiveCodeViewModel.feature(),
    aiOptionsFeature: AiOptionsFeature = AiOptionsViewModel.feature(),
    drumFeature: DrumFeature = DrumViewModel.feature(),
    drumBeatsFeature: DrumBeatsFeature = DrumBeatsViewModel.feature(),
    height: Dp = 260.dp,
    onDialogActiveChange: (Boolean) -> Unit = {}
) {
    val uiState by headerFeature.stateFlow.collectAsState()
    val headerActions = headerFeature.actions

    fun PanelId.setExpanded(expanded: Boolean) {
        headerActions.setExpanded(this, expanded)
    }

    fun PanelId.isExpanded(): Boolean = uiState.isExpanded(this)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PresetsPanel(
            feature = presetsFeature,
            isExpanded = PanelId.PRESETS.isExpanded(),
            onExpandedChange = { PanelId.PRESETS.setExpanded(it) },
            modifier = panelModifier(PanelId.PRESETS.isExpanded())
        )
        MidiPanel(
            feature = midiFeature,
            isExpanded = PanelId.MIDI.isExpanded(),
            onExpandedChange = { PanelId.MIDI.setExpanded(it) },
            modifier = panelModifier(PanelId.MIDI.isExpanded(), weight = .5f)
        )
        StereoPanel(
            feature = stereoFeature,
            isExpanded = PanelId.STEREO.isExpanded(),
            onExpandedChange = { PanelId.STEREO.setExpanded(it) },
            modifier = panelModifier(PanelId.STEREO.isExpanded(), weight = .5f)
        )
        VizPanel(
            feature = vizFeature,
            isExpanded = PanelId.VIZ.isExpanded(),
            onExpandedChange = { PanelId.VIZ.setExpanded(it) },
            modifier = panelModifier(PanelId.VIZ.isExpanded(), weight = .5f)
        )
        EvoPanel(
            evoFeature = evoFeature,
            isExpanded = PanelId.EVO.isExpanded(),
            onExpandedChange = { PanelId.EVO.setExpanded(it) },
            modifier = panelModifier(PanelId.EVO.isExpanded(), weight = .5f)
        )
        DuoLfoPanel(
            feature = lfoFeature,
            isExpanded = PanelId.LFO.isExpanded(),
            onExpandedChange = { PanelId.LFO.setExpanded(it) },
            modifier = panelModifier(PanelId.LFO.isExpanded(), weight = .5f)
        )
        DelayFeedbackPanel(
            feature = delayFeature,
            isExpanded = PanelId.DELAY.isExpanded(),
            onExpandedChange = { PanelId.DELAY.setExpanded(it) },
            modifier = panelModifier(PanelId.DELAY.isExpanded(), weight = 1.25f)
        )
        DistortionPanel(
            feature = distortionFeature,
            isExpanded = PanelId.DISTORTION.isExpanded(),
            onExpandedChange = { PanelId.DISTORTION.setExpanded(it) },
            modifier = panelModifier(PanelId.DISTORTION.isExpanded(), weight = .5f)
        )
        // Rings Resonator panel
        ResonatorPanel(
            feature = resonatorFeature,
            isExpanded = PanelId.RESONATOR.isExpanded(),
            onExpandedChange = { PanelId.RESONATOR.setExpanded(it) },
            modifier = panelModifier(PanelId.RESONATOR.isExpanded())
        )
        // Grains Panel
        GrainsPanel(
             feature = grainsFeature,
             isExpanded = PanelId.GRAINS.isExpanded(),
             onExpandedChange = { PanelId.GRAINS.setExpanded(it) },
             modifier = panelModifier(PanelId.GRAINS.isExpanded())
        )
        // Live Coding panel
        LiveCodePanel(
            feature = liveCodeFeature,
            isExpanded = PanelId.CODE.isExpanded(),
            onExpandedChange = { PanelId.CODE.setExpanded(it) },
            modifier = panelModifier(PanelId.CODE.isExpanded())
        )
        // Drums panel
        DrumsPanel(
            drumFeature = drumFeature,
            isExpanded = PanelId.DRUMS.isExpanded(),
            onExpandedChange = { PanelId.DRUMS.setExpanded(it) },
            modifier = panelModifier(PanelId.DRUMS.isExpanded(), weight = 1.15f)
        )
        // Pattern panel
        DrumBeatsPanel(
            drumBeatsFeature = drumBeatsFeature,
            isExpanded = PanelId.PATTERN.isExpanded(),
            onExpandedChange = { PanelId.PATTERN.setExpanded(it) },
            modifier = panelModifier(PanelId.PATTERN.isExpanded(), weight = 1.25f)
        )
        // AI Options panel
        AiOptionsPanel(
            feature = aiOptionsFeature,
            isExpanded = PanelId.AI.isExpanded(),
            onExpandedChange = { PanelId.AI.setExpanded(it) },
            modifier = panelModifier(
                isExpanded = PanelId.AI.isExpanded(),
                weight = .4f,
            )
        )
    }
}

/**
 * Returns the appropriate modifier for a panel based on its expansion state.
 * Expanded panels get weight(1f) to share available space equally.
 * Collapsed panels use their intrinsic width (just the header).
 */
@Composable
private fun RowScope.panelModifier(
    isExpanded: Boolean,
    weight: Float = 1f
): Modifier {
    return if (isExpanded) {
        Modifier
            .fillMaxHeight()
            .weight(weight)
    } else {
        Modifier.fillMaxHeight()
    }
}
