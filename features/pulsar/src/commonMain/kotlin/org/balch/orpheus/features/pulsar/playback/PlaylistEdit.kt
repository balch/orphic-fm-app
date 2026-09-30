package org.balch.orpheus.features.pulsar.playback

import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import org.balch.orpheus.features.pulsar.models.Album
import kotlin.random.Random

/** One change the playlist sheet makes to the saved rotation. */
sealed interface PlaylistEdit {
    /** Moves [name] to just after [after] in the full order: a drop in the rotated "Up next" list. */
    data class PlaceAfter(val name: String, val after: String) : PlaylistEdit

    /** Moves [name] to just before [before]: a top drop while the playing vibe has no slot of its own. */
    data class PlaceBefore(val name: String, val before: String) : PlaylistEdit

    data class SetIncluded(val name: String, val included: Boolean) : PlaylistEdit

    /** Scrambles the order. Paused, the feature also picks the new first of Up next to replace the playing vibe. */
    data object Shuffle : PlaylistEdit

    /**
     * Brings [album]'s vibes, in track order and none set aside, to play right after [now]. The
     * feature sets [andPick] while paused: the album's first vibe is about to replace [now].
     */
    data class QueueAlbum(val album: Album, val now: String, val andPick: Boolean = false) : PlaylistEdit

    data object Reset : PlaylistEdit
}

/**
 * [prefs] after [edit], merged with [catalog] first so an edit never loses a vibe. Setting aside the
 * last playing vibe is refused, since the rotation would fall back to playing everything.
 * [albumTracks] lists an album's vibes in track order (see [albumListing]).
 */
fun applyPlaylistEdit(
    prefs: VibePlaylistPrefs?,
    catalog: List<String>,
    albumTracks: (Album) -> List<String>,
    edit: PlaylistEdit,
    random: Random = Random,
): VibePlaylistPrefs? {
    val rotation = rotationOf(catalog, prefs)
    val base = (prefs ?: VibePlaylistPrefs()).copy(order = rotation.order)
    return when (edit) {
        is PlaylistEdit.PlaceAfter -> base.copy(order = moveNextTo(base.order, edit.name, edit.after, after = true))
        is PlaylistEdit.PlaceBefore -> base.copy(order = moveNextTo(base.order, edit.name, edit.before, after = false))
        is PlaylistEdit.SetIncluded -> when {
            edit.included -> base.copy(removed = base.removed - edit.name)
            rotation.playing == listOf(edit.name) -> prefs
            else -> base.copy(removed = base.removed + edit.name)
        }
        PlaylistEdit.Shuffle -> base.copy(order = base.order.shuffled(random))
        is PlaylistEdit.QueueAlbum -> {
            val known = base.order.toSet()
            val album = albumTracks(edit.album).filter { it in known }.distinct()
            if (album.isEmpty()) return prefs
            base.copy(order = queueAlbum(base.order, album, edit.now, edit.andPick), removed = base.removed - album.toSet())
        }
        PlaylistEdit.Reset -> null
    }
}

// The album goes in right after NOW's slot, or at it when NOW belongs to the album; first with no slot.
private fun queueAlbum(order: List<String>, album: List<String>, now: String, andPick: Boolean): List<String> {
    val inAlbum = album.toSet()
    val slot = order.indexOf(now)
    val at = if (slot < 0) 0 else order.take(slot).count { it !in inAlbum } + if (now in inAlbum) 0 else 1
    // Playing on, NOW keeps its song and the album continues from it, wrapping.
    val block = if (now in inAlbum && !andPick) album.indexOf(now).let { album.drop(it) + album.take(it) } else album
    val rest = order.filterNot { it in inAlbum }
    return rest.take(at) + block + rest.drop(at)
}

private fun moveNextTo(order: List<String>, name: String, anchor: String, after: Boolean): List<String> {
    if (name == anchor || name !in order || anchor !in order) return order
    val rest = order - name
    val at = rest.indexOf(anchor) + if (after) 1 else 0
    return rest.take(at) + name + rest.drop(at)
}
