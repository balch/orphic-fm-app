package org.balch.orpheus.features.drum

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.CompactPortraitConfig
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.PanelPosition
import org.balch.orpheus.core.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class DrumsPanelRegistration : FeaturePanel {
    override val panelId = PanelId.DRUMS
    override val position = PanelPosition.END
    override val linkedFeature: PanelId? = null
    override val weight = 1.15f
    override val defaultExpanded = false
    override val compactPortrait = CompactPortraitConfig("Drums", OrpheusColors.neonMagenta, 120)

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        DrumsPanel(
            drumFeature = DrumViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.DRUMS,
            position = PanelPosition.END,
            weight = 1.15f,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            DrumsPanel(
                drumFeature = DrumViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
