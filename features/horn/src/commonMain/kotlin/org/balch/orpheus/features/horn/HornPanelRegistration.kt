package org.balch.orpheus.features.horn

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class HornPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.HORN
    override val description = "Rotating speaker cabinet effect"
    override val weight = 0.65f
    override val label = "Horn"
    override val color = Color(0xFFCC2222) // Blackout Crimson accent

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
            color = Color(0xFFCC2222),
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
