package org.balch.orpheus.features.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.HeaderPanelScope
import org.balch.orpheus.core.features.FeaturePanel
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
class VibeCreatePanelRegistration : FeaturePanel {
    override val panelId = PanelId.VIBE_CREATE
    override val description = "AI-created Pulsar vibe"
    override val weight = 1.0f
    override val label = "VIBE"
    override val color = OrpheusColors.neonCyan

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        VibeCreatePanel(
            feature = VibeCreateViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
            onDialogActiveChange = onDialogActiveChange,
        )
    }
}
