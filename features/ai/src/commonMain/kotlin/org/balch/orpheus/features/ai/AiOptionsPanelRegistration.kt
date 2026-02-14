package org.balch.orpheus.features.ai

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
class AiOptionsPanelRegistration : FeaturePanel {
    override val panelId = PanelId.AI
    override val description = "Panel allowing user to select a patch"
    override val weight = 0.6f
    override val label = "AI"
    override val color = OrpheusColors.electricBlue

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        AiOptionsPanel(
            feature = AiOptionsViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.AI,
            weight = 0.6f,
            label = "AI",
            color = OrpheusColors.electricBlue,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            AiOptionsPanel(
                feature = AiOptionsViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
