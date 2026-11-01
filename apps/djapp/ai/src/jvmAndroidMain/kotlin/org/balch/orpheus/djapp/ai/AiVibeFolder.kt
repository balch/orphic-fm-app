package org.balch.orpheus.djapp.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.ai.AiVibeArchive
import org.balch.orpheus.features.ai.NoOpAiVibeArchive
import org.balch.orpheus.features.ai.aiVibeFileName
import org.balch.orpheus.features.ai.tools.decodeVibe
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.playback.NoSavedVibes
import org.balch.orpheus.features.pulsar.playback.SavedVibe
import org.balch.orpheus.features.pulsar.playback.SavedVibeLibrary

/**
 * The AI edition's vibe archive and saved-vibe list over one folder, `ai-vibes` in [OrphicFiles.root]
 * (desktop `~/.config/orpheus-dj/ai-vibes/`, Android `filesDir/ai-vibes/`). Every apply writes its own
 * `<millis>_<name>.json`; the list is re-read after each write and delete. A delete moves the files into
 * `deleted/`, which is never listed, so they can be recovered by hand.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AiVibeArchive>(), replaces = [NoOpAiVibeArchive::class])
@ContributesBinding(AppScope::class, binding = binding<SavedVibeLibrary>(), replaces = [NoSavedVibes::class])
class AiVibeFolder internal constructor(
    private val dir: File,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : AiVibeArchive, SavedVibeLibrary {

    @Inject
    constructor(files: OrphicFiles, dispatchers: DispatcherProvider, scope: AppCoroutineScope) :
        this(File(files.root, ARCHIVE_DIR), dispatchers.io, scope)

    private val log = logging("AiVibeFolder")
    private val flow = MutableStateFlow<List<SavedVibe>>(emptyList())
    override val saved: StateFlow<List<SavedVibe>> = flow.asStateFlow()
    private val lock = Mutex()
    // File name to its vibe, or null when it does not decode. An archived file is never rewritten.
    private val decoded = mutableMapOf<String, Vibe?>()

    init {
        scope.launch(io) { refresh() }
    }

    override suspend fun archive(vibeName: String, vibeJson: String): Boolean {
        // Under the lock, so a scan never reads a file half written.
        val written = lock.withLock {
            withContext(io) {
                try {
                    dir.mkdirs()
                    val file = File(dir, aiVibeFileName(vibeName, now()))
                    file.writeText(vibeJson)
                    log.debug { "Archived AI vibe '$vibeName' -> ${file.absolutePath}" }
                    true
                } catch (e: Exception) {
                    log.error { "Failed to archive AI vibe '$vibeName': ${e.message}" }
                    false
                }
            }
        }
        if (written) refresh()
        return written
    }

    override suspend fun delete(name: String) {
        lock.withLock {
            withContext(io) {
                val key = keyOf(name)
                val trash = File(dir, DELETED_DIR)
                scan().filter { keyOf(it.vibe.name) == key }.forEach { entry ->
                    trash.mkdirs()
                    val moved = entry.file.renameTo(File(trash, entry.file.name))
                    if (moved) decoded.remove(entry.file.name)
                    else log.error { "Could not move ${entry.file.name} to $DELETED_DIR" }
                }
            }
        }
        refresh()
    }

    /** Re-reads the folder into [saved]: one row per name, the newest save, newest first. */
    internal suspend fun refresh() = lock.withLock {
        flow.value = withContext(io) {
            scan().groupBy { keyOf(it.vibe.name) }.values
                .map { saves -> saves.maxBy { it.savedAt } }
                .sortedByDescending { it.savedAt }
                .map { SavedVibe(it.vibe.name, it.savedAt, it.vibe) }
        }
    }

    private class Entry(val file: File, val savedAt: Long, val vibe: Vibe)

    // Top-level json files only, so deleted/ stays out. Call under the lock on io.
    private fun scan(): List<Entry> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            val vibe = decoded.getOrPut(file.name) { read(file) } ?: return@mapNotNull null
            Entry(file, file.name.substringBefore('_').toLongOrNull() ?: file.lastModified(), vibe)
        }
    }

    private fun read(file: File): Vibe? {
        val text = runCatching { file.readText() }.getOrElse {
            log.debug { "Skipped AI vibe ${file.name}: ${it.message}" }
            return null
        }
        return decodeVibe(vibeApplyJson, text)
            .onFailure { log.debug { "Skipped AI vibe ${file.name}: ${it.message}" } }
            .getOrNull()
    }

    private fun keyOf(name: String) = name.trim().lowercase()

    private companion object {
        const val ARCHIVE_DIR = "ai-vibes"
        const val DELETED_DIR = "deleted"
    }
}
