package org.balch.orpheus.features.cowbell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.HeaderPanelScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview

@Inject
@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
class CowbellPanelRegistration(
    private val feature: CowbellFeature,
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.COWBELL
    override val description = "A playable cowbell you strike, hold and bend"
    override val weight = 1.0f
    override val label = "Cowbell"
    override val color = CowbellPanelColor

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        // The rim glow's poll runs only while the panel is on screen, Signal Monitor or not.
        DisposableEffect(synthEngine) {
            synthEngine.setCowbellVizEnabled(true)
            onDispose { synthEngine.setCowbellVizEnabled(false) }
        }
        CowbellPanel(
            feature = feature,
            outLevel = synthEngine.cowbellOutVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.COWBELL,
            label = "Cowbell",
            color = CowbellPanelColor,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            CowbellPanel(
                feature = CowbellViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
