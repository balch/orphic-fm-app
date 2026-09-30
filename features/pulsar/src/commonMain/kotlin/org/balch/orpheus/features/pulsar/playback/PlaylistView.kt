package org.balch.orpheus.features.pulsar.playback

import androidx.compose.runtime.Immutable
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.vibes.AlbumCatalog

/** What the playlist sheet draws: the rotation and the album listing (see [albumListing]). */
@Immutable
data class PlaylistView(
    val rotation: VibeRotation = VibeRotation.EMPTY,
    val albums: Map<Album, List<String>> = emptyMap(),
)

/**
 * Each album holding any of [names], in [catalog]'s album order, with those vibes in its track order.
 * The one place album membership and order are decided for the chips, the queue and Android Auto.
 */
fun albumListing(names: Collection<String>, catalog: AlbumCatalog = AlbumCatalog.Default): Map<Album, List<String>> {
    val shown = names.toSet()
    return catalog.albums.associateWith { album -> catalog.tracks(album).filter { it in shown } }.filterValues { it.isNotEmpty() }
}
