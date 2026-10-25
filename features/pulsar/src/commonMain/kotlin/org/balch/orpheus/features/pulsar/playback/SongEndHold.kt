package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds

/**
 * Asked by [PulsarSongAdvancer] at each song end. True keeps the playing vibe: the song restarts from its
 * intro in place, with no load, so live edits a load would clear survive. The AI edition holds while its
 * Mixer has edits no capture has taken.
 */
fun interface SongEndHold {
    fun holdsSong(): Boolean
}

@ContributesTo(AppScope::class)
interface SongEndHolds {
    @Multibinds(allowEmpty = true)
    fun songEndHolds(): Set<SongEndHold>
}
