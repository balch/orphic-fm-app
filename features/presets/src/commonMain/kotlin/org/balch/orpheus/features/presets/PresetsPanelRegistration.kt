package org.balch.orpheus.features.presets

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
class PresetsPanelRegistration : FeaturePanel {
    override val panelId = PanelId.PRESETS
    override val description = "Panel allowing user to select a patch"
    override val weight = 1.0f
    override val label = "Preset"
    override val color = OrpheusColors.presetOrange

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        PresetsPanel(
            feature = PresetsViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.PRESETS,
            label = "Preset",
            color = OrpheusColors.presetOrange,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            PresetsPanel(
                feature = PresetsViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
