package org.balch.orpheus.djapp.ai.songref

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.DispatcherProvider

/** Reads small text and HTML files by extension, so an agent can't point the tool at arbitrary files. */
@ContributesBinding(AppScope::class, replaces = [UnavailableSongFileReader::class])
@Inject
class JvmSongFileReader(private val dispatchers: DispatcherProvider) : SongFileReader {
    override suspend fun read(path: String): String = withContext(dispatchers.io) {
        val home = System.getProperty("user.home").orEmpty()
        val expanded = when {
            path == "~" -> home
            path.startsWith("~/") -> home + path.substring(1)
            else -> path
        }
        // Extension first, with no filesystem access, so a refusal doesn't reveal whether the file exists.
        refuseUnlessAllowed(path, File(expanded))
        val file = File(expanded).canonicalFile
        refuseUnlessAllowed(path, file)
        require(file.isFile) { "No readable file at $path." }
        require(file.length() <= MAX_BYTES) { "$path is over 4 MB." }
        file.readText(Charsets.UTF_8)
    }

    private fun refuseUnlessAllowed(path: String, file: File) =
        require(file.extension.lowercase() in EXTENSIONS) {
            "Can't read $path: only ${EXTENSIONS.joinToString(" ")} files are allowed."
        }

    private companion object {
        val EXTENSIONS = setOf("html", "htm", "txt", "tab", "crd", "chopro", "pro", "text", "md")
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
