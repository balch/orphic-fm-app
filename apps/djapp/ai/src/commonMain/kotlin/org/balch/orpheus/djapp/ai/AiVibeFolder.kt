package org.balch.orpheus.djapp.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.time.Clock
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
 * The `ai-vibes` folder as [AiVibeFolder] needs it, one per platform: desktop `~/.config/orpheus-dj/ai-vibes/`,
 * Android `filesDir/ai-vibes/`, iOS `Application Support/ai-vibes/`. Calls are blocking and come on io.
 */
interface AiVibeDir {
    /** Names of the top-level `.json` files; `deleted/` is never among them. */
    fun jsonFiles(): List<String>

    /** The file's text, or null when it cannot be read. */
    fun read(name: String): String?

    /** Creates the folder if needed; throws when the file cannot be written. */
    fun write(name: String, text: String)

    /** Moves the file into `deleted/`; false when it could not. */
    fun moveToDeleted(name: String): Boolean

    /** Last-modified time in epoch millis, for a file whose name carries no timestamp. */
    fun modifiedAt(name: String): Long
}

/**
 * The AI edition's vibe archive and saved-vibe list over one [AiVibeDir]. Every apply writes its own
 * `<millis>_<name>.json`; the list is re-read after each write and delete. A delete moves the files into
 * `deleted/`, which is never listed, so they can be recovered by hand.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AiVibeArchive>(), replaces = [NoOpAiVibeArchive::class])
@ContributesBinding(AppScope::class, binding = binding<SavedVibeLibrary>(), replaces = [NoSavedVibes::class])
class AiVibeFolder internal constructor(
    private val dir: AiVibeDir,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AiVibeArchive, SavedVibeLibrary {

    @Inject
    constructor(dir: AiVibeDir, dispatchers: DispatcherProvider, scope: AppCoroutineScope) :
        this(dir, dispatchers.io, scope)

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
                    val name = aiVibeFileName(vibeName, now())
                    dir.write(name, vibeJson)
                    log.debug { "Archived AI vibe '$vibeName' -> $name" }
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
                scan().filter { keyOf(it.vibe.name) == key }.forEach { entry ->
                    if (dir.moveToDeleted(entry.file)) decoded.remove(entry.file)
                    else log.error { "Could not move ${entry.file} to deleted/" }
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

    private class Entry(val file: String, val savedAt: Long, val vibe: Vibe)

    // Call under the lock on io.
    private fun scan(): List<Entry> = dir.jsonFiles().mapNotNull { file ->
        val vibe = decoded.getOrPut(file) { read(file) } ?: return@mapNotNull null
        Entry(file, file.substringBefore('_').toLongOrNull() ?: dir.modifiedAt(file), vibe)
    }

    private fun read(file: String): Vibe? {
        val text = dir.read(file) ?: run {
            log.debug { "Skipped AI vibe $file: unreadable" }
            return null
        }
        return decodeVibe(vibeApplyJson, text)
            .onFailure { log.debug { "Skipped AI vibe $file: ${it.message}" } }
            .getOrNull()
    }

    private fun keyOf(name: String) = name.trim().lowercase()

    internal companion object {
        const val ARCHIVE_DIR = "ai-vibes"
        const val DELETED_DIR = "deleted"
    }
}
