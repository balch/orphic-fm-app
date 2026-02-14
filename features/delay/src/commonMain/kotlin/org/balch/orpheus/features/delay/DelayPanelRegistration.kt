package org.balch.orpheus.features.delay

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
class DelayPanelRegistration : FeaturePanel {
    override val panelId = PanelId.DELAY
    override val description = "Add repeating lines to sounds"
    override val weight = 1.0f
    override val label = "Delay"
    override val color = OrpheusColors.warmGlow

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
            label = "Delay",
            color = OrpheusColors.warmGlow,
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
