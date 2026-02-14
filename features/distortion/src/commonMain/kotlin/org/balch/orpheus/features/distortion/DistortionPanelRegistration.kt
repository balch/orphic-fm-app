package org.balch.orpheus.features.distortion

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
class DistortionPanelRegistration : FeaturePanel {
    override val panelId = PanelId.DISTORTION
    override val position = PanelPosition.MID
    override val linkedFeature: PanelId? = null
    override val weight = 0.6f
    override val defaultExpanded = true
    override val compactPortrait = CompactPortraitConfig("Distortion", OrpheusColors.neonMagenta, 30)

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        DistortionPanel(
            feature = DistortionViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.DISTORTION,
            position = PanelPosition.MID,
            weight = 0.6f,
            defaultExpanded = true,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            DistortionPanel(
                feature = DistortionViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
