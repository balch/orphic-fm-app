package org.balch.orpheus.features.pulsar.mixer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.HeaderPanelScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
class MixerPanelRegistration(
    private val feature: MixerFeature,
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.MIXER
    override val description = "Per-band volume mixer with master distortion"
    override val weight = 0.6f
    override val label = "Mixer"
    override val color = OrpheusColors.mixerMasterPurple

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        MixerPanel(
            feature = feature,
            trackVizFlows = synthEngine.pulsarTrackVizFlows,
            masterOutVizFlow = synthEngine.masterOutVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.MIXER,
            weight = 0.6f,
            label = "Mixer",
            color = OrpheusColors.mixerMasterPurple,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            MixerPanel(
                feature = MixerViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
