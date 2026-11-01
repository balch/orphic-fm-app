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

/**
 * The bell with a bend fader, on phones only. It shares [CowbellFeature] with the BELL panel.
 *
 * It is listed only in the Compact (phone pager) factory set, at the far end from BELL, and is not
 * in the desktop catalog, so the two are never composed together. Each bell owns its own motion
 * source and `ShakeRig`; with one bell up, one rig drives the tilt and the fill's strikes. A layout
 * that composes both would double them, which is why this must stay out of any set that shows
 * BELL beside it (one rig per feature is a part 5 question).
 */
@Inject
@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
class CowbellBenderPanelRegistration(
    private val feature: CowbellFeature,
    private val synthEngine: SynthEngine,
) : FeaturePanel {
    override val panelId = PanelId.COWBELL_BEND
    override val description = "The cowbell with a bend fader: strike it, then bend it"
    override val weight = 1.0f
    override val label = "Bell Bend"
    override val color = CowbellPanelColor

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        DisposableEffect(synthEngine) {
            synthEngine.setCowbellVizEnabled(true)
            onDispose { synthEngine.setCowbellVizEnabled(false) }
        }
        CowbellBenderPanel(
            feature = feature,
            outLevel = synthEngine.cowbellOutVizFlow,
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.COWBELL_BEND,
            label = "Bell Bend",
            color = CowbellPanelColor,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            CowbellBenderPanel(
                feature = CowbellViewModel.previewFeature(),
                modifier = modifier,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}
