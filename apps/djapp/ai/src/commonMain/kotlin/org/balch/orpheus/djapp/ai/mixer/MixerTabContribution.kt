package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.djapp.DjRoute
import org.balch.orpheus.djapp.MixerTab
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.djapp.variant.LocalStageChromeOverhang

/** The AI edition's Mixer tab, appended after the existing tabs. */
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<DjTabContribution>())
class MixerTabContribution : DjTabContribution {
    override val route: DjRoute = MixerTab
    override val replaces: DjRoute? = null

    @Composable
    override fun Content(
        isOpen: Boolean,
        modifier: Modifier,
        isLandscape: Boolean,
        onDismiss: () -> Unit,
    ) {
        val feature = AiMixerViewModel.feature()
        // The panel collects the meters flow, so it is composed only while the sheet is open.
        if (isOpen) MixerSheetContent(feature, onDismiss)
    }
}

/**
 * The open sheet. The bar's chrome (the dock's dome pill, the phone bar's ring) reaches [LocalStageChromeOverhang]
 * over the stage's bottom edge; the panel takes it whole, so the sheet's 8dp margin stays as the gap above it.
 */
@Composable
internal fun MixerSheetContent(feature: AiMixerFeature, onDismiss: () -> Unit) {
    val overhang = LocalStageChromeOverhang.current
    MixerSheet(onDismiss) {
        AiMixerPanel(feature, Modifier.fillMaxSize(), shape = MixerSheetShape, bottomOverhang = overhang)
    }
}
