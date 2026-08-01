package org.balch.orpheus.features.distortion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.HeaderPanelScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
class DistortionPanelRegistration(
    private val feature: DistortionFeature,
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.DISTORTION
    override val description = "Control volume characteristics of sounds"
    override val weight = 0.6f
    override val label = "Distortion"
    override val color = OrpheusColors.neonMagenta

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        DistortionPanel(
            feature = feature,
            outVizFlow = synthEngine.masterOutVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.DISTORTION,
            weight = 0.6f,
            label = "Distortion",
            color = OrpheusColors.neonMagenta,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            DistortionPanel(
                feature = DistortionViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
