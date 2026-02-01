package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.balch.orpheus.core.routing.SynthController

/**
 * Tool for triggering voice pulses (playing notes).
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class VoiceTriggerTool @Inject constructor(
    private val synthController: SynthController
) : Tool<VoiceTriggerTool.Args, VoiceTriggerTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "voice_trigger",
    description = """
        Trigger a voice to play a note. Voice indices are 0-7 (corresponding to voices 1-8).
        Set start=true to begin playing, start=false to stop.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Voice index to trigger (0-7 for voices 1-8)")
        val voiceIndex: Int,

        @property:LLMDescription("Whether to start (true) or stop (false) the voice. Defaults to true.")
        val start: Boolean = true
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String
    )

    override suspend fun execute(args: Args): Result {
        val validIndex = args.voiceIndex.coerceIn(0, 7)
        
        if (args.start) {
            synthController.emitPulseStart(validIndex)
        } else {
            synthController.emitPulseEnd(validIndex)
        }
        
        val action = if (args.start) "triggered" else "released"
        return Result(
            success = true,
            message = "Voice ${validIndex + 1} $action"
        )
    }
}
