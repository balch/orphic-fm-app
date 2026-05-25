package org.balch.orpheus.core.audio

import kotlinx.serialization.Serializable

/**
 * Catalog of song-to-song transition styles. See `TransitionSpec` for parameters.
 * The behavior of each style is implemented in `PulsarTransitionRunner`.
 *
 * @property isSafe Whether this style is included in the random-pick pool when
 *   `TransitionSpec.randomPool` is empty. RANDOM is excluded to prevent recursion.
 * @property canHandoff Whether `TransitionSpec.handoffMs` applies to this style.
 *   Drives the enable/disable state of the handoff knob in the settings sheet.
 *   False for styles whose timeline doesn't accept a tunable duration (CUT is
 *   instant; RANDOM defers to the picked substyle).
 * @property isVisible Whether this style appears in the user-facing picker.
 *   Defaults to [isSafe] — styles excluded from the random pool are typically
 *   also hidden from the manual picker, with RANDOM as the deliberate exception.
 *
 * - [CUT] — instant swap. No fade, no gap. Audible click on percussive tails.
 * - [GAP] — silent gap between songs. Default 500ms.
 * - [FADE] — symmetric fade-out → swap → fade-in. Default 350ms total.
 * - [CROSSFADE] — single-voice emulated crossfade; outgoing reverb tail provides overlap.
 * - [TAPE] — tape-stop varispeed on master, then quick fade-in for incoming.
 * - [SCRATCH] — beat-synced stutter gate over the song boundary.
 * - [FILTER] — 4-stage allpass filter sweep with LFO.
 * - [RANDOM] — pick a safe substyle (any style with `isSafe = true`) when
 *   `TransitionSpec.randomPool` is empty; otherwise pick from the explicit pool.
 */
@Serializable
enum class TransitionStyle(
    val isSafe: Boolean,
    val canHandoff: Boolean = true,
    val isVisible: Boolean = isSafe,
) {
    CUT(isSafe = true, canHandoff = false),
    GAP(isSafe = true),
    FADE(isSafe = true),
    CROSSFADE(isSafe = true),
    TAPE(isSafe = true),
    SCRATCH(isSafe = true),
    FILTER(isSafe = true),
    RANDOM(isSafe = false, isVisible = true, canHandoff = false), ;

    companion object {
        val default: TransitionSpec = TransitionSpec(TAPE, handoffMs = 1000)
    }
}
