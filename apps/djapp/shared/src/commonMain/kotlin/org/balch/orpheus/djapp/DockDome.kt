package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.readableOnDark

/**
 * The dock dome's ring on desktop, tablets and the unfolded Fold, at every window size and density.
 * Its bottom sits [DockDomeDrop] below the toggles' label line, so its top clears the bar by a
 * little and its name's pill rides above it, over the stage.
 */
internal val DockDomeRingSize = 160.dp

/** How far the ring's bottom sits below the toggles' label line. */
internal val DockDomeDrop = 16.dp

/** TV hardware keeps a smaller dome, static there, hung the same way with its pill above. */
internal val TvDockDomeRingSize = 144.dp

internal fun dockDomeRingSize(television: Boolean): Dp = if (television) TvDockDomeRingSize else DockDomeRingSize

/** Extra room each side of the dome's slot off TV hardware, so it isn't crowded by Mix and Horn. */
internal val DockDomeSideRoom = 4.dp

/** The dome's vibe name, a size up from the toggles' 24sp labels, as the phone bar's is from its tabs'. */
internal val DockNameSize = 28.sp

/** The widest the name's pill draws over the stage; a longer name scrolls inside it (ellipsizes on TV). */
internal val DockNamePillMaxWidth = 360.dp

/**
 * The dock's play/pause: the vibe transport's dome in its music ring, in the bottom bar's centre
 * slot: tap to toggle, drag to skip. The ring sits low in the bar, [DockDomeDrop] under the toggles'
 * label line, with its name in a pill above it over the stage. TV hardware gets it smaller and
 * static: no pulse, and the ring and dome hold still. It takes the dock's launch focus, so Space,
 * Enter and the D-pad's select work at once, and held by the keyboard its focus mark takes the bar's
 * [accent] and rides the dock's focus idle fade.
 */
@Composable
internal fun DockDome(
    pulsarFeature: PulsarFeature,
    onTogglePlayback: () -> Unit,
    ringSize: Dp,
    nameStyle: TextStyle,
    accent: DockAccent,
    modifier: Modifier = Modifier,
    // The keyboard focus mark; only TV hardware wears it.
    focusMark: Boolean = true,
    // Render-harness seams only: pin the ring's wave phase and paused zip, see rememberProgressWave.
    previewWavePhase: Float? = null,
    previewZipMs: Long? = null,
) {
    val nav by pulsarFeature.vibeNavFlow.collectAsStateWithLifecycle()
    // Only play/pause, so other Pulsar state changes never recompose the dome.
    val paused by remember(pulsarFeature) {
        pulsarFeature.stateFlow.map { it.globalPaused }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = pulsarFeature.stateFlow.value.globalPaused)
    // Read by value in the ring's frame loop, never collected. TV hardware runs no loop, so nothing holds it there.
    val pulse: (() -> MusicPulse)? = if (LocalTelevisionHardware.current) null else rememberMusicPulse(pulsarFeature)
    // Over the stage the pill's lane is its own, not the bar's gap between Mix and Horn.
    val pillPx = with(LocalDensity.current) { DockNamePillMaxWidth.roundToPx() }
    val pillLane = remember(pillPx) { { _: Int -> pillPx } }
    val actions = pulsarFeature.actions
    val focusRequester = remember { FocusRequester() }
    // Once per dock (it opens once a session), not on every recomposition, which would steal focus back.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Read in the mark's draw, so an accent that follows the visualization recomposes nothing.
    val focusColor = remember(accent) { ColorProducer { accent.color().readableOnDark() } }
    Box(
        modifier
            .width(ringSize)
            .focusRequester(focusRequester)
            .hangFromLabelLine(DockDomeDrop),
    ) {
        VibeTransportItem(
            name = nav.currentName,
            previousName = if (nav.previousRestarts) nav.currentName else nav.previousName,
            nextName = nav.nextName,
            progress = nav.progress,
            paused = paused,
            onTogglePlayback = onTogglePlayback,
            onNext = actions.nextVibe,
            onPrevious = actions.previousVibe,
            modifier = Modifier.fillMaxWidth(),
            previousRestarts = nav.previousRestarts,
            nameStyle = nameStyle,
            nameLane = pillLane,
            // A name wider than the ring would widen its tap target over the stage beside it.
            ringWideTarget = true,
            namePill = true,
            ringSize = ringSize,
            focusColor = focusColor.takeIf { focusMark },
            position = nav.songPosition(),
            pulse = pulse,
            previewWavePhase = previewWavePhase,
            previewZipMs = previewZipMs,
        )
    }
}
