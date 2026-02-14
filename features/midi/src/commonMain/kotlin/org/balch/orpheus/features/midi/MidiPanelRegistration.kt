package org.balch.orpheus.features.midi

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class MidiPanelRegistration : FeaturePanel {
    override val panelId = PanelId.MIDI
    override val description = "Assign MIDI commands to control the synthesizer"
    override val weight = 0.5f
    override val label = "MIDI"
    override val color = OrpheusColors.electricBlue

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        MidiPanel(
            feature = MidiViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.MIDI,
            weight = 0.5f,
            label = "MIDI",
            color = OrpheusColors.electricBlue,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            MidiPanel(
                feature = MidiViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
