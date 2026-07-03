package org.balch.orpheus.djapp.ai

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.djapp.AdaptiveAiSheet
import org.balch.orpheus.djapp.AiTab
import org.balch.orpheus.djapp.DjRoute
import org.balch.orpheus.djapp.HornTab
import org.balch.orpheus.djapp.variant.DjTabContribution

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<DjTabContribution>())
class AiTabContribution : DjTabContribution {
    override val route: DjRoute = AiTab
    override val replaces: DjRoute? = HornTab

    @Composable
    override fun Content(
        isOpen: Boolean,
        modifier: Modifier,
        isLandscape: Boolean,
        onDismiss: () -> Unit,
    ) {
        // Content stays composed while the sheet is closed (isOpen=false) so we can cancel the
        // in-flight agent run whenever the sheet CLOSES — by ANY path (scrim/drag/back, the nav-item
        // toggle, or switching to another sheet), not just the scrim tap. A late vibe must not
        // auto-start audio "from silence" after the user has left. The reset is keyed off the
        // isOpen true->false transition (NOT onDispose): a config-change recomposition restores
        // isOpen=true and so must NOT cancel a run the user wanted to keep. The agent is greeting-
        // free (ON_FIRST_PROMPT), so resolving the feature while closed issues no LLM traffic.
        val feature = DjAiViewModel.feature()
        var wasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(isOpen) {
            if (!isOpen && wasOpen) feature.actions.reset()
            wasOpen = isOpen
        }
        if (isOpen) {
            BoxWithConstraints(modifier.fillMaxSize()) {
                AdaptiveAiSheet(
                    isLandscape = isLandscape,
                    portraitPeekHeight = maxHeight * 0.66f,
                    onDismiss = onDismiss,
                ) {
                    DjAiPanel(feature = feature, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
