package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.audio.dsp.StemLayout
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.bench.StemCaptureService
import org.balch.orpheus.features.pulsar.bench.StemFile
import org.balch.orpheus.features.pulsar.bench.StemRecorder
import org.balch.orpheus.features.pulsar.bench.analysis.AnalysisKind
import org.balch.orpheus.features.pulsar.bench.analysis.StemAnalysis
import org.balch.orpheus.features.pulsar.bench.analysis.StemAnalyzer

private const val MIN_SECONDS = 0.5f
private const val ANALYZE_MAX_SECONDS = 15
private const val CAPTURE_MAX_SECONDS = 30

private const val STEMS_DOC =
    "Stems: track0-track7 (the 8 Pulsar tracks, each after its fader and pan, before the bus), storm, street, " +
        "speech (Pulsar's storm, street-ambience and spoken-clip sources), bus (the summed mix after its 35 Hz " +
        "high-pass and drive, before the limiter) and out (the app's output before any text-to-speech announcement " +
        "is mixed in). Track stems are dry, taken before the delay and reverb sends: those returns appear only in " +
        "bus and out, so the tracks summed do not match the bus."

@LLMDescription("Arguments for audio_analyze.")
@Serializable
data class AudioAnalyzeArgs(
    @property:LLMDescription("How long to record, 0.5 to 15 seconds.")
    val seconds: Float = 5f,
    @property:LLMDescription("Stem names to analyze (track0-track7, storm, street, speech, bus, out); omit for all 13.")
    val stems: List<String>? = null,
    @property:LLMDescription("Analyses to run (level, spectrum, envelope, onsets, lowend, stereo); omit for all.")
    val kinds: List<String>? = null,
)

@LLMDescription("What audio_analyze measured.")
@Serializable
data class AudioAnalyzeResult(
    val ok: Boolean,
    val message: String,
    val vibe: String = "",
    @property:LLMDescription("The section playing when the recording started.")
    val section: String = "",
    @property:LLMDescription("Seconds recorded.")
    val seconds: Float = 0f,
    @property:LLMDescription("Frames lost because the app fell behind reading the audio; 0 normally.")
    val droppedFrames: Long = 0,
    @property:LLMDescription(
        "One entry per stem; a field is null when its kind was not asked for. dB values round to 0.1 and floor at -120. " +
            "level: rmsDb, peakDb, crestDb. spectrum: bandsHz and bandsDb, 31 third-octave bands from 20 Hz to 20 kHz, " +
            "each band's power in dBFS (a full-scale sine reads -3 in its band). envelope: envelopeDb, one RMS value per envelopeMs. " +
            "onsets: count, per second and median interval. lowend: subPct (20-40 Hz) and lowPct (40-120 Hz) shares " +
            "of the power. stereo: correlation (-1 to 1) and sideToMidDb.",
    )
    val stems: List<StemAnalysis> = emptyList(),
    @property:LLMDescription("Per track 0-7, the engine it plays, \"a / b\" when its two slots differ.")
    val trackLabels: List<String> = emptyList(),
)

@LLMDescription("Arguments for audio_capture.")
@Serializable
data class AudioCaptureArgs(
    @property:LLMDescription("How long to record, 0.5 to 30 seconds.")
    val seconds: Float = 10f,
    @property:LLMDescription("Stem names to save (track0-track7, storm, street, speech, bus, out); omit for all 13.")
    val stems: List<String>? = null,
)

@LLMDescription("What audio_capture saved.")
@Serializable
data class AudioCaptureResult(
    val ok: Boolean,
    val message: String,
    @property:LLMDescription("Absolute path of the capture folder (WAVs plus stems.json), or empty when nothing was kept.")
    val dir: String = "",
    @property:LLMDescription("Per stem: name, label (the engine for track0-7), absolute WAV path, rmsDb and peakDb.")
    val files: List<StemFile> = emptyList(),
    @property:LLMDescription("Stereo frames per file.")
    val frames: Int = 0,
    @property:LLMDescription("Frames lost because the app fell behind reading the audio; 0 normally.")
    val droppedFrames: Long = 0,
)

internal fun requireSeconds(seconds: Float, max: Int): Float =
    seconds.takeIf { it >= MIN_SECONDS && it <= max } ?: badArg("seconds must be 0.5 to $max, not $seconds")

/** Stem names to indices into [StemLayout.NAMES], in the order given; null means every stem. */
internal fun parseStems(names: List<String>?): List<Int> {
    if (names == null) return StemLayout.NAMES.indices.toList()
    if (names.isEmpty()) badArg("stems is empty; omit it for all 13")
    return names.map { n ->
        StemLayout.NAMES.indexOf(n.trim().lowercase()).takeIf { it >= 0 }
            ?: badArg("no stem \"$n\"; stems: ${StemLayout.NAMES.joinToString()}")
    }.distinct()
}

internal fun parseKinds(names: List<String>?): Set<AnalysisKind> {
    if (names == null) return AnalysisKind.entries.toSet()
    if (names.isEmpty()) badArg("kinds is empty; omit it for all")
    return names.mapTo(LinkedHashSet()) { n ->
        AnalysisKind.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) }
            ?: badArg("no kind \"$n\"; kinds: ${AnalysisKind.entries.joinToString { it.name.lowercase() }}")
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
// A provider, as in the mix tools: the recorder's controller and the feature would close a DI cycle.
class AudioAnalyzeTool(private val recorderProvider: () -> StemRecorder) : ToolProvider {
    override val tool: Tool<AudioAnalyzeArgs, AudioAnalyzeResult> by lazy {
        object : Tool<AudioAnalyzeArgs, AudioAnalyzeResult>(
            argsType = typeToken<AudioAnalyzeArgs>(),
            resultType = typeToken<AudioAnalyzeResult>(),
            name = "audio_analyze",
            description = """
                Record the next N seconds of live audio (seconds 0.5 to 15, default 5; the call takes that long)
                and measure each stem: level, third-octave spectrum, envelope, onsets, low end and stereo.
                $STEMS_DOC
                Pulsar must be playing. Desktop app only; one recording runs at a time, and a second is refused.
                Fails (ok=false) if the vibe changes or playback stops while recording.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: AudioAnalyzeArgs): AudioAnalyzeResult = guarded({ AudioAnalyzeResult(false, it) }) {
                val seconds = requireSeconds(args.seconds, ANALYZE_MAX_SECONDS)
                val stems = parseStems(args.stems)
                val kinds = parseKinds(args.kinds)
                val recorder = recorderProvider()
                val analyzer = StemAnalyzer(recorder.sampleRate, stems, kinds)
                val r = recorder.record(seconds, analyzer) { reportProgress(it.toDouble(), 1.0, "recording") }
                AudioAnalyzeResult(
                    ok = r.ok,
                    message = r.message,
                    vibe = r.vibe,
                    section = r.sectionAtStart,
                    seconds = r.frames.toFloat() / recorder.sampleRate,
                    droppedFrames = r.droppedFrames,
                    // The analyzer's buffers are free only once record returns.
                    stems = if (r.ok) analyzer.results() else emptyList(),
                    trackLabels = r.trackLabels,
                )
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class AudioCaptureTool(private val service: StemCaptureService) : ToolProvider {
    override val tool: Tool<AudioCaptureArgs, AudioCaptureResult> by lazy {
        object : Tool<AudioCaptureArgs, AudioCaptureResult>(
            argsType = typeToken<AudioCaptureArgs>(),
            resultType = typeToken<AudioCaptureResult>(),
            name = "audio_capture",
            description = """
                Record the next N seconds of live audio (seconds 0.5 to 30, default 10; the call takes that long)
                to one WAV file per stem, stereo 32-bit float at the app's sample rate, for offline analysis.
                The folder also holds stems.json (vibe, section, sample rate, frames, per-stem levels).
                $STEMS_DOC
                Pulsar must be playing. Desktop app only; one recording runs at a time, and a second is refused.
                Fails (ok=false), keeping no files, if the vibe changes or playback stops while recording.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: AudioCaptureArgs): AudioCaptureResult = guarded({ AudioCaptureResult(false, it) }) {
                val seconds = requireSeconds(args.seconds, CAPTURE_MAX_SECONDS)
                val o = service.capture(seconds, parseStems(args.stems)) { reportProgress(it.toDouble(), 1.0, "recording") }
                AudioCaptureResult(o.ok, o.message, o.dir, o.files, o.frames, o.droppedFrames)
            }
        }
    }
}
