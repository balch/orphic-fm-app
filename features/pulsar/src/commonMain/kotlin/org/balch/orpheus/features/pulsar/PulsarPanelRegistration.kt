package org.balch.orpheus.features.pulsar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<FeaturePanel>())
class PulsarPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.PULSAR
    override val description = "Pulsar Beat Machine"
    override val weight = 0.5f
    override val label = "Pulsar"
    override val color = OrpheusColors.cosmicPurple

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        PulsarPanel(
            pulsar = PulsarViewModel.feature(),
            vizFlow = synthEngine.pulsarVizFlow,
            trackVizFlows = synthEngine.pulsarTrackVizFlows,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.PULSAR,
            weight = 0.5f,
            label = "Pulsar",
            color = OrpheusColors.cosmicPurple,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            PulsarPanel(
                pulsar = PulsarViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
