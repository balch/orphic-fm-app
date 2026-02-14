package org.balch.orpheus.features.delay

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
class DelayPanelRegistration : FeaturePanel {
    override val panelId = PanelId.DELAY
    override val position = PanelPosition.MID
    override val linkedFeature: PanelId? = null
    override val weight = 1.0f
    override val defaultExpanded = false
    override val compactPortrait = CompactPortraitConfig("Delay", OrpheusColors.warmGlow, 100)

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        DelayFeedbackPanel(
            feature = DelayViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.DELAY,
            position = PanelPosition.MID,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            DelayFeedbackPanel(
                feature = DelayViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
