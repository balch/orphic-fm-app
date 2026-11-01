package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.djapp.ai.licklab.LickLabFeature
import org.balch.orpheus.djapp.ai.licklab.LickSlot
import org.balch.orpheus.djapp.ai.licklab.SlotRef
import org.balch.orpheus.features.pulsar.PulsarFeature

@Serializable
data class KeyboardReadArgs(
    @property:LLMDescription("Return only the takes the user starred. Default false.")
    val starredOnly: Boolean = false,
    @property:LLMDescription("How many of the newest takes to return, 1-20. Default 5.")
    val last: Int = 5,
)

@Serializable
data class StepDto(
    val rest: Boolean,
    @property:LLMDescription("Scale degree in the slot's key; meaningless for a rest.")
    val degree: Int,
    @property:LLMDescription("Beats.")
    val duration: Float,
    val velocity: Float,
    @property:LLMDescription("0-1 when the note slid in from the previous one, else -1.")
    val glideRate: Float,
)

@Serializable
data class TakeDto(
    val id: Long,
    val starred: Boolean,
    @property:LLMDescription("Where this take was played to be pasted: Lead, Bass, Pool n or Solo track n.")
    val slot: String,
    val steps: List<StepDto>,
    val loopLength: Int,
    @property:LLMDescription("Paste-ready Kotlin for the vibe file, exactly what the user's Copy button gives.")
    val kotlin: String,
    @property:LLMDescription("Step indices whose note was off the scale and snapped to the nearest degree.")
    val chromaticSteps: List<Int>,
    @property:LLMDescription("MIDI notes actually played for those chromatic steps, same order.")
    val chromaticMidi: List<Int> = emptyList(),
    @property:LLMDescription("More steps than a Lick accepts; split it before pasting.")
    val overCap: Boolean = false,
    @property:LLMDescription("Notes dropped because two landed on the same 16th.")
    val mergedCount: Int = 0,
    @property:LLMDescription("Step index to the degree it was clamped from, for notes outside -32..63.")
    val clamped: Map<Int, Int> = emptyMap(),
    @property:LLMDescription(
        "Played on a track that follows chords (FOLLOW or ROOT_ONLY). Live play was not transposed, so a paste may " +
            "sound transposed against the take when the chords move. Tell the user; never transpose the notes.",
    )
    val followsChords: Boolean = false,
)

@Serializable
data class KeyboardReadResult(
    val ok: Boolean,
    val error: String? = null,
    val vibe: String = "",
    @property:LLMDescription("The armed track, null when none is armed.")
    val track: Int? = null,
    @property:LLMDescription("The slot picked now, for the next take; each take carries its own slot.")
    val slot: String = "",
    @property:LLMDescription("The vibe's key and what degree 0 is, e.g. \"B minor · 0 = B4\".")
    val key: String = "",
    val bpm: Float = 0f,
    val takes: List<TakeDto> = emptyList(),
)

/**
 * Reads the takes the user played in Lick Lab. Served by the MCP server only (not contributed into the
 * ToolProvider set), so the in-app agent never sees it; lazy providers avoid a DI cycle as in [MixReadTool].
 */
class KeyboardReadTool(
    private val labProvider: () -> LickLabFeature,
    private val pulsarProvider: () -> PulsarFeature,
) : ToolProvider {
    override val tool: Tool<KeyboardReadArgs, KeyboardReadResult> by lazy {
        object : Tool<KeyboardReadArgs, KeyboardReadResult>(
            argsType = typeToken<KeyboardReadArgs>(),
            resultType = typeToken<KeyboardReadResult>(),
            name = "keyboard_read",
            description = """
                Read the takes the user played on the Lick Lab keyboard, newest last: steps (scale degrees in the
                vibe's key, durations in beats) and paste-ready Kotlin. Every note is one the user played. When
                pasting into a vibe file, do not add, drop, transpose or retime notes: only place the Lick
                in the slot the user names. Marks to relay: overCap, mergedCount, chromatic and clamped steps, followsChords.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: KeyboardReadArgs): KeyboardReadResult =
                guarded({ KeyboardReadResult(false, it) }) {
                    val lab = labProvider()
                    val state = lab.stateFlow.value
                    val pulsar = pulsarProvider()
                    val takes = state.takes
                        .filter { !args.starredOnly || it.starred }
                        .takeLast(args.last.coerceIn(1, 20))
                        .mapNotNull { take ->
                            val ctx = take.context ?: return@mapNotNull null
                            val lick = lab.exportFor(take.id) ?: return@mapNotNull null
                            TakeDto(
                                id = take.id,
                                starred = take.starred,
                                slot = ctx.slot.label(),
                                steps = lick.steps.map { StepDto(it.rest, it.scaleDegree, it.duration, it.velocity, it.glideRate) },
                                loopLength = lick.loopLength,
                                kotlin = lab.kotlinFor(take.id).orEmpty(),
                                chromaticSteps = lick.chromaticNotes,
                                chromaticMidi = lick.chromaticMidi,
                                overCap = lick.overCap,
                                mergedCount = lick.mergedCount,
                                clamped = lick.clamped,
                                followsChords = ctx.followsChords,
                            )
                        }
                    KeyboardReadResult(
                        ok = true,
                        vibe = pulsar.vibeFlow.value.name,
                        track = state.armedTrack,
                        slot = state.slot.label(),
                        key = state.keyLabel,
                        bpm = pulsar.stateFlow.value.bpm,
                        takes = takes,
                    )
                }
        }
    }
}

private fun SlotRef.label(): String = when (slot) {
    LickSlot.Lead -> "Lead"
    LickSlot.Bass -> "Bass"
    LickSlot.Pool -> "Pool $index"
    LickSlot.Solo -> "Solo track $index"
}
