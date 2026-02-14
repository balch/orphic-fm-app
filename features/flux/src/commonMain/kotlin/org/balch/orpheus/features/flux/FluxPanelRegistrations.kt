package org.balch.orpheus.features.flux

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
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class TriggerRouterPanelRegistration : FeaturePanel {
    override val panelId = PanelId.FLUX_TRIGGERS
    override val position = PanelPosition.MID
    override val linkedFeature: PanelId? = null
    override val weight = 0.8f
    override val defaultExpanded = false

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        TriggerRouterPanel(
            drumFeature = DrumViewModel.feature(),
            voiceFeature = VoiceViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.FLUX_TRIGGERS,
            position = PanelPosition.MID,
            weight = 0.8f,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            TriggerRouterPanel(
                drumFeature = DrumViewModel.previewFeature(),
                voiceFeature = VoiceViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class FluxPanelRegistration : FeaturePanel {
    override val panelId = PanelId.FLUX
    override val position = PanelPosition.MID
    override val linkedFeature = PanelId.FLUX_TRIGGERS
    override val weight = 1.0f
    override val defaultExpanded = false
    override val compactPortrait = CompactPortraitConfig("Flux", OrpheusColors.neonCyan, 80)

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        FluxPanel(
            flux = FluxViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.FLUX,
            position = PanelPosition.MID,
            linkedFeature = PanelId.FLUX_TRIGGERS,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            FluxPanel(
                flux = FluxViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
