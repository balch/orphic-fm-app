package org.balch.orpheus.features.warps

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
class WarpsPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.WARPS
    override val description = "Cross Modulation"
    override val weight = 1.0f
    override val label = "Marps"
    override val color = OrpheusColors.warpsGreen

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        WarpsPanel(
            feature = WarpsViewModel.feature(),
            carrierVizFlow = synthEngine.warpsCarrierVizFlow,
            modulatorVizFlow = synthEngine.warpsModVizFlow,
            outputVizFlow = synthEngine.warpsOutVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.WARPS,
            label = "Marps",
            color = OrpheusColors.warpsGreen,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            WarpsPanel(
                feature = WarpsViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
