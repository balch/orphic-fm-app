package org.balch.orpheus.djapp.ai.collab

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.Vibe

/** A vibe's History as saved: [vibeHash] is the vibe content it was made on, so an edited vibe starts clean. */
@Serializable
data class StoredChangeSet(
    val vibe: String,
    val vibeHash: String,
    val items: List<HistoryItem>,
    val nextId: Int,
    val pointCount: Int,
)

/** Each vibe's Mixer History, kept across vibe switches and app launches. */
interface MixChangeStore {
    /** The change set saved for [vibe], or null; one saved for different vibe content is deleted and null returned. */
    fun get(vibe: Vibe): MixHistory?

    /** Saves [history] for [vibe]; an empty history deletes the entry. Writes happen off the caller's thread, in order. */
    fun put(vibe: Vibe, history: MixHistory)

    fun remove(vibeName: String)
}

/** Default where nothing is saved (iOS): each load starts an empty History, as before. */
@ContributesBinding(AppScope::class)
@Inject
class NoMixChangeStore : MixChangeStore {
    override fun get(vibe: Vibe): MixHistory? = null
    override fun put(vibe: Vibe, history: MixHistory) {}
    override fun remove(vibeName: String) {}
}
