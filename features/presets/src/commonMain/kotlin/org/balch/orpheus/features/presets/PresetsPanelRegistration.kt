package org.balch.orpheus.features.presets

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
class PresetsPanelRegistration : FeaturePanel {
    override val panelId = PanelId.PRESETS
    override val description = "Panel allowing user to select a patch"
    override val position = PanelPosition.FIRST
    override val linkedFeature: PanelId? = null
    override val weight = 1.0f
    override val defaultExpanded = false
    override val compactPortrait = CompactPortraitConfig("Preset", OrpheusColors.presetOrange, 10)

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
            position = PanelPosition.FIRST,
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
