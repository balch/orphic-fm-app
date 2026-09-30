package org.balch.orpheus.djapp.playlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The ball's size over the stage, where its die can hold a phrase. */
internal val RevealDiameter = 160.dp

private const val TurnHalfMs = 150
private const val PhraseHoldMs = 1_400L
private const val SinkMs = 300

/**
 * Plays [state]'s reveal over the whole screen: the ball rises from the dome to the stage's centre,
 * turns over to its phrase, then hands over to the sheet and sinks back into the dome. The sink ends
 * at the dome's centre and size, where the ring takes over the ball while the sheet is open.
 */
@Composable
internal fun EightBallReveal(state: EightBallRevealState, modifier: Modifier = Modifier) {
    val phase = state.phase
    val from = when (phase) {
        is RevealPhase.Showing -> phase.from
        is RevealPhase.Sinking -> phase.from
        RevealPhase.Idle, RevealPhase.Open -> return
    }
    val density = LocalDensity.current
    val endPx = with(density) { RevealDiameter.toPx() }
    // 0 at the dome, 1 at the stage's centre.
    val travel = remember { Animatable(0f) }
    val faceScale = remember { Animatable(1f) }
    var face by remember { mutableStateOf<EightBallFace>(EightBallFace.Eight) }
    // This overlay's own bounds in root coordinates: its origin, and the stage when none was reported.
    var overlay by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(phase) {
        when (phase) {
            is RevealPhase.Showing -> {
                face = EightBallFace.Eight
                faceScale.snapTo(1f)
                travel.snapTo(0f)
                travel.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 300f))
                faceScale.animateTo(0f, tween(TurnHalfMs))
                face = EightBallFace.Die(phase.phrase)
                faceScale.animateTo(1f, tween(TurnHalfMs))
                delay(PhraseHoldMs)
                state.handOver()
            }
            is RevealPhase.Sinking -> {
                // A tap mid-turn cut the turn short: the face sinks whole, not squashed.
                faceScale.snapTo(1f)
                travel.animateTo(0f, tween(SinkMs))
                state.finish()
            }
            RevealPhase.Idle, RevealPhase.Open -> Unit
        }
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { overlay = it.boundsInRoot() }) {
        EightBall(
            face = face,
            diameter = RevealDiameter,
            faceScale = { faceScale.value },
            modifier = Modifier
                .graphicsLayer {
                    val t = travel.value
                    val stage = state.stageBounds.takeUnless { it == Rect.Zero } ?: overlay
                    val centre = lerp(from.center, stage.center, t) - overlay.topLeft
                    val scale = (from.width + (endPx - from.width) * t) / endPx
                    scaleX = scale
                    scaleY = scale
                    translationX = centre.x - endPx / 2
                    translationY = centre.y - endPx / 2
                }
                .pointerInput(state) { detectTapGestures { state.handOver() } },
        )
    }
}
