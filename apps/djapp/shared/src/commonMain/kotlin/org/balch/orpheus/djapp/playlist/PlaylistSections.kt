package org.balch.orpheus.djapp.playlist

import androidx.compose.runtime.Immutable
import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.features.pulsar.playback.stepInRotation

/**
 * The sheet's three parts. The NOW vibe, [upNext] and [setAside] hold every vibe exactly once;
 * [setAside] is what the user removed, in saved order.
 */
@Immutable
data class PlaylistSections(val now: String, val upNext: List<String>, val setAside: List<String>)

fun playlistSections(view: PlaylistView, current: String): PlaylistSections {
    val rotation = view.rotation
    val playing = rotation.playing
    val start = stepInRotation(rotation, current, 1)
    val upNext = if (start == null) emptyList() else {
        val at = playing.indexOf(start)
        (playing.drop(at) + playing.take(at)).filter { it != current }
    }
    val playingSet = playing.toSet()
    val setAside = rotation.order.filter { it !in playingSet && it != current }
    return PlaylistSections(current, upNext, setAside)
}

/**
 * The edit for [rows] after a drag left one at [index]: after the row above it, or after [now] at the
 * top. A top drop under an AI vibe, which has no slot in [order], goes before the row below instead.
 */
fun dropEdit(rows: List<String>, index: Int, now: String, order: List<String>): PlaylistEdit? {
    val name = rows.getOrNull(index) ?: return null
    return when {
        index > 0 -> PlaylistEdit.PlaceAfter(name, rows[index - 1])
        now in order -> PlaylistEdit.PlaceAfter(name, now)
        rows.size > 1 -> PlaylistEdit.PlaceBefore(name, rows[1])
        else -> null
    }
}
