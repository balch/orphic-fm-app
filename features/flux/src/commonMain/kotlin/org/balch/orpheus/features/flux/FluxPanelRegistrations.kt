package org.balch.orpheus.features.flux

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.featurePanelPreview
import org.balch.orpheus.features.drum.DrumViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class TriggerRouterPanelRegistration : FeaturePanel {
    override val panelId = PanelId.FLUX_TRIGGERS
    override val description = "Assigns sounds to Flux outputs"
    override val weight = 0.8f
    override val label = "Triggers"
    override val color = OrpheusColors.neonCyan

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
            weight = 0.8f,
            label = "Triggers",
            color = OrpheusColors.neonCyan,
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
    override val description = "Random music generator"
    override val weight = 1.0f
    override val label = "Flux"
    override val color = OrpheusColors.neonCyan

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
            label = "Flux",
            color = OrpheusColors.neonCyan,
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
