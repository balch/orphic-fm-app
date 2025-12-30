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
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.features.ai.AiOptionsPanel
import org.balch.orpheus.features.delay.ModDelayPanel
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.lfo.HyperLfoPanel
import org.balch.orpheus.features.midi.MidiPanel
import org.balch.orpheus.features.presets.PresetsPanel
import org.balch.orpheus.features.stereo.StereoPanel
import org.balch.orpheus.features.tidal.ui.LiveCodePanel
import org.balch.orpheus.features.viz.VizPanel

/**
 * A container for the top header panel row that manages expansion state
 * and applies weight-based layout to expanded panels.
 */
@Composable
fun HeaderPanel(
    modifier: Modifier = Modifier,
    viewModel: HeaderViewModel = metroViewModel(),
    height: Dp = 260.dp,
    isVizInitiallyExpanded: Boolean = true,
    onDialogActiveChange: (Boolean) -> Unit = {}
) {
    // Observe expansion state from ViewModel
    val presetExpanded by viewModel.presetExpanded.collectAsState()
    val midiExpanded by viewModel.midiExpanded.collectAsState()
    val stereoExpanded by viewModel.stereoExpanded.collectAsState()
    val vizExpanded by viewModel.vizExpanded.collectAsState()
    val lfoExpanded by viewModel.lfoExpanded.collectAsState()
    val delayExpanded by viewModel.delayExpanded.collectAsState()
    val distortionExpanded by viewModel.distortionExpanded.collectAsState()
    val codeExpanded by viewModel.codeExpanded.collectAsState()
    val aiExpanded by viewModel.aiExpanded.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PresetsPanel(
            isExpanded = presetExpanded,
            onExpandedChange = viewModel::setPresetExpanded,
            onDialogActiveChange = onDialogActiveChange,
            modifier = panelModifier(presetExpanded)
        )
        MidiPanel(
            isExpanded = midiExpanded,
            onExpandedChange = viewModel::setMidiExpanded,
            modifier = panelModifier(midiExpanded)
        )
        StereoPanel(
            isExpanded = stereoExpanded,
            onExpandedChange = viewModel::setStereoExpanded,
            modifier = panelModifier(stereoExpanded)
        )
        VizPanel(
            isExpanded = vizExpanded,
            onExpandedChange = viewModel::setVizExpanded,
            modifier = panelModifier(vizExpanded)
        )
        HyperLfoPanel(
            isExpanded = lfoExpanded,
            onExpandedChange = viewModel::setLfoExpanded,
            modifier = panelModifier(lfoExpanded)
        )
        ModDelayPanel(
            isExpanded = delayExpanded,
            onExpandedChange = viewModel::setDelayExpanded,
            modifier = panelModifier(delayExpanded)
        )
        DistortionPanel(
            isExpanded = distortionExpanded,
            onExpandedChange = viewModel::setDistortionExpanded,
            modifier = panelModifier(distortionExpanded, maxWidth = 220.dp)
        )
        // Live Coding panel - at the end, closed by default
        LiveCodePanel(
            isExpanded = codeExpanded,
            onExpandedChange = viewModel::setCodeExpanded,
            modifier = panelModifier(codeExpanded)
        )
        // AI Chat panel - at the very end, closed by default
        AiOptionsPanel(
            isExpanded = aiExpanded,
            onExpandedChange = viewModel::setAiExpanded,
            modifier = panelModifier(aiExpanded, maxWidth = 200.dp)
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
