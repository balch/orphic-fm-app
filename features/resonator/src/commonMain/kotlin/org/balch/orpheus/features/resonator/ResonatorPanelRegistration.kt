package org.balch.orpheus.features.resonator

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
class ResonatorPanelRegistration : FeaturePanel {
    override val panelId = PanelId.RESONATOR
    override val description = "Add texture to sounds"
    override val position = PanelPosition.MID
    override val linkedFeature: PanelId? = null
    override val weight = 1.0f
    override val defaultExpanded = false
    override val compactPortrait = CompactPortraitConfig("Rezo", OrpheusColors.lakersGold, 50)

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        ResonatorPanel(
            feature = ResonatorViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.RESONATOR,
            position = PanelPosition.MID,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            ResonatorPanel(
                feature = ResonatorViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
