package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.VibeName

/**
 * The albums: the order they come in, which vibes each holds and in what track order. It is the
 * apps' only source of album membership (chips, queue order, badges, Android Auto, the media
 * subtitle and art); [Album] stays the identity (title, art, Android Auto ids).
 *
 * A new vibe goes on its album in [Default], unless it belongs on STEALTH: that album takes every
 * [VibeCatalog] entry no other album lists, so it needs no line here. `AlbumCatalogTest` fails on
 * a listed name that is not a [VibeCatalog] entry. Tests and ViewModels can pass their own listing.
 *
 * @param listed the albums as written: declaration order is album order, list order is track order.
 * @param catchAll an album and the vibes it may hold. Each of them that [listed] leaves off goes on
 *   that album, in the order given: after its listed tracks if [listed] has it, else on a new
 *   album added last.
 */
class AlbumCatalog(
    listed: List<Pair<Album, List<VibeName>>>,
    catchAll: Pair<Album, Collection<VibeName>>? = null,
) {

    /** [listed] plus the catch-all's leftovers: the albums in order, each with its tracks in order. */
    val listing: List<Pair<Album, List<VibeName>>> = if (catchAll == null) {
        listed
    } else {
        val (album, candidates) = catchAll
        val onAnAlbum = listed.flatMapTo(HashSet()) { it.second }
        val leftovers = candidates.filterNot { it in onAnAlbum }
        val at = listed.indexOfFirst { it.first == album }
        if (at >= 0) {
            listed.mapIndexed { i, (listedAlbum, names) -> listedAlbum to if (i == at) names + leftovers else names }
        } else {
            listed + (album to leftovers)
        }
    }

    private val tracksByAlbum: Map<Album, List<String>> =
        listing.associate { (album, names) -> album to names.map { it.value } }

    private val albumByName: Map<String, Album> = buildMap {
        listing.forEach { (album, names) -> names.forEach { getOrPut(it.value) { album } } }
    }

    /** The albums in order. */
    val albums: List<Album> = listing.map { it.first }.distinct()

    /** [album]'s vibes in track order; empty for an album with none. */
    fun tracks(album: Album): List<String> = tracksByAlbum[album].orEmpty()

    /** The album [name] is on, or null for a vibe on none (an AI's). */
    fun albumOf(name: String): Album? = albumByName[name]

    companion object {
        /** The albums as the apps ship them. */
        val Default = AlbumCatalog(
            // Declaration order is album order; each album's list order is its track order.
            listed = listOf(
                Album.ZERO_TO_ONE to listOf(
                    VibeNames.DOG_HOUSE,
                    VibeNames.FIRE_SKY_05F,
                    VibeNames.VELVET_LEASH,
                    VibeNames.FILTER_FUNK
                ),
                Album.RIF to listOf(
                    VibeNames.BELL_TOLLS,
                    VibeNames.TECHNO_WOBBLE,
                    VibeNames.FIRE_SKY,
                    VibeNames.SPACE_AND_DRUMS,
                    VibeNames.VOLTAGE_STRUT,
                ),
                Album.ANOMALIES to listOf(
                    VibeNames.RUST_BELT,
                    VibeNames.LOST_IN_SPACE,
                    VibeNames.STAY_ASLEEP,
                ),
            ),
            // STEALTH holds every other cataloged vibe in VibeCatalog order; list one under STEALTH to put it first.
            catchAll = Album.STEALTH to VibeCatalog.entries.keys,
        )
    }
}
