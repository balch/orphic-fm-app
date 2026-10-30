package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.DuoPatchBay
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.core.di.FeatureScope

@Serializable
data class DuoPatchArgs(
    @property:LLMDescription("One of: read, connect, disconnect, set_amount, wake, bridge.")
    val action: String,
    @property:LLMDescription("Duo index 0-5.")
    val duo: Int,
    @property:LLMDescription("Source: LFO_AB, LFO_A, LFO_B, LFO_A90, WAVES_1..WAVES_4, FLUX_X1, FLUX_X2, FLUX_X3, FLUX_Y, FLUX_T1, FLUX_T2, FLUX_T3, VOICE_FM.")
    val source: String? = null,
    @property:LLMDescription("Destination: HARMONICS, TIMBRE, MORPH, FM (OSC engine only), PITCH, ENV.")
    val dest: String? = null,
    @property:LLMDescription("BOTH (default), A or B. Only matters for PITCH and ENV once unbridged.")
    val slot: String? = null,
    @property:LLMDescription("Cable amount -1.0 to 1.0 (connect defaults to 0.5).")
    val amount: Float? = null,
    @property:LLMDescription("For bridge: true shares one PITCH or ENV row across the duo, false gives voices A and B their own.")
    val bridged: Boolean? = null,
)

@Serializable
data class DuoPatchResult(val success: Boolean, val message: String, val patch: String)

/** Readable cable list for a duo, with parked cables marked. */
fun describePatch(patch: DuoPatch): String {
    val rows = patch.cables.joinToString("\n") { c ->
        val parked = if (c.parked) " (parked)" else ""
        "${c.source.name} -> ${c.dest.name} [${c.slot.name}] ${c.amount}$parked"
    }.ifEmpty { "(no cables)" }
    return "$rows\npitchBridged=${patch.pitchBridged} envBridged=${patch.envBridged}"
}

/** Applies one `duo_patch` request to [bay]; a failed request leaves the patch untouched. */
fun executeDuoPatch(bay: DuoPatchBay, args: DuoPatchArgs): DuoPatchResult {
    val patches = bay.patches.value
    fun fail(msg: String) = DuoPatchResult(false, msg, patches.getOrNull(args.duo)?.let(::describePatch).orEmpty())

    val current = patches.getOrNull(args.duo) ?: return fail("Duo must be 0..${patches.lastIndex}.")
    if (args.action == "read") return DuoPatchResult(true, "Duo ${args.duo} patch.", describePatch(current))

    if (args.action !in setOf("connect", "disconnect", "set_amount", "wake", "bridge")) {
        return fail("Unknown action: ${args.action}. Use read, connect, disconnect, set_amount, wake or bridge.")
    }
    val dest = args.dest?.let { n -> PatchDest.entries.firstOrNull { it.name == n } }
        ?: return fail("Unknown or missing dest: ${args.dest}. Use ${PatchDest.entries.joinToString { it.name }}.")
    val slot = args.slot?.let { n -> VoiceSlot.entries.firstOrNull { it.name == n } ?: return fail("Unknown slot: $n.") }
        ?: VoiceSlot.BOTH

    val transform: (DuoPatch) -> DuoPatch
    val message: String
    if (args.action == "bridge") {
        val bridged = args.bridged ?: return fail("bridge needs bridged=true or false.")
        if (!dest.perVoice) return fail("Only PITCH and ENV can be bridged or split.")
        transform = { it.setBridged(dest, bridged) }
        message = "Set ${dest.name} bridged=$bridged."
    } else {
        val source = args.source?.let { n -> PatchSource.entries.firstOrNull { it.name == n } }
            ?: return fail("Unknown or missing source: ${args.source}. Use ${PatchSource.entries.joinToString { it.name }}.")
        transform = when (args.action) {
            // Live even on DX: a cable patched there steps through patches deliberately.
            "connect" -> { p -> p.connect(source, dest, slot, args.amount ?: 0.5f) }
            "disconnect" -> { p -> p.disconnect(source, dest, slot) }
            "set_amount" -> {
                val amount = args.amount ?: return fail("set_amount needs amount.")
                ({ p -> p.setAmount(source, dest, slot, amount) })
            }
            "wake" -> { p -> p.wake(source, dest, slot) }
            else -> return fail("Unknown action: ${args.action}.")
        }
        message = "${args.action} ${source.name} -> ${dest.name} on duo ${args.duo}."
    }

    // Read-modify-write happens inside the bay so concurrent edits are not lost.
    var changed = false
    bay.update(args.duo) { p ->
        val next = transform(p)
        changed = next != p
        next
    }
    val after = describePatch(bay.patches.value[args.duo])
    val mustChange = args.action in setOf("disconnect", "set_amount", "wake")
    return if (changed || !mustChange) DuoPatchResult(true, message, after)
    else DuoPatchResult(false, "No matching cable, or nothing to change.", after)
}

/** Edits the modulation cables of a voice duo. */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
class DuoPatchTool(
    private val duoPatchBay: DuoPatchBay,
) : ToolProvider {
    override val tool by lazy {
        object : Tool<DuoPatchArgs, DuoPatchResult>(
            argsType = typeToken<DuoPatchArgs>(),
            resultType = typeToken<DuoPatchResult>(),
            name = "duo_patch",
            description = """
                Read or edit the modulation patch of a voice duo (0-5). A patch is a list of cables, each
                running from a source to a destination with an amount from -1 to 1.
                Sources: LFO_AB, LFO_A, LFO_B, LFO_A90 (LFO A·B, A, B, A 90°), WAVES_1..4,
                FLUX_X1, FLUX_X2, FLUX_X3, FLUX_Y, FLUX_T1, FLUX_T2, FLUX_T3, VOICE_FM.
                Destinations: HARMONICS, TIMBRE, MORPH, FM (OSC engine only), PITCH, ENV.
                Actions: read, connect, disconnect, set_amount, wake (unpark a cable), bridge
                (PITCH or ENV: bridged=false lets voices A and B take separate cables).
                Cables stay silent until voice_duo_mod_source_level_N is above 0. HARMONICS cables park (go
                inactive) when a duo switches to a DX engine; wake brings one back. A HARMONICS cable
                connected while the duo is already on DX is live and steps through patches. Flux sources also need flux_mix above 0.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: DuoPatchArgs): DuoPatchResult =
                executeDuoPatch(duoPatchBay, args)
        }
    }
}
