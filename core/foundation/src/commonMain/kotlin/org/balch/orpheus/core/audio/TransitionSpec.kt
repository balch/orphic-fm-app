package org.balch.orpheus.core.audio

import kotlinx.serialization.Serializable

/**
 * Declarative description of a song-to-song transition. Authored per-vibe
 * (via `Arrangement.transitionOut`) or set globally as a user preference
 * (via `AppPreferences.pulsarTransitionDefault`).
 *
 * The entire transition timeline lives in `PulsarTransitionRunner` — fade-out,
 * swap, fade-in (or tape-stop, etc) all happen at the section boundary.
 * There's no separate in-song outro fade.
 *
 * @param style The transition behavior. Resolves to a suspend fun on
 *   `PulsarTransitionRunner` at runtime.
 * @param handoffMs Style-dependent. Null = use the style's built-in default.
 *   - CUT: ignored
 *   - GAP: silent gap duration (default 500)
 *   - FADE: full transition duration; fade-out is H/2, fade-in is H/2 (default 350)
 *   - CROSSFADE: full overlap duration; outgoing drops to 0.5 over H/2,
 *     incoming starts at H/2, vol returns to 1.0 over the second H/2 (default 400)
 *   - TAPE: tape-stop duration (default 300); followed by 100ms fade-in
 *   - SCRATCH: scratch noise duration over the new song start (default 250)
 *   - DJ: full sweep duration (default 500); LPF closes at midpoint, opens again
 *   - RANDOM: ignored (inherits from chosen substyle)
 * @param randomPool Only consulted when `style == RANDOM`. Empty = fall back
 *   to every style with `TransitionStyle.isSafe == true`. Vibes that want
 *   to constrain the random rotation set it explicitly.
 */
@Serializable
data class TransitionSpec(
    val style: TransitionStyle,
    val handoffMs: Int? = null,
    val randomPool: List<TransitionStyle> = emptyList(),
) {
    init {
        handoffMs?.let { require(it in 0..5_000) { "handoffMs must be 0..5000, got $it" } }
        require(TransitionStyle.RANDOM !in randomPool) {
            "randomPool cannot contain RANDOM (would recurse)"
        }
    }
}
