package org.balch.orpheus.features.lfo

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
class LfoPanelRegistration : FeaturePanel {
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
