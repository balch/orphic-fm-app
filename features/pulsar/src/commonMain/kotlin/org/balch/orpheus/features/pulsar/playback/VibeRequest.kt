package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.features.pulsar.models.Vibe

/** A user's ask to change the playing vibe. [VibeNavigator] turns it into a transition. */
sealed interface VibeRequest {
    data object Next : VibeRequest
    data object Previous : VibeRequest
    /** A pick from a list (VIBE chip, TV picker, Android Auto). */
    data class Pick(val vibe: Vibe) : VibeRequest

    /**
     * A committed swipe on the play/pause dome: [Next] when [next], else [Previous]. Its move is
     * the dome's own, so the dome, which already rolled on release, doesn't roll for it again.
     */
    data class DomeSwipe(val next: Boolean) : VibeRequest
}
