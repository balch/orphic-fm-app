package org.balch.orpheus.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.delay.ModDelayPanel
import org.balch.orpheus.features.distortion.DistortionPanel
import org.balch.orpheus.features.lfo.HyperLfoPanel
import org.balch.orpheus.features.midi.MidiPanel
import org.balch.orpheus.features.presets.PresetsPanel
import org.balch.orpheus.features.stereo.StereoPanel

/**
 * A container for the top header panel row that manages expansion state
 * and applies weight-based layout to expanded panels.
 */
@Composable
fun HeaderPanel(
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    onDialogActiveChange: (Boolean) -> Unit = {}
) {
    // Track expansion state for each panel
    var presetExpanded by remember { mutableStateOf(false) }
    var midiExpanded by remember { mutableStateOf(false) }
    var stereoExpanded by remember { mutableStateOf(false) }
    var vizExpanded by remember { mutableStateOf(true) }
    var lfoExpanded by remember { mutableStateOf(true) }
    var delayExpanded by remember { mutableStateOf(true) }
    var distortionExpanded by remember { mutableStateOf(true) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PresetsPanel(
            isExpanded = presetExpanded,
            onExpandedChange = { presetExpanded = it },
            onDialogActiveChange = onDialogActiveChange,
            modifier = panelModifier(presetExpanded)
        )
        MidiPanel(
            isExpanded = midiExpanded,
            onExpandedChange = { midiExpanded = it },
            modifier = panelModifier(midiExpanded)
        )
        StereoPanel(
            isExpanded = stereoExpanded,
            onExpandedChange = { stereoExpanded = it },
            modifier = panelModifier(stereoExpanded)
        )
        _root_ide_package_.org.balch.orpheus.features.viz.VizPanel(
            isExpanded = vizExpanded,
            onExpandedChange = { vizExpanded = it },
            modifier = panelModifier(vizExpanded)
        )
        HyperLfoPanel(
            isExpanded = lfoExpanded,
            onExpandedChange = { lfoExpanded = it },
            modifier = panelModifier(lfoExpanded)
        )
        ModDelayPanel(
            isExpanded = delayExpanded,
            onExpandedChange = { delayExpanded = it },
            modifier = panelModifier(delayExpanded)
        )
        DistortionPanel(
            isExpanded = distortionExpanded,
            onExpandedChange = { distortionExpanded = it },
            modifier = panelModifier(distortionExpanded)
        )
    }
}

/**
 * Returns the appropriate modifier for a panel based on its expansion state.
 * Expanded panels get weight(1f) to share available space equally.
 * Collapsed panels use their intrinsic width (just the header).
 */
@Composable
private fun RowScope.panelModifier(isExpanded: Boolean): Modifier {
    return if (isExpanded) {
        Modifier.weight(1f).fillMaxHeight()
    } else {
        Modifier.fillMaxHeight()
    }
}
