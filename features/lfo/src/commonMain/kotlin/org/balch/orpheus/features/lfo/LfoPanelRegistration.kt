package org.balch.orpheus.features.lfo

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
class LfoPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.LFO
    override val description = "Provide wave patterns to produce sounds"
    override val weight = 0.6f
    override val label = "LFO"
    override val color = OrpheusColors.neonCyan

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        DuoLfoPanel(
            feature = LfoViewModel.feature(),
            vizFlow = synthEngine.lfoVizFlow,
            vizCh1Flow = synthEngine.lfoCh1VizFlow,
            vizCh2Flow = synthEngine.lfoCh2VizFlow,
            vizCh3Flow = synthEngine.lfoCh3VizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.LFO,
            weight = 0.6f,
            label = "LFO",
            color = OrpheusColors.neonCyan,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            DuoLfoPanel(
                feature = LfoViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
