package org.balch.orpheus.features.resonator

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
class ResonatorPanelRegistration : FeaturePanel {
    override val panelId = PanelId.RESONATOR
    override val description = "Add texture to sounds"
    override val weight = 1.0f
    override val label = "Rezo"
    override val color = OrpheusColors.lakersGold

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        ResonatorPanel(
            feature = ResonatorViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.RESONATOR,
            label = "Rezo",
            color = OrpheusColors.lakersGold,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            ResonatorPanel(
                feature = ResonatorViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
