package org.balch.orpheus.features.pulsar.playback

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.preferences.VibePlaylistPrefs

/**
 * What Next, Previous, song-end and the "Up next" chrome step through. [order] is every known vibe
 * in the user's order; [playing] is the part of it that plays, in the same order.
 */
@Immutable
data class VibeRotation(val order: List<String>, val playing: List<String>) {
    companion object {
        val EMPTY = VibeRotation(emptyList(), emptyList())

        /** Catalog order with everything playing: the rotation before any edit. */
        fun of(catalog: List<String>) = VibeRotation(catalog, catalog)
    }
}

/**
 * Merges [prefs] with [catalog]: unknown names drop out and new catalog names join at the end.
 * Removing every vibe is ignored, so something always plays.
 */
fun rotationOf(catalog: List<String>, prefs: VibePlaylistPrefs?): VibeRotation {
    if (prefs == null) return VibeRotation.of(catalog)
    val known = catalog.toSet()
    val saved = prefs.order.filter { it in known }.distinct()
    val savedSet = saved.toSet()
    val order = saved + catalog.filterNot { it in savedSet }
    return VibeRotation(order, order.filterNot { it in prefs.removed }.ifEmpty { order })
}

/**
 * The vibe one [step] (+1 or -1) from [current]. A vibe outside [VibeRotation.playing] steps from
 * its slot in the full order; one outside that too (an AI vibe) lands on the first or the last.
 */
fun stepInRotation(rotation: VibeRotation, current: String, step: Int): String? {
    val playing = rotation.playing
    if (playing.isEmpty()) return null
    if (current in playing) return neighborVibe(playing, current, step)
    val slot = rotation.order.indexOf(current)
    if (slot < 0) return if (step >= 0) playing.first() else playing.last()
    val direction = if (step >= 0) 1 else -1
    val size = rotation.order.size
    return (1..size).asSequence()
        .map { rotation.order[(slot + direction * it).mod(size)] }
        .firstOrNull { it in playing }
}
