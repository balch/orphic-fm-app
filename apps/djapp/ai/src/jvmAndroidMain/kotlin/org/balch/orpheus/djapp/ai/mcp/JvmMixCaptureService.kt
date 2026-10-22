package org.balch.orpheus.djapp.ai.mcp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.djapp.ai.OrphicFiles
import org.balch.orpheus.features.ai.AiVibeArchive
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService
import org.balch.orpheus.features.pulsar.bench.capture
import org.balch.orpheus.features.pulsar.bench.withCapture
import org.balch.orpheus.features.pulsar.models.Vibe

/**
 * Capture files go to [OrphicFiles.mixCaptures] (desktop `~/.config/orpheus-dj/mix-captures/`). With a repo checkout
 * (`orpheus.dsp.sourceDir`, set by the Gradle run task) they also name the vibe's source and its hash.
 * One per app, so the bench page, MCP and the in-app agent share the stale baseline and the capture lock.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [UnavailableMixCaptureService::class])
@Inject
class JvmMixCaptureService(
    pulsarFeatureProvider: () -> PulsarFeature,
    controllerProvider: () -> MixBenchController,
    archive: AiVibeArchive,
    dispatchers: DispatcherProvider,
    files: OrphicFiles,
) : MixCaptureService by MixCaptureFlow(
    pulsarFeatureProvider,
    controllerProvider,
    archive,
    dispatchers,
    CaptureWriter(files.mixCaptures, repoRoot()),
    CoroutineScope(SupervisorJob() + dispatchers.io),
)

/** `<repo>/liborpheus_dsp` from the run task, so its parent is the checkout; null when unset, as on Android. */
internal fun repoRoot(dspDir: String? = System.getProperty("orpheus.dsp.sourceDir")): File? =
    dspDir?.let { File(it).absoluteFile.parentFile }?.takeIf { File(it, "features/pulsar").isDirectory }

/**
 * The capture flow behind [JvmMixCaptureService]: rows from the bench layers, a file through [writer], and for
 * an AI vibe the rows written into the vibe, archived and re-applied. One capture runs at a time.
 * [scope] runs the load follower that records each vibe's source hash; null leaves it to [recordLoad].
 */
internal class MixCaptureFlow(
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val controllerProvider: () -> MixBenchController,
    private val archive: AiVibeArchive,
    private val dispatchers: DispatcherProvider,
    private val writer: CaptureWriter,
    scope: CoroutineScope? = null,
) : MixCaptureService {
    private val serial = Mutex()
    // Per vibe name, the source hash at its first load this run (null: no source). Guarded by itself.
    private val baselines = mutableMapOf<String, String?>()

    init {
        // The provider is called here, after construction, so building the service never touches the feature graph.
        scope?.launch {
            val pulsar = pulsarFeatureProvider()
            pulsar.vibeLoadCount.collect { recordLoad(pulsar.vibeFlow.value.name) }
        }
    }

    /** Records [name]'s source hash the first time it loads; a reload keeps it, as the bench page does. */
    suspend fun recordLoad(name: String) {
        if (synchronized(baselines) { name in baselines }) return
        val hash = withContext(dispatchers.io) { writer.sourceHash(name) }
        synchronized(baselines) { if (name !in baselines) baselines[name] = hash }
    }

    override fun baselineHash(vibeName: String): String? = synchronized(baselines) { baselines[vibeName] }

    override suspend fun capture(): CaptureOutcome = serial.withLock {
        val pulsar = pulsarFeatureProvider()
        val snap = controllerProvider().captureSnapshot()
            ?: return@withLock CaptureOutcome(false, "A vibe is loading; capture again once it plays.", null, emptyList(), null, false, false)
        val vibe = snap.vibe
        val rows = capture(vibe, snap.layers)
        val ai = vibe.name !in pulsar.vibeNames
        val written = withContext(dispatchers.io) {
            writer.write(vibe, rows, if (ai) null else baselineHash(vibe.name), aiVibe = ai)
        }
        fun outcome(ok: Boolean, message: String, applied: Boolean = false) =
            CaptureOutcome(ok, message + clampNote(rows), written.file.absolutePath, rows, written.capture.stale, ai, applied)

        if (!ai) return@withLock outcome(true, "Wrote ${rows.size} rows for \"${vibe.name}\"; the live mix is unchanged.")
        if (rows.isEmpty()) return@withLock outcome(true, "No mix edits to capture; the AI vibe \"${vibe.name}\" is unchanged.")
        val updated = vibe.withCapture(rows)
        if (pulsar.vibeLoadCount.value != snap.loadCount) return@withLock outcome(false, changed(vibe, pulsar, archived = false))
        val archived = archive.archive(updated.name, vibeApplyJson.encodeToString(Vibe.serializer(), updated))
        // Loads run on main, so checking there right before the apply can't miss one.
        val applied = withContext(dispatchers.main) {
            (pulsar.vibeLoadCount.value == snap.loadCount).also { if (it) pulsar.applyVibe(updated) }
        }
        if (!applied) return@withLock outcome(false, changed(vibe, pulsar, archived))
        outcome(
            ok = true,
            message = "Wrote ${rows.size} rows into the AI vibe \"${vibe.name}\" and re-applied it: the song restarts, " +
                "and mutes, solos, the section loop and the mix edits clear, since the vibe now holds them. " +
                if (archived) "The updated vibe is archived in ai-vibes." else NOT_ARCHIVED,
            applied = true,
        )
    }

    private fun changed(vibe: Vibe, pulsar: PulsarFeature, archived: Boolean): String =
        "The vibe changed while capturing (now \"${pulsar.vibeFlow.value.name}\"), so the rows were not applied " +
            "to the AI vibe \"${vibe.name}\"; the capture file has them." +
            if (archived) " The ai-vibes archive also got the updated vibe, but the app is not playing it." else ""

    private companion object {
        const val NOT_ARCHIVED = "The ai-vibes archive was not written (failed, or this platform has none); the capture file has the full change."

        /** Names each volume row written quieter than heard, since a vibe volume stops at 1.0. */
        fun clampNote(rows: List<CaptureRow>): String {
            val clamped = rows.filter { it.clampedDb > 0f }
            if (clamped.isEmpty()) return ""
            return " Written quieter than heard (volume stops at 1.0): " + clamped.joinToString("; ") { r ->
                val where = if (r.scope == Scope.SECTION) "section \"${r.sectionName}\"" else "whole vibe"
                "track ${r.track} ${r.field.name} ($where) by ${"%.1f".format(r.clampedDb)} dB"
            } + "."
        }
    }
}
