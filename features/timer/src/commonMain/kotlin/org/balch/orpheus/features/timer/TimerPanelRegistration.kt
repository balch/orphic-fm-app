package org.balch.orpheus.features.timer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.HeaderPanelScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.featurePanelPreview
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
class TimerPanelRegistration : FeaturePanel {
    override val panelId = PanelId.TIMER
    override val description = "Sleep timer with countdown and auto-fade"
    override val weight = 0.3f
    override val label = "SLEEP"
    override val color = OrpheusColors.sleepMoonlight

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        TimerPanel(modifier = modifier, isExpanded = isExpanded, onExpandedChange = onExpandedChange)
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.TIMER,
            weight = 0.3f,
            label = "SLEEP",
            color = OrpheusColors.sleepMoonlight,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            TimerPanel(
                feature = TimerViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
