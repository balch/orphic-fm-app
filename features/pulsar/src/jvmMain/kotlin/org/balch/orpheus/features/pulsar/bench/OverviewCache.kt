package org.balch.orpheus.features.pulsar.bench

import com.diamondedge.logging.logging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OverviewSection(val index: Int, val name: String, @SerialName("start_s") val startS: Float)

/** Whole-song overview: [env] is mid-signal (min, max) per 100 ms of the OUT signal. */
@Serializable
data class Overview(val seconds: Int, val sections: List<OverviewSection>, val env: List<List<Float>>)

/**
 * Renders a vibe's overview with scripts/mix/measure.sh (VIBE_OVERVIEW=1) and caches it as
 * build/mix-bench/overview/<slug>-<hash>.overview.json; the script's untagged output is renamed to add the hash.
 * [launch] runs the command from [repoRoot] and returns the exit code; renders are serialised by a mutex.
 * The default launcher gives up after [timeoutMs] and kills the process tree on timeout or cancellation.
 */
class OverviewCache(
    private val repoRoot: File,
    private val timeoutMs: Long = 5 * 60_000L,
    private val launch: (List<String>) -> Int = { cmd -> defaultLaunch(repoRoot, cmd, timeoutMs) },
) {
    private val renderLock = Mutex()
    private val log = logging("OverviewCache")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(vibeName: String, sourceHash: String): Overview? = withContext(Dispatchers.IO) {
        try {
            val slug = vibeName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            val dir = File(repoRoot, "build/mix-bench/overview")
            val cached = File(dir, "$slug-$sourceHash.overview.json")
            parse(cached)?.let { return@withContext it }
            renderLock.withLock {
                parse(cached)?.let { return@withLock it }
                dir.mkdirs()
                val raw = File(dir, "$slug.overview.json")
                raw.delete()
                // Without NO_DUMP the script re-dumps the ports, since the vibe file may have changed.
                // The script's content-changing VIBE_* knobs are cleared so the user's shell cannot leak in.
                val code = runInterruptible { launch(command(vibeName)) }
                if (code != 0 || !raw.exists()) {
                    log.warn { "overview render failed for $vibeName (exit $code)" }
                    return@withLock null
                }
                val tmp = File(dir, "$slug-$sourceHash.overview.json.tmp")
                raw.copyTo(tmp, overwrite = true)
                Files.move(tmp.toPath(), cached.toPath(), StandardCopyOption.ATOMIC_MOVE)
                raw.delete()
                parse(cached)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "overview for $vibeName failed: ${e.message}" }
            null
        }
    }

    /** Parses [f], deleting it when it is corrupt so the next call re-renders. */
    private fun parse(f: File): Overview? {
        if (!f.exists()) return null
        return try {
            json.decodeFromString<Overview>(f.readText())
        } catch (e: Exception) {
            log.warn { "dropping corrupt overview ${f.name}: ${e.message}" }
            f.delete()
            null
        }
    }

    private fun command(vibeName: String) =
        listOf("env") + CLEARED.flatMap { listOf("-u", it) } +
            listOf("VIBE_OVERVIEW=1", "VIBE_MEASURE_DIR=build/mix-bench/overview", "scripts/mix/measure.sh", vibeName)

    private companion object {
        val CLEARED = listOf(
            "VIBE_TAG", "VIBE_SET", "VIBE_TRIM", "VIBE_DIST", "VIBE_SECONDS", "VIBE_OUTRO_AT", "VIBE_WAV",
            "VIBE_MASTER", "VIBE_SR", "VIBE_BLOCK", "VIBE_PORTS", "NO_DUMP",
        )

        fun defaultLaunch(root: File, cmd: List<String>, timeoutMs: Long): Int {
            val p = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return p.exitValue()
                return -1
            } finally {
                if (p.isAlive) {
                    p.descendants().forEach { it.destroyForcibly() }
                    p.destroyForcibly()
                }
            }
        }
    }
}
