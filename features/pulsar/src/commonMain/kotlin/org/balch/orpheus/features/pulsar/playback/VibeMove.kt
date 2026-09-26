package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A vibe change [VibeNavigator] accepted, announced as its transition starts. A vibe applied
 * straight to the feature (an AI's, with no transition) never passes the navigator, so it has none.
 */
data class VibeMove(val kind: VibeMoveKind, val origin: VibeMoveOrigin)

enum class VibeMoveKind {
    /** A step on, an advance at a song's end included. */
    Next,

    /** A step back. */
    Previous,

    /** ◀ late in a song: the playing vibe again from its top. */
    Restart,

    /** A vibe chosen from a list. */
    Pick,
}

enum class VibeMoveOrigin {
    /** A committed swipe on the play/pause dome, which rolled for it as the finger let go. */
    DomeSwipe,

    /** Any other ask: buttons, tiles, keys, media keys, the widget, a list. */
    Request,

    /** The song ended and the navigator moved on by itself. */
    Advance,
}

/** No moves ever: the stub [org.balch.orpheus.features.pulsar.PulsarFeature.vibeMoves] previews and fakes share. */
internal val NoVibeMoves: SharedFlow<VibeMove> = MutableSharedFlow<VibeMove>().asSharedFlow()
