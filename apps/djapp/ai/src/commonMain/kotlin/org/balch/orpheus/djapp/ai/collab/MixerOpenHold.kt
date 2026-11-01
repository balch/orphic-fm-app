package org.balch.orpheus.djapp.ai.collab

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.features.pulsar.playback.SongEndHold

/** Loops the playing vibe while the Mixer is open, so a song end restarts it from the intro instead of advancing mid-mix. */
@ContributesIntoSet(AppScope::class, binding = binding<SongEndHold>())
@Inject
class MixerOpenHold(private val collab: MixCollab) : SongEndHold {
    override fun holdsSong(): Boolean = collab.mixerVisible.value
}
