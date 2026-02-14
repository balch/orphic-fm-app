package org.balch.orpheus.features.reverb

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.PanelPosition
import org.balch.orpheus.core.featurePanelPreview

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class ReverbPanelRegistration : FeaturePanel {
    override val panelId = PanelId.REVERB
    override val description = "Add spatial reverb effect"
    override val position = PanelPosition.MID
    override val linkedFeature: PanelId? = null
    override val weight = 0.5f
    override val defaultExpanded = false

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        ReverbPanel(
            feature = ReverbViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.REVERB,
            position = PanelPosition.MID,
            weight = 0.5f,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            ReverbPanel(
                feature = ReverbViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
