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
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.CaptureOutcome
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.MixCaptureService
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.UnavailableMixCaptureService
import org.balch.orpheus.features.pulsar.bench.capture

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
    dispatchers: DispatcherProvider,
    files: OrphicFiles,
) : MixCaptureService by MixCaptureFlow(
    pulsarFeatureProvider,
    controllerProvider,
    dispatchers,
    CaptureWriter(files.mixCaptures, repoRoot()),
    CoroutineScope(SupervisorJob() + dispatchers.io),
)

/** `<repo>/liborpheus_dsp` from the run task, so its parent is the checkout; null when unset, as on Android. */
internal fun repoRoot(dspDir: String? = System.getProperty("orpheus.dsp.sourceDir")): File? =
    dspDir?.let { File(it).absoluteFile.parentFile }?.takeIf { File(it, "features/pulsar").isDirectory }

/**
 * The capture flow behind [JvmMixCaptureService]: rows from the bench layers and a file through [writer], for catalog
 * and AI vibes alike; the live mix and the vibe are untouched. One capture runs at a time.
 * [scope] runs the load follower that records each vibe's source hash; null leaves it to [recordLoad].
 */
internal class MixCaptureFlow(
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val controllerProvider: () -> MixBenchController,
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
            ?: return@withLock CaptureOutcome(false, "A vibe is loading; capture again once it plays.", null, emptyList(), null, false)
        val vibe = snap.vibe
        val rows = capture(vibe, snap.layers)
        val ai = vibe.name !in pulsar.vibeNames
        val written = withContext(dispatchers.io) {
            writer.write(vibe, rows, if (ai) null else baselineHash(vibe.name), aiVibe = ai)
        }
        CaptureOutcome(
            true, "Wrote ${rows.size} rows for \"${vibe.name}\"; the live mix is unchanged." + clampNote(rows),
            written.file.absolutePath, rows, written.capture.stale, ai,
        )
    }

    private companion object {
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
