package org.balch.orpheus.djapp.ai.collab

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.djapp.ai.OrphicFiles
import org.balch.orpheus.features.pulsar.models.Vibe

/**
 * One `<slug>.json` per vibe in [dir] ([OrphicFiles.mixChanges]). Reads are cached per vibe name, so a name put this
 * run never reads the disk again; writes run in order on one coroutine of [scope].
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoMixChangeStore::class])
class FileMixChangeStore internal constructor(private val dir: File, scope: CoroutineScope) : MixChangeStore {
    @Inject
    constructor(files: OrphicFiles, dispatchers: DispatcherProvider) :
        this(files.mixChanges, CoroutineScope(SupervisorJob() + dispatchers.io))

    private val log = logging("FileMixChangeStore")

    private sealed interface Op {
        data class Write(val name: String, val set: StoredChangeSet) : Op
        data class Delete(val name: String) : Op
        class Flush(val done: CompletableDeferred<Unit>) : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)

    // Guarded by itself. A key mapped to null is known to have no change set.
    private val cache = mutableMapOf<String, StoredChangeSet?>()

    // Per name, the last vibe hashed and its hash, so each put does not re-encode the vibe.
    private val hashes = mutableMapOf<String, Pair<Vibe, String>>()

    init {
        scope.launch {
            for (op in ops) {
                try {
                    when (op) {
                        is Op.Write -> write(op.name, op.set)
                        is Op.Delete -> delete(op.name)
                        is Op.Flush -> op.done.complete(Unit)
                    }
                } catch (e: Exception) {
                    log.warn { "change set write failed: ${e.message}" }
                }
            }
        }
    }

    override fun get(vibe: Vibe): MixHistory? {
        val stored = synchronized(cache) { cache.getOrPut(vibe.name) { read(vibe.name) } } ?: return null
        if (stored.vibeHash != hash(vibe)) {
            remove(vibe.name)
            return null
        }
        return MixHistory(items = stored.items, nextId = stored.nextId, pointCount = stored.pointCount)
    }

    override fun put(vibe: Vibe, history: MixHistory) {
        if (history.items.isEmpty()) return remove(vibe.name)
        val set = StoredChangeSet(vibe.name, hash(vibe), history.items, history.nextId, history.pointCount)
        synchronized(cache) {
            if (cache[vibe.name] == set) return
            cache[vibe.name] = set
            ops.trySend(Op.Write(vibe.name, set))
        }
    }

    override fun remove(vibeName: String) {
        synchronized(cache) {
            if (vibeName in cache && cache[vibeName] == null) return
            cache[vibeName] = null
            ops.trySend(Op.Delete(vibeName))
        }
    }

    /** Waits until every write queued so far is on disk. */
    internal suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        ops.send(Op.Flush(done))
        done.await()
    }

    private fun hash(vibe: Vibe): String = synchronized(hashes) {
        hashes[vibe.name]?.takeIf { it.first === vibe }?.second
            ?: sha256(vibeJson.encodeToString(Vibe.serializer(), vibe)).also { hashes[vibe.name] = vibe to it }
    }

    // A file that does not parse, or that a different name's slug collides with, reads as no change set.
    private fun read(name: String): StoredChangeSet? {
        val f = file(name)
        if (!f.isFile) return null
        return try {
            json.decodeFromString(StoredChangeSet.serializer(), f.readText()).takeIf { it.vibe == name }
        } catch (e: Exception) {
            log.warn { "unreadable change set ${f.name}: ${e.message}" }
            null
        }
    }

    // Leaves a file another name's slug collides with; an unreadable one goes.
    private fun delete(name: String) {
        val f = file(name)
        if (!f.isFile) return
        val owner = runCatching { json.decodeFromString(StoredChangeSet.serializer(), f.readText()).vibe }.getOrNull()
        if (owner == null || owner == name) f.delete()
    }

    private fun write(name: String, set: StoredChangeSet) {
        dir.mkdirs()
        val out = file(name).toPath()
        val tmp = File(dir, "${out.fileName}.tmp").toPath()
        Files.write(tmp, json.encodeToString(StoredChangeSet.serializer(), set).toByteArray())
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun file(name: String) = File(dir, "${slug(name)}.json")

    private companion object {
        val json = Json { allowStructuredMapKeys = true; encodeDefaults = true; ignoreUnknownKeys = true }
        // The vibe as the AI vibe tools encode it, so the hash follows its content.
        val vibeJson = Json { encodeDefaults = true }

        // CaptureWriter's slug rule, so a vibe's change set and its captures share a stem.
        fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "vibe" }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
