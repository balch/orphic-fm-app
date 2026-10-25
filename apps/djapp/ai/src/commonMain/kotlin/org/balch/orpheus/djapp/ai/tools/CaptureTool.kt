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
import org.balch.orpheus.features.pulsar.bench.VibeFieldChange

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
        "One row per changed vibe field other than the mix: path (e.g. tracks[2].engineEdm.harmonics), " +
            "old (the file's value) and new, as JSON.",
    )
    val vibeRows: List<VibeFieldChange> = emptyList(),
    @property:LLMDescription(
        "True: the vibe's Kotlin source changed since the app loaded it, so old values may not match it. " +
            "Null: can't tell (no repo checkout, an AI vibe, or a load the app did not record).",
    )
    val stale: Boolean? = null,
    @property:LLMDescription("True for an AI-made vibe, one not in vibe_list.")
    val aiVibe: Boolean = false,
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
// The service itself, not a provider: building it with the tools starts its load follower early. Captures go through [collabMixer].
class MixCaptureTool(@Suppress("UNUSED_PARAMETER") service: MixCaptureService, private val collabMixer: CollabMixer) : ToolProvider {
    override val tool: Tool<NoArgs, MixCaptureResult> by lazy {
        object : Tool<NoArgs, MixCaptureResult>(
            argsType = typeToken<NoArgs>(),
            resultType = typeToken<MixCaptureResult>(),
            name = "mix_capture",
            description = """
                Save the live mix edits (levels, pans, sends, macros and delay settings from mix_set) as vibe field changes in a
                capture file, and return the rows. Sound edits made with pulsar_edit_vibe are captured too, as vibe
                field rows. Mutes and solos are not captured.
                Only the file is written, for applying to the vibe's source later; the live mix stays as it is, and the
                captured rows stay in the History marked captured. Works the same for catalog and AI-made vibes.
                With no new mix changes since the last capture, or while a vibe is loading, nothing is written (ok=true, no file).
            """.trimIndent(),
        ) {
            override suspend fun execute(args: NoArgs): MixCaptureResult = guarded({ MixCaptureResult(false, it) }) {
                // The mixer ends any hear and adds the History's CAPTURE point.
                val o = collabMixer.capture()
                if (o == null) MixCaptureResult(true, NOTHING_NEW)
                else MixCaptureResult(o.ok, o.message, o.file, o.rows, o.vibeRows, o.stale, o.aiVibe)
            }
        }
    }

    companion object {
        const val NOTHING_NEW = "No new mix or sound changes since the last capture; nothing written."
    }
}
