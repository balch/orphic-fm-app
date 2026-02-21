package org.balch.orpheus.features.evo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class EvoPanelRegistration : FeaturePanel {
    override val panelId = PanelId.EVO
    override val description = "Algorithmic Evolution Panel"
    override val weight = 0.5f
    override val label = "Evo"
    override val color = OrpheusColors.evoGold

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        EvoPanel(
            evoFeature = EvoViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.EVO,
            weight = 0.5f,
            label = "Evo",
            color = OrpheusColors.evoGold,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            EvoPanel(
                evoFeature = EvoViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
