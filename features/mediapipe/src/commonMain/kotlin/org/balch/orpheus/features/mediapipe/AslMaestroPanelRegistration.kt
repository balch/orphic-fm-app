package org.balch.orpheus.features.mediapipe

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
class AslMaestroPanelRegistration : FeaturePanel {
    override val panelId = PanelId.ASL_MAESTRO
    override val description = "Camera hand tracking gesture control"
    override val weight = 1.0f
    override val label = "Gesture"
    override val color = OrpheusColors.synthGreen

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        AslMaestroPanel(
            feature = MediaPipeViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.ASL_MAESTRO,
            label = "Gesture",
            color = OrpheusColors.synthGreen,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            AslMaestroPanel(
                feature = MediaPipeViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
