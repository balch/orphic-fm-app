package org.balch.orpheus.features.record

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
class RecordPanelRegistration(
    private val feature: RecordFeature,
) : FeaturePanel {
    override val panelId = PanelId.RECORD
    override val description = "Record the output with a preset and telemetry for AI"
    override val weight = 0.6f
    override val label = "REC"
    override val color = OrpheusColors.recordRed

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        RecordPanel(
            feature = feature,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.RECORD,
            weight = 0.6f,
            label = "REC",
            color = OrpheusColors.recordRed,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            RecordPanel(
                feature = RecordViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
