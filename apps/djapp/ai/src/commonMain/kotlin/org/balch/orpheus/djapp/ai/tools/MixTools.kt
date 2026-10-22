package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SendEdit
import org.balch.orpheus.features.pulsar.bench.effective
import org.balch.orpheus.features.pulsar.bench.sectionScopeAvailable

private const val TRACKS = 8
private const val FADER_MIN_DB = -30f
private const val FADER_MAX_DB = 3.5f

/** A mix edit the controller refused because a vibe load is not yet followed. */
internal const val VIBE_LOADING = "The vibe just changed; call mix_read, then try again."

@LLMDescription("Arguments for mix_read.")
@Serializable
data class MixReadArgs(
    @property:LLMDescription("Include loudness meters. After ~10 s without a read, the first read starts metering and returns silence; read again after ~3 s.")
    val meters: Boolean = true,
)

@LLMDescription("One Pulsar track as heard in the playing section.")
@Serializable
data class MixTrackState(
    @property:LLMDescription("Track index 0-7.")
    val track: Int,
    @property:LLMDescription("Track role: KICK, PERC, HIHAT, BASS, KEYS, PAD, TEXT or FX.")
    val name: String,
    @property:LLMDescription("Fader offset in dB over the vibe as written (whole-vibe plus section layer); 0 = unchanged.")
    val levelDb: Float,
    @property:LLMDescription("Pan, -1 (left) to 1 (right).")
    val pan: Float,
    @property:LLMDescription("Reverb send heard in this section, 0-1.")
    val reverb: Float,
    @property:LLMDescription("Delay send heard in this section, 0-1.")
    val delay: Float,
    val muted: Boolean,
    val soloed: Boolean,
    @property:LLMDescription("True when the mix tools hold this track's level.")
    val owned: Boolean,
)

@Serializable
data class MixSectionRef(val index: Int, val name: String)

@LLMDescription("One meter channel. relLu is loudness in LU relative to the dry mix.")
@Serializable
data class MixChannelMeter(
    val name: String,
    val relLu: Double,
    val peakDb: Double,
    val pan: Double,
    val subPct: Double,
    val lowPct: Double,
)

@LLMDescription("Loudness of the output over the last 3 s (lufsShort) and since metering started (lufsIntegrated).")
@Serializable
data class MixMeters(
    val lufsShort: Double,
    val lufsIntegrated: Double,
    val preClipPeakDb: Double,
    @property:LLMDescription("Percent of samples past the limiter knee.")
    val pastKneePct: Double,
    @property:LLMDescription("Stereo correlation, -1 to 1.")
    val correlation: Double,
    val section: Int,
    val channels: List<MixChannelMeter>,
)

@LLMDescription("The live mix: vibe, section, per-track values, macro knobs and meters.")
@Serializable
data class MixReadResult(
    val ok: Boolean,
    val message: String,
    val vibe: String = "",
    @property:LLMDescription("The playing section, or null before the arrangement reports one.")
    val section: MixSectionRef? = null,
    val sections: List<String> = emptyList(),
    @property:LLMDescription("Where mix_set edits land by default: VIBE (whole vibe) or SECTION (playing section only).")
    val scope: String = "",
    @property:LLMDescription("The section section_loop holds, or null.")
    val loopSection: Int? = null,
    val tracks: List<MixTrackState> = emptyList(),
    @property:LLMDescription("The app's macro knobs (energy, complexity, mood, space), 0-1: the whole-vibe values the app saves.")
    val macros: Map<String, Float> = emptyMap(),
    @property:LLMDescription("Macros a section-scope edit holds in the playing section, as heard (0-1); the engine plays these over the knob.")
    val sectionMacros: Map<String, Float> = emptyMap(),
    val meters: MixMeters? = null,
)

@LLMDescription("Arguments for mix_set. Give track for level_db, pan, reverb and delay; give macro for field=macro.")
@Serializable
data class MixSetArgs(
    @property:LLMDescription("level_db, pan, reverb, delay or macro.")
    val field: String,
    @property:LLMDescription("level_db: dB heard in the playing section, -30 to 3.5. pan: -1 to 1. reverb, delay, macro: 0-1.")
    val value: Float,
    @property:LLMDescription("Track index 0-7 (see mix_read).")
    val track: Int? = null,
    @property:LLMDescription("energy, complexity, mood or space.")
    val macro: String? = null,
    @property:LLMDescription("vibe (whole vibe) or section (playing section only). Omit to keep the current scope. Pan is always whole-vibe.")
    val scope: String? = null,
)

@LLMDescription("The values after a mix_set.")
@Serializable
data class MixSetResult(
    val ok: Boolean,
    val message: String,
    @property:LLMDescription("True: a mix capture will include this change.")
    val captured: Boolean = false,
    val scope: String = "",
    val track: MixTrackState? = null,
    val macro: String? = null,
    @property:LLMDescription("The macro's knob now, 0-1 (the whole-vibe value the app saves).")
    val macroKnob: Float? = null,
    @property:LLMDescription("The value heard in the playing section when a section edit holds this macro (engine only), else null.")
    val macroHeard: Float? = null,
)

@LLMDescription("A track index.")
@Serializable
data class MixTrackArgs(
    @property:LLMDescription("Track index 0-7 (see mix_read).")
    val track: Int,
)

@LLMDescription("Mute and solo state after the change.")
@Serializable
data class MixToggleResult(
    val ok: Boolean,
    val message: String,
    val muted: List<Int> = emptyList(),
    val soloed: List<Int> = emptyList(),
)

@LLMDescription("No arguments.")
@Serializable
class NoArgs

@LLMDescription("Outcome of the call.")
@Serializable
data class MixStatusResult(
    val ok: Boolean,
    val message: String,
)

@LLMDescription("Arguments for section_jump. Give section or name.")
@Serializable
data class SectionJumpArgs(
    @property:LLMDescription("Section index (see mix_read sections).")
    val section: Int? = null,
    @property:LLMDescription("Section name, matched ignoring case.")
    val name: String? = null,
)

@LLMDescription("The section the jump targets.")
@Serializable
data class SectionJumpResult(
    val ok: Boolean,
    val message: String,
    val section: MixSectionRef? = null,
)

@LLMDescription("Arguments for section_loop.")
@Serializable
data class SectionLoopArgs(
    @property:LLMDescription("true holds the playing section; false lets the arrangement move on.")
    val on: Boolean,
)

@LLMDescription("The held section after section_loop.")
@Serializable
data class SectionLoopResult(
    val ok: Boolean,
    val message: String,
    val loopSection: Int? = null,
)

/** Turns a thrown error into the tool's failure result, so a bad call never escapes as an exception. */
internal suspend inline fun <R> guarded(fail: (String) -> R, body: () -> R): R =
    try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(e.message ?: e::class.simpleName ?: "error")
    }

internal class ToolArgException(message: String) : Exception(message)

internal fun badArg(message: String): Nothing = throw ToolArgException(message)

internal fun requireTrack(t: Int?): Int = when {
    t == null -> badArg("track is required (0-7)")
    t !in 0 until TRACKS -> badArg("track $t out of range 0-7")
    else -> t
}

internal fun parseMacro(name: String?): Macro =
    Macro.entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
        ?: badArg("macro must be energy, complexity, mood or space, not \"$name\"")

internal fun parseScope(name: String): Scope =
    Scope.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        ?: badArg("scope must be vibe or section, not \"$name\"")

private fun Float.r3(): Float = (this * 1000f).roundToInt() / 1000f

private fun Double.r2(): Double = if (isFinite()) (this * 100.0).roundToInt() / 100.0 else BenchMeters.SILENT_DB

internal fun PulsarFeature.sectionRef(index: Int): MixSectionRef? =
    vibeFlow.value.arrangement?.sections?.getOrNull(index)?.let { MixSectionRef(index, it.name) }

internal suspend fun MixBenchController.trackStates(pulsar: PulsarFeature): List<MixTrackState> {
    val vibe = pulsar.vibeFlow.value
    val section = pulsar.arrangementStateFlow.value.sectionIndex
    val layers = layers.value
    val energy = pulsar.macroKnob(Macro.ENERGY)
    val owned = ownedTracks()
    return (0 until minOf(TRACKS, vibe.tracks.size)).map { t ->
        val e = layers.effective(vibe, section, t, energy)
        MixTrackState(
            track = t,
            name = BenchMeters.CHANNEL_NAMES[t],
            levelDb = e.levelDb.r3(),
            pan = e.pan.r3(),
            reverb = e.reverbSend.r3(),
            delay = e.delaySend.r3(),
            muted = t in muted.value,
            soloed = t in soloed.value,
            owned = t in owned,
        )
    }
}

private fun MeterSnapshot.toResult() = MixMeters(
    lufsShort = lufsShort.r2(),
    lufsIntegrated = lufsIntegrated.r2(),
    preClipPeakDb = preClipPeakDb.r2(),
    pastKneePct = pastKneePct.r2(),
    correlation = correlation.r2(),
    section = section,
    channels = channels.map { MixChannelMeter(it.name, it.relLu.r2(), it.peakDb.r2(), it.pan.r2(), it.subPct.r2(), it.lowPct.r2()) },
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class MixReadTool(
    // Lazy providers, as in VibeApplyTool: the feature and the AppScope controller would close a DI cycle.
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val controllerProvider: () -> MixBenchController,
) : ToolProvider {
    override val tool: Tool<MixReadArgs, MixReadResult> by lazy {
        object : Tool<MixReadArgs, MixReadResult>(
            argsType = typeToken<MixReadArgs>(),
            resultType = typeToken<MixReadResult>(),
            name = "mix_read",
            description = """
                Read the live Pulsar mix: vibe, playing section, edit scope, each of the 8 tracks (levelDb offset,
                pan -1..1, reverb and delay sends 0-1 as heard in this section, mute, solo), the macro knobs (0-1)
                and, with meters=true, loudness (LUFS, peak dB, per-channel LU).
            """.trimIndent(),
        ) {
            override suspend fun execute(args: MixReadArgs): MixReadResult = guarded({ MixReadResult(false, it) }) {
                val pulsar = pulsarFeatureProvider()
                val c = controllerProvider()
                val warm = c.isMetering
                val meters = if (args.meters) c.readMeters().toResult() else null
                val vibe = pulsar.vibeFlow.value
                val section = pulsar.arrangementStateFlow.value.sectionIndex
                MixReadResult(
                    ok = true,
                    message = if (args.meters && !warm) "Metering just started; meters read silent until the next read." else "ok",
                    vibe = vibe.name,
                    section = pulsar.sectionRef(section),
                    sections = vibe.arrangement?.sections.orEmpty().map { it.name },
                    scope = c.scope.value.name,
                    loopSection = c.loopSection.value,
                    tracks = c.trackStates(pulsar),
                    macros = Macro.entries.associate { it.name.lowercase() to pulsar.macroKnob(it).r3() },
                    sectionMacros = c.layers.value.sections[section]?.macrosHeard.orEmpty()
                        .entries.associate { (m, v) -> m.name.lowercase() to v.r3() },
                    meters = meters,
                )
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class MixSetTool(
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val controllerProvider: () -> MixBenchController,
) : ToolProvider {
    override val tool: Tool<MixSetArgs, MixSetResult> by lazy {
        object : Tool<MixSetArgs, MixSetResult>(
            argsType = typeToken<MixSetArgs>(),
            resultType = typeToken<MixSetResult>(),
            name = "mix_set",
            description = """
                Change the live mix; a mix capture includes the change.
                field=level_db: dB the track should be heard at in the playing section, -30 to 3.5 (0 = as written).
                field=pan: -1 to 1, whole vibe only. field=reverb or delay: send heard in the playing section, 0-1.
                field=macro: energy, complexity, mood or space knob, 0-1.
                scope=vibe edits every section; scope=section edits only the playing one. Omit scope to keep the last one.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: MixSetArgs): MixSetResult = guarded({ MixSetResult(false, it) }) {
                val pulsar = pulsarFeatureProvider()
                val c = controllerProvider()
                if (!args.value.isFinite()) badArg("value must be a number")
                val field = args.field.trim().lowercase()
                if (field !in FIELDS) badArg("field must be one of ${FIELDS.joinToString()}, not \"${args.field}\"")
                val track = if (field == "macro") null else requireTrack(args.track)
                val macro = if (field == "macro") parseMacro(args.macro) else null
                args.scope?.let { c.setScope(parseScope(it)) }
                val scope = c.scope.value
                val notes = mutableListOf<String>()
                if (scope == Scope.SECTION && !pulsar.vibeFlow.value.sectionScopeAvailable()) {
                    notes += "This vibe has fewer than two sections, so the edit went to the whole vibe."
                }
                val range = RANGES.getValue(field)
                val value = args.value.coerceIn(range)
                if (value != args.value) notes += "$field clamped to ${range.start}..${range.endInclusive} (asked ${args.value})."
                val applied = when (field) {
                    "level_db" -> c.setHeardLevelDb(track!!, value)
                    "pan" -> {
                        if (scope == Scope.SECTION) notes += "Pan is whole-vibe only."
                        c.setPan(track!!, value)
                    }
                    "reverb", "delay" -> when (c.setHeardSend(track!!, value, reverb = field == "reverb")) {
                        SendEdit.APPLIED -> true
                        SendEdit.VIBE_LOADING -> false
                        SendEdit.ZERO_SEND ->
                            badArg("This send is 0 here, so a whole-vibe edit can't scale it; use scope=section.")
                    }
                    else -> c.setMacro(macro!!, value)
                }
                if (!applied) badArg(VIBE_LOADING)
                val section = pulsar.arrangementStateFlow.value.sectionIndex
                MixSetResult(
                    ok = true,
                    message = notes.joinToString(" ").ifEmpty { "ok" },
                    captured = true,
                    scope = scope.name,
                    track = track?.let { c.trackStates(pulsar)[it] },
                    macro = macro?.name?.lowercase(),
                    macroKnob = macro?.let { pulsar.macroKnob(it).r3() },
                    macroHeard = macro?.let { c.layers.value.sections[section]?.macrosHeard?.get(it)?.r3() },
                )
            }
        }
    }

    private companion object {
        val FIELDS = listOf("level_db", "pan", "reverb", "delay", "macro")
        val RANGES = mapOf(
            "level_db" to FADER_MIN_DB..FADER_MAX_DB,
            "pan" to -1f..1f,
            "reverb" to 0f..1f,
            "delay" to 0f..1f,
            "macro" to 0f..1f,
        )
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class MixMuteTool(private val controllerProvider: () -> MixBenchController) : ToolProvider {
    override val tool: Tool<MixTrackArgs, MixToggleResult> by lazy {
        toggleTool(
            "mix_mute",
            "Toggle mute on one track (0-7). Mutes stay through mix_reset; a vibe load clears them.",
            controllerProvider,
        ) { toggleMute(it) }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class MixSoloTool(private val controllerProvider: () -> MixBenchController) : ToolProvider {
    override val tool: Tool<MixTrackArgs, MixToggleResult> by lazy {
        toggleTool(
            "mix_solo",
            "Toggle solo on one track (0-7); any solo silences the unsoloed tracks. Solos stay through mix_reset; a vibe load clears them.",
            controllerProvider,
        ) { toggleSolo(it) }
    }
}

private fun toggleTool(
    name: String,
    description: String,
    controllerProvider: () -> MixBenchController,
    toggle: suspend MixBenchController.(Int) -> Boolean,
): Tool<MixTrackArgs, MixToggleResult> = object : Tool<MixTrackArgs, MixToggleResult>(
    argsType = typeToken<MixTrackArgs>(),
    resultType = typeToken<MixToggleResult>(),
    name = name,
    description = description,
) {
    override suspend fun execute(args: MixTrackArgs): MixToggleResult = guarded({ MixToggleResult(false, it) }) {
        val c = controllerProvider()
        if (!c.toggle(requireTrack(args.track))) badArg(VIBE_LOADING)
        MixToggleResult(true, "ok", c.muted.value.sorted(), c.soloed.value.sorted())
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class MixResetTool(private val controllerProvider: () -> MixBenchController) : ToolProvider {
    override val tool: Tool<NoArgs, MixStatusResult> by lazy {
        object : Tool<NoArgs, MixStatusResult>(
            argsType = typeToken<NoArgs>(),
            resultType = typeToken<MixStatusResult>(),
            name = "mix_reset",
            description = """
                Reset the mix edits: levels, pans, sends and every section edit (section macros included) go back
                to the vibe as written. Keeps mutes, solos and whole-vibe macro moves: those stay on the app's
                knobs and a mix capture still writes them.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: NoArgs): MixStatusResult = guarded({ MixStatusResult(false, it) }) {
                controllerProvider().resetToFile()
                MixStatusResult(true, "Levels, pans, sends and section edits reset; mutes, solos and whole-vibe macro moves kept (a capture includes them).")
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class SectionJumpTool(
    private val pulsarFeatureProvider: () -> PulsarFeature,
    private val controllerProvider: () -> MixBenchController,
) : ToolProvider {
    override val tool: Tool<SectionJumpArgs, SectionJumpResult> by lazy {
        object : Tool<SectionJumpArgs, SectionJumpResult>(
            argsType = typeToken<SectionJumpArgs>(),
            resultType = typeToken<SectionJumpResult>(),
            name = "section_jump",
            description = "Jump to an arrangement section at the next bar line, by index or name (see mix_read sections). A held section_loop moves with it.",
        ) {
            override suspend fun execute(args: SectionJumpArgs): SectionJumpResult = guarded({ SectionJumpResult(false, it) }) {
                val pulsar = pulsarFeatureProvider()
                val sections = pulsar.vibeFlow.value.arrangement?.sections.orEmpty()
                if (sections.isEmpty()) badArg("This vibe has no sections.")
                val index = when {
                    args.section != null -> args.section.takeIf { it in sections.indices }
                        ?: badArg("section ${args.section} out of range 0-${sections.lastIndex}")
                    args.name != null -> sections.indexOfFirst { it.name.equals(args.name.trim(), ignoreCase = true) }
                        .takeIf { it >= 0 }
                        ?: badArg("no section named \"${args.name}\"; sections: ${sections.joinToString { it.name }}")
                    else -> badArg("give section (index) or name")
                }
                controllerProvider().jumpTo(index)
                SectionJumpResult(true, "Jumps at the next bar line.", pulsar.sectionRef(index))
            }
        }
    }
}

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class SectionLoopTool(private val controllerProvider: () -> MixBenchController) : ToolProvider {
    override val tool: Tool<SectionLoopArgs, SectionLoopResult> by lazy {
        object : Tool<SectionLoopArgs, SectionLoopResult>(
            argsType = typeToken<SectionLoopArgs>(),
            resultType = typeToken<SectionLoopResult>(),
            name = "section_loop",
            description = "on=true holds the playing section (each move away jumps back at the next bar line); on=false releases it.",
        ) {
            override suspend fun execute(args: SectionLoopArgs): SectionLoopResult = guarded({ SectionLoopResult(false, it) }) {
                val c = controllerProvider()
                c.setLoop(args.on)
                val held = c.loopSection.value
                SectionLoopResult(
                    ok = !args.on || held != null,
                    message = if (args.on && held == null) "No section is playing yet." else "ok",
                    loopSection = held,
                )
            }
        }
    }
}
