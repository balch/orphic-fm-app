package org.balch.orpheus.features.horn

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class HornPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.HORN
    override val description = "Rotating speaker cabinet effect"
    override val weight = 0.65f
    override val label = "Horn"
    override val color = OrpheusColors.hornCrimson // Blackout Crimson accent

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        HornPanel(
            feature = HornViewModel.feature(),
            inVizFlow = synthEngine.hornInVizFlow,
            outVizFlow = synthEngine.hornOutVizFlow,
            hornPhaseVizFlow = synthEngine.hornPhaseVizFlow,
            wooferPhaseVizFlow = synthEngine.wooferPhaseVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.HORN,
            weight = 0.65f,
            label = "Horn",
            color = OrpheusColors.hornCrimson,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            HornPanel(
                feature = HornViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
