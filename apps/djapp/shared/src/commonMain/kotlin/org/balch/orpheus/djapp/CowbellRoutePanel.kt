package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.features.cowbell.CowbellFeature
import org.balch.orpheus.features.cowbell.CowbellViewModel
import org.balch.orpheus.features.cowbell.dj.CowbellDjPanel

/**
 * The Cowbell tab, sitting [lift] up off the bottom of its slot. A nav slot gives the panel its
 * height; a docked bell takes [CowbellDockedHeight]. The panel arms the bell's LIVE gate through
 * [feature] while it is shown, so a strike sounds through a paused vibe.
 *
 * [savedMotion] is held by the screen, not here: a tab switch drops the tab's own saved state. It
 * outlives process death but not a quit, so it wins both ways over what the singleton feature holds.
 */
@Composable
internal fun CowbellRoutePanel(
    synthEngine: SynthEngine,
    modifier: Modifier,
    docked: Boolean,
    lift: Dp,
    savedMotion: Boolean = false,
    onSaveMotion: (Boolean) -> Unit = {},
    feature: CowbellFeature = CowbellViewModel.feature(),
) {
    LaunchedEffect(feature) {
        feature.actions.onMotion(savedMotion)
        feature.stateFlow.map { it.motion }.dropWhile { it != savedMotion }.distinctUntilChanged().collect(onSaveMotion)
    }
    // The rim glow's poll runs only while the tab is on screen.
    DisposableEffect(synthEngine) {
        synthEngine.setCowbellVizEnabled(true)
        onDispose { synthEngine.setCowbellVizEnabled(false) }
    }
    val sized = if (docked) modifier.height(CowbellDockedHeight) else modifier
    CowbellDjPanel(
        feature = feature,
        modifier = sized.padding(bottom = lift),
        outLevel = synthEngine.cowbellOutVizFlow,
    )
}
