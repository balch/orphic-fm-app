package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.djapp.ai.collab.CollabMixer
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.MixCaptureService

@LLMDescription("What mix_capture saved.")
@Serializable
data class MixCaptureResult(
    val ok: Boolean,
    val message: String,
    @property:LLMDescription("Absolute path of the capture JSON, or null when none was written.")
    val file: String? = null,
    @property:LLMDescription(
        "One row per changed vibe field: scope (VIBE or SECTION), section index and name, track 0-7, field, macro, " +
            "and the old and new values as the vibe stores them (SECTION macro rows hold multipliers). " +
            "clampedDb > 0: the heard gain passed 1.0, so the written volume is that many dB quieter than heard.",
    )
    val rows: List<CaptureRow> = emptyList(),
    @property:LLMDescription(
        "True: the vibe's Kotlin source changed since the app loaded it, so old values may not match it. " +
            "Null: can't tell (no repo checkout, an AI vibe, or a load the app did not record).",
    )
    val stale: Boolean? = null,
    @property:LLMDescription("True for an AI-made vibe, one not in vibe_list.")
    val aiVibe: Boolean = false,
    @property:LLMDescription("True: the rows were written into the AI vibe itself, which was archived and re-applied.")
    val appliedToAiVibe: Boolean = false,
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
// The service itself, not a provider: building it with the tools starts its load follower early.
class MixCaptureTool(private val service: MixCaptureService, private val collabMixer: CollabMixer) : ToolProvider {
    override val tool: Tool<NoArgs, MixCaptureResult> by lazy {
        object : Tool<NoArgs, MixCaptureResult>(
            argsType = typeToken<NoArgs>(),
            resultType = typeToken<MixCaptureResult>(),
            name = "mix_capture",
            description = """
                Save the live mix edits (levels, pans, sends, macros and delay settings from mix_set) as vibe field changes in a
                capture file, and return the rows. Mutes and solos are not captured.
                A catalog vibe (in vibe_list): only the file is written, for applying to the vibe's source later;
                the live mix stays as it is.
                An AI-made vibe: the rows are also written into the vibe itself, which is archived and re-applied.
                The song restarts, and mutes, solos, the section loop and the mix edits clear, since the vibe now holds them.
                Fails (ok=false) if the vibe changes while capturing.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: NoArgs): MixCaptureResult = guarded({ MixCaptureResult(false, it) }) {
                // A capture reads the controller's layers, so it saves the history, not a point being heard.
                collabMixer.endHear()
                val o = service.capture()
                // A catalog vibe keeps playing the mix, so the History marks where the file was written.
                if (o.ok && o.rows.isNotEmpty() && !o.appliedToAiVibe) collabMixer.markPoint(CAPTURED)
                MixCaptureResult(o.ok, o.message, o.file, o.rows, o.stale, o.aiVibe, o.appliedToAiVibe)
            }
        }
    }

    companion object {
        const val CAPTURED = "Captured"
    }
}
