package org.balch.orpheus.features.grains

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
class GrainsPanelRegistration : FeaturePanel {
    override val panelId = PanelId.GRAINS
    override val description = "Granular Molecule Synthesis"
    override val weight = 1.0f
    override val label = "Grains"
    override val color = OrpheusColors.grainsRed

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        GrainsPanel(
            feature = GrainsViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.GRAINS,
            label = "Grains",
            color = OrpheusColors.grainsRed,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            GrainsPanel(
                feature = GrainsViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
