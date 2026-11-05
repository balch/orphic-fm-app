package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import org.balch.orpheus.core.onboarding.OnboardingSequencer
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.playback.VibeMove

/**
 * What moves a play/pause dome besides a finger: the navigator's vibe moves, which it rolls with,
 * and the "swipe me" wiggle, once a launch until a swipe has ever committed. Provided once at the
 * app root, so every chrome's dome finds it wherever that dome lives. Main thread only.
 */
@Stable
internal class VibeDomeCues(
    /** Each vibe change the navigator accepts, as its transition starts. */
    val moves: Flow<VibeMove>,
    private val requestSwipe: (next: Boolean) -> Unit,
    // The wiggle is one onboarding step among others, which gives up a launch another has taken.
    private val onboarding: OnboardingSequencer,
) {
    /** Waits for the hint ahead of the wiggle to decide; false if there's no wiggle this launch. */
    suspend fun wiggleDue(): Boolean = onboarding.awaitTurn(DomeSwipeStep) != null

    /** Runs [wiggle] on the one dome that gets there, unless a touch or a swipe came first. */
    suspend fun takeWiggle(wiggle: suspend () -> Unit) {
        onboarding.run(DomeSwipeStep) { wiggle() }
    }

    /** A touch or a key on a dome: no wiggle this launch. */
    fun skipWiggle() = onboarding.skip(DomeSwipeStep)

    /**
     * A committed swipe's vibe, asked for as the dome's own: its move comes back marked so and rolls
     * nothing more. The first one is remembered for good.
     */
    fun swipe(next: Boolean) {
        requestSwipe(next)
        swipeLearned()
    }

    /**
     * The gesture is known: a committed swipe, or TalkBack's "Next vibe" and "Previous vibe", which
     * stand in for it. No wiggle now, and none on a later launch.
     */
    fun swipeLearned() = onboarding.learn(DomeSwipeStep)
}

/** Unprovided in previews and most tests: there a dome moves only under a finger, and a swipe calls its onNext. */
internal val LocalVibeDomeCues = staticCompositionLocalOf<VibeDomeCues?> { null }

@Composable
internal fun rememberVibeDomeCues(pulsarFeature: PulsarFeature, onboarding: OnboardingSequencer): VibeDomeCues =
    remember(pulsarFeature, onboarding) {
        VibeDomeCues(pulsarFeature.vibeMoves, pulsarFeature.actions.swipeVibe, onboarding)
    }
