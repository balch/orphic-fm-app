package org.balch.orpheus.features.midi

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.PanelPosition
import org.balch.orpheus.core.featurePanelPreview

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class MidiPanelRegistration : FeaturePanel {
    override val panelId = PanelId.MIDI
    override val description = "Assign MIDI commands to control the synthesizer"
    override val position = PanelPosition.START
    override val linkedFeature: PanelId? = null
    override val weight = 0.5f
    override val defaultExpanded = false

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
            position = PanelPosition.START,
            weight = 0.5f,
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
