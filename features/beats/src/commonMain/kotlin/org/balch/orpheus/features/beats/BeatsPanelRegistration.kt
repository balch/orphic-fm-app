package org.balch.orpheus.features.beats

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
class BeatsPanelRegistration : FeaturePanel {
    override val panelId = PanelId.BEATS
    override val position = PanelPosition.END
    override val linkedFeature = PanelId.DRUMS
    override val weight = 1.25f
    override val defaultExpanded = false
    override val compactPortrait = CompactPortraitConfig("Sequencer", OrpheusColors.neonCyan, 130)

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        DrumBeatsPanel(
            drumBeatsFeature = DrumBeatsViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.BEATS,
            position = PanelPosition.END,
            linkedFeature = PanelId.DRUMS,
            weight = 1.25f,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            DrumBeatsPanel(
                drumBeatsFeature = DrumBeatsViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
