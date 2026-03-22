package org.balch.orpheus.features.dj

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class DjPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.DJ
    override val description = "DJ Turntable"
    override val weight = 1.0f
    override val label = "DJ"
    override val color = OrpheusColors.djRed

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        // Start/stop turntable viz polling based on panel expansion
        LaunchedEffect(isExpanded) {
            synthEngine.setTurntableVizEnabled(isExpanded)
        }

        DjPanel(
            feature = DjViewModel.feature(),
            vizFlowA = synthEngine.djVizFlowA,
            vizFlowB = synthEngine.djVizFlowB,
            outVizFlow = synthEngine.djOutVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.DJ,
            label = "DJ",
            color = OrpheusColors.djRed,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            DjPanel(
                feature = DjViewModel.previewFeature(),
                vizFlowA = MutableStateFlow(FloatArray(0)),
                vizFlowB = MutableStateFlow(FloatArray(0)),
                outVizFlow = MutableStateFlow(FloatArray(0)),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
