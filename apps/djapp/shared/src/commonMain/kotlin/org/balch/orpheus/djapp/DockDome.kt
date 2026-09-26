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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.readableOnDark

/**
 * Fraction of the dome's diameter that rises above the bottom bar's visible top edge (the line where
 * [DockBarGlass] starts), on every dock off TV hardware. Tune this, then retune [DockDomeRingSize] to
 * match; `DockDomeRaiseTest` pins the relationship between them.
 */
internal const val DockDomeRiseFraction = 1f / 3f

/**
 * The dock dome's ring on desktop, tablets and the unfolded Fold, at every window size and density.
 * The ring's bottom sits a constant 82dp below the bar's top edge (its name is on the toggles' label
 * line), so this is `82dp / (1 - DockDomeRiseFraction)`.
 */
internal val DockDomeRingSize = 123.dp

/** TV hardware keeps a smaller dome, static there, still raised out of the bar. */
internal val TvDockDomeRingSize = 96.dp

internal fun dockDomeRingSize(television: Boolean): Dp = if (television) TvDockDomeRingSize else DockDomeRingSize

/** Extra room each side of the dome's slot off TV hardware, so it isn't crowded by Mix and Horn. */
internal val DockDomeSideRoom = 4.dp

/**
 * The dome's slot in the bar, or the ring where that is wider: room for most names at
 * [DockNameSize] without reaching a docked neighbour's plate. Longer names scroll.
 */
internal val DockDomeMinSlot = 140.dp

internal fun dockDomeSlot(ringSize: Dp): Dp = max(ringSize, DockDomeMinSlot)

/** The dome's vibe name, a size up from the toggles' 24sp labels, as the phone bar's is from its tabs'. */
internal val DockNameSize = 28.sp

/**
 * The dock's play/pause: the vibe transport's dome in its music ring, in the bottom bar's centre
 * slot. It rises out of the bar with its name on the toggles' label line, as in the phone bar: tap to
 * toggle, drag to skip. It takes the dock's launch focus, so Space, Enter and the D-pad's select work
 * at once, and held by the keyboard its focus mark takes the bar's [accent] and rides the dock's
 * focus idle fade. TV hardware gets it static: no pulse, and the ring and dome hold still.
 */
@Composable
internal fun DockDome(
    pulsarFeature: PulsarFeature,
    onTogglePlayback: () -> Unit,
    ringSize: Dp,
    nameStyle: TextStyle,
    nameLane: (Int) -> Int,
    accent: DockAccent,
    modifier: Modifier = Modifier,
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
    val actions = pulsarFeature.actions
    val focusRequester = remember { FocusRequester() }
    // Once per dock (it opens once a session), not on every recomposition, which would steal focus back.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Read in the mark's draw, so an accent that follows the visualization recomposes nothing.
    val focusColor = remember(accent) { ColorProducer { accent.color().readableOnDark() } }
    Box(
        modifier
            .width(dockDomeSlot(ringSize))
            .focusRequester(focusRequester)
            .liftRingAboveName(ringSize),
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
            nameLane = nameLane,
            // A name wider than a raised ring would widen its tap target over the stage beside it.
            ringWideTarget = true,
            nameDrop = BarNameDrop,
            ringSize = ringSize,
            focusColor = focusColor,
            position = nav.songPosition(),
            pulse = pulse,
            previewWavePhase = previewWavePhase,
            previewZipMs = previewZipMs,
        )
    }
}

/**
 * Reports the dome from its name down and places the ring above that, as the phone bar's transport
 * does, so a row aligned by baseline puts the name on its labels' line. The transport sits inside
 * it, so its tap target covers the raised ring.
 */
private fun Modifier.liftRingAboveName(ringSize: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lift = (TransportPadding.roundToPx() + ringSize.roundToPx()).coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - lift) { placeable.placeRelative(0, -lift) }
}
