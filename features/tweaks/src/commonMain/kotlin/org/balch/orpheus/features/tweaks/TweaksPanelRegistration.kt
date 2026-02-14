package org.balch.orpheus.features.tweaks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.featurePanelPreview
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.theme.OrpheusColors

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class TweaksPanelRegistration : FeaturePanel {
    override val panelId = PanelId.TWEAKS
    override val description = "Modulation tweaks panel"
    override val weight = 0.5f
    override val label = "Tweaks"
    override val color = OrpheusColors.electricBlue

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        ModTweaksPanel(
            voiceFeature = VoiceViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.TWEAKS,
            weight = 0.5f,
            label = "Tweaks",
            color = OrpheusColors.electricBlue,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            ModTweaksPanel(
                voiceFeature = VoiceViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
