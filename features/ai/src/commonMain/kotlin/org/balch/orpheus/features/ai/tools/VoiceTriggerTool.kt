package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.controller.SynthController

@Serializable
data class VoiceTriggerArgs(
    @property:LLMDescription("Voice index to trigger (0-7 for voices 1-8)")
    val voiceIndex: Int,

    @property:LLMDescription("Whether to start (true) or stop (false) the voice. Defaults to true.")
    val start: Boolean = true
)

@Serializable
data class VoiceTriggerResult(
    val success: Boolean,
    val message: String
)

/**
 * Tool for triggering voice pulses (playing notes).
 */
@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
class VoiceTriggerTool @Inject constructor(
    private val synthController: SynthController
) : ToolProvider {

    override val tool by lazy {
        object : Tool<VoiceTriggerArgs, VoiceTriggerResult>(
            argsSerializer = VoiceTriggerArgs.serializer(),
            resultSerializer = VoiceTriggerResult.serializer(),
            name = "voice_trigger",
            description = """
        Trigger a voice to play a note. Voice indices are 0-7 (corresponding to voices 1-8).
        Set start=true to begin playing, start=false to stop.
    """.trimIndent()
        ) {
            override suspend fun execute(args: VoiceTriggerArgs): VoiceTriggerResult {
                return executeInternal(args)
            }
        }
    }
    private suspend fun executeInternal(args: VoiceTriggerArgs): VoiceTriggerResult {
        val validIndex = args.voiceIndex.coerceIn(0, 7)
        
        if (args.start) {
            synthController.emitPulseStart(validIndex)
        } else {
            synthController.emitPulseEnd(validIndex)
        }
        
        val action = if (args.start) "triggered" else "released"
        return VoiceTriggerResult(
            success = true,
            message = "Voice ${validIndex + 1} $action"
        )
    }
}
