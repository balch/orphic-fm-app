package org.balch.orpheus.features.visualizations

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
class VizPanelRegistration : FeaturePanel {
    override val panelId = PanelId.VIZ
    override val description = "Display visualizations linked to the sound in the background"
    override val position = PanelPosition.START
    override val linkedFeature: PanelId? = null
    override val weight = 0.5f
    override val defaultExpanded = true
    override val compactPortrait = CompactPortraitConfig("Viz", OrpheusColors.vizGreen, 20)

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        VizPanel(
            feature = VizViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.VIZ,
            position = PanelPosition.START,
            weight = 0.5f,
            defaultExpanded = true,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            VizPanel(
                feature = VizViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
