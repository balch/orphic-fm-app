package org.balch.orpheus.djapp.ai

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
        // Closing the sheet does NOT cancel an in-flight run. The feature and agent are
        // app-scoped, so a generation keeps streaming into state while the sheet is away and
        // reopening shows live progress; the panel's explicit ✕ Cancel is the only abort. A
        // vibe that completes while the sheet is closed still applies and starts playback —
        // fulfilling the request is the point of having asked for it. The agent is greeting-
        // free (ON_FIRST_PROMPT), so resolving the feature while closed issues no LLM traffic.
        val feature = DjAiViewModel.feature()
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
