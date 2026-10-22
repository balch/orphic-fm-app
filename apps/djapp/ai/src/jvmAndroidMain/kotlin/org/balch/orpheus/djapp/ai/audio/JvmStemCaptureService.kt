package org.balch.orpheus.djapp.ai.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.dsp.StemLayout
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.djapp.ai.OrphicFiles
import org.balch.orpheus.features.pulsar.bench.StemCaptureOutcome
import org.balch.orpheus.features.pulsar.bench.StemCaptureService
import org.balch.orpheus.features.pulsar.bench.StemFile
import org.balch.orpheus.features.pulsar.bench.StemRecorder
import org.balch.orpheus.features.pulsar.bench.StemRecording
import org.balch.orpheus.features.pulsar.bench.UnavailableStemCaptureService
import org.balch.orpheus.features.pulsar.bench.analysis.AnalysisKind
import org.balch.orpheus.features.pulsar.bench.analysis.StemAnalyzer

/** Stem captures, one owner-only folder each under [OrphicFiles.stemCaptures] (desktop `~/.config/orpheus-dj/stem-captures/`). */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [UnavailableStemCaptureService::class])
@Inject
class JvmStemCaptureService(
    recorderProvider: () -> StemRecorder,
    dispatchers: DispatcherProvider,
    files: OrphicFiles,
) : StemCaptureService by StemCaptureFlow(recorderProvider, files.stemCaptures, dispatchers)

/** The manifest written beside the WAVs as `stems.json`. */
@Serializable
internal data class StemManifest(
    val vibe: String,
    val sectionAtStart: String,
    val sampleRate: Int,
    val frames: Int,
    val droppedFrames: Long,
    val stems: List<StemManifestEntry>,
)

@Serializable
internal data class StemManifestEntry(val stem: String, val label: String, val rmsDb: Float, val peakDb: Float)

/**
 * Records into a hidden partial folder under [root], writing each stem's WAV and its levels in one pass, then
 * renames it `<vibe-slug>-<yyyyMMdd-HHmmss>` and adds the manifest. A refused or failed capture leaves nothing.
 */
internal class StemCaptureFlow(
    private val recorderProvider: () -> StemRecorder,
    private val root: File,
    private val dispatchers: DispatcherProvider,
    private val openWriter: (File, Int) -> StemWavWriter = ::StemWavWriter,
) : StemCaptureService {
    override suspend fun capture(seconds: Float, stems: List<Int>, progress: suspend (Float) -> Unit): StemCaptureOutcome {
        require(stems.isNotEmpty() && stems.all { it in 0 until StemLayout.STEMS }) { "bad stems: $stems" }
        val recorder = recorderProvider()
        // A refused call touches no disk.
        recorder.refusal()?.let { return StemCaptureOutcome(false, it) }
        val stamp = LocalDateTime.now().format(STAMP)
        val writers = mutableListOf<StemWavWriter>()
        var dir: File? = null
        var kept = false
        try {
            val partial = withContext(dispatchers.io) {
                sweepStalePartials()
                ownerOnlyTempDir()
            }
            dir = partial
            // Read once: the headers and the analyzer must agree with the rate record() pins, checked below.
            val headerRate = recorder.sampleRate
            withContext(dispatchers.io) {
                for (s in stems) writers += openWriter(File(partial, "${StemLayout.NAMES[s]}.wav"), headerRate)
            }
            val levels = StemAnalyzer(headerRate, stems, setOf(AnalysisKind.LEVEL))
            val rec = recorder.record(
                seconds,
                { frames, count ->
                    levels.write(frames, count)
                    for (i in stems.indices) writers[i].write(frames, count, stems[i])
                },
                progress,
            )
            if (!rec.ok) return StemCaptureOutcome(false, rec.message, frames = rec.frames, droppedFrames = rec.droppedFrames)
            if (rec.sampleRate != headerRate) {
                return StemCaptureOutcome(false, StemRecording.RATE_CHANGED, frames = rec.frames, droppedFrames = rec.droppedFrames)
            }

            return withContext(dispatchers.io) {
                writers.forEach { it.close() }
                val final = finalDir(slug(rec.vibe), stamp)
                Files.move(partial.toPath(), final.toPath())
                dir = final
                val files = levels.results().mapIndexed { i, a ->
                    val name = StemLayout.NAMES[stems[i]]
                    StemFile(name, label(stems[i], rec), File(final, "$name.wav").absolutePath, a.rmsDb ?: SILENT_DB, a.peakDb ?: SILENT_DB)
                }
                val manifest = StemManifest(
                    vibe = rec.vibe,
                    sectionAtStart = rec.sectionAtStart,
                    sampleRate = rec.sampleRate,
                    frames = rec.frames,
                    droppedFrames = rec.droppedFrames,
                    stems = files.map { StemManifestEntry(it.stem, it.label, it.rmsDb, it.peakDb) },
                )
                File(final, MANIFEST).writeText(json.encodeToString(StemManifest.serializer(), manifest))
                kept = true
                StemCaptureOutcome(true, rec.message, final.absolutePath, files, rec.frames, rec.droppedFrames)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StemCaptureOutcome(false, "couldn't write the stems: ${e.message ?: e::class.simpleName}")
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                writers.forEach { runCatching { it.close() } }
                if (!kept) dir?.deleteRecursively()
            }
        }
    }

    /**
     * Removes partial folders a crash left behind. Only old ones: a caller racing past the refusal check makes its
     * partial folder before the recorder's lock refuses it, and a live capture (30 s at most) is never this old.
     */
    private fun sweepStalePartials() {
        val cutoff = System.currentTimeMillis() - STALE_PARTIAL_MS
        root.listFiles { f -> f.isDirectory && f.name.startsWith(PARTIAL_PREFIX) && f.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    private fun ownerOnlyTempDir(): File {
        Files.createDirectories(root.toPath())
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            val attr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            return Files.createTempDirectory(root.toPath(), PARTIAL_PREFIX, attr).toFile()
        }
        return Files.createTempDirectory(root.toPath(), PARTIAL_PREFIX).toFile().apply {
            setReadable(false, false)
            setReadable(true, true)
            setWritable(false, false)
            setWritable(true, true)
            setExecutable(false, false)
            setExecutable(true, true)
        }
    }

    /** Captures are serial (the recorder's lock), so a suffix only guards two in the same second. */
    private fun finalDir(slug: String, stamp: String): File {
        var out = File(root, "$slug-$stamp")
        var n = 2
        while (out.exists()) out = File(root, "$slug-$stamp-${n++}")
        return out
    }

    private companion object {
        const val MANIFEST = "stems.json"
        const val PARTIAL_PREFIX = ".partial-"
        const val SILENT_DB = -120f
        const val STALE_PARTIAL_MS = 10 * 60_000L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val json = Json { prettyPrint = true }

        fun slug(vibe: String) = vibe.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "vibe" }

        /** Tracks take the vibe's engine names; storm, street, speech, bus and out label themselves. */
        fun label(stem: Int, rec: StemRecording): String =
            if (stem < TRACKS) rec.trackLabels.getOrElse(stem) { StemLayout.NAMES[stem] } else StemLayout.NAMES[stem]

        const val TRACKS = 8
    }
}
