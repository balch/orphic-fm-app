package org.balch.orpheus.features.reverb

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
class ReverbPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.REVERB
    override val description = "Add spatial reverb effect"
    override val weight = 0.5f
    override val label = "Reverb"
    override val color = OrpheusColors.electricBlue

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        ReverbPanel(
            feature = ReverbViewModel.feature(),
            inVizFlow = synthEngine.reverbInVizFlow,
            outVizFlow = synthEngine.reverbOutVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.REVERB,
            weight = 0.5f,
            label = "Reverb",
            color = OrpheusColors.electricBlue,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            ReverbPanel(
                feature = ReverbViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
