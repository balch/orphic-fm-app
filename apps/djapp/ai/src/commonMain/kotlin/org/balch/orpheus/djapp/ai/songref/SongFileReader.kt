package org.balch.orpheus.djapp.ai.songref

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Reads a song page from a file path. Throws [IllegalArgumentException] with a message fit to show the user. */
interface SongFileReader {
    suspend fun read(path: String): String
}

/** Default where files can't be read (iOS, wasm). */
@ContributesBinding(AppScope::class)
@Inject
class UnavailableSongFileReader : SongFileReader {
    override suspend fun read(path: String): String =
        throw IllegalArgumentException("Reading files isn't available here; pass the reference as text.")
}
