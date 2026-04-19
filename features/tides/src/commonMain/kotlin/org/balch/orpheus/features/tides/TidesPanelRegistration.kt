package org.balch.orpheus.features.tides

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<FeaturePanel>())
class TidesPanelRegistration(
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.TIDES
    override val description = "Function generator producing ramps, envelopes, and LFO shapes"
    override val weight = 1.0f
    override val label = "Waves"
    override val color = OrpheusColors.neonOrange

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        TidesPanel(
            tides = TidesViewModel.feature(),
            vizCh0Flow = synthEngine.tidesCh0VizFlow,
            vizCh1Flow = synthEngine.tidesCh1VizFlow,
            vizCh2Flow = synthEngine.tidesCh2VizFlow,
            vizCh3Flow = synthEngine.tidesCh3VizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.TIDES,
            label = "Waves",
            color = OrpheusColors.neonOrange,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            TidesPanel(
                tides = TidesViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
