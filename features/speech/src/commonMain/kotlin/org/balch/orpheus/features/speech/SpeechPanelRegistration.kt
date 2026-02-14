package org.balch.orpheus.features.speech

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.FeaturePanel
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.PanelPosition
import org.balch.orpheus.core.featurePanelPreview

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<FeaturePanel>())
class SpeechPanelRegistration : FeaturePanel {
    override val panelId = PanelId.SPEECH
    override val description = "Speech synthesis panel showing AI speech output"
    override val position = PanelPosition.END
    override val linkedFeature: PanelId? = null
    override val weight = 1.0f
    override val defaultExpanded = false

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        SpeechPanel(
            feature = SpeechViewModel.feature(),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
            onTextFieldFocusChange = onDialogActiveChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.SPEECH,
            position = PanelPosition.END,
        ) { modifier, isExpanded, onExpandedChange, onDialogActiveChange ->
            SpeechPanel(
                feature = SpeechViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
                onTextFieldFocusChange = onDialogActiveChange,
            )
        }
    }
}
