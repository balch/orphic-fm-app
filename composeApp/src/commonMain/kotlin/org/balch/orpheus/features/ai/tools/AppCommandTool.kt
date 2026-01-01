package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.tidal.TidalRepl

/**
 * Describes a command sent from the users to the Agent.
 * Each subclass represents a specific app command with its parameters.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("command")
@LLMDescription("A tool to control and query the app. Choose the appropriate command type based on what the user wants to do or know.")
sealed class AppCommand {

    @Serializable
    @SerialName("Mute")
    @LLMDescription("Stops all sounds and REPL patterns. Use when user says: stop, mute, silence, quiet, done, hush")
    data object Mute : AppCommand()

    @Serializable
    @SerialName("ChangeModel")
    @LLMDescription("Changes the AI model. Use when user wants to switch between Flash 2.5 and Pro 2.5 models.")
    data class ChangeModel(
        @SerialName("model_name")
        @property:LLMDescription("The model ID to switch to. Available: 'flash_25' (Flash 2.5) or 'pro_25' (Pro 2.5)")
        val modelId: String
    ) : AppCommand()

    @Serializable
    @SerialName("GetModel")
    @LLMDescription("Returns the current model as a string. Use when asked what kind of model, ai, llm you are")
    data object GetModel : AppCommand()
}

/**
 * Tool for executing built-in commands to control the app
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class AppCommandTool @Inject constructor(
    private val tidalRepl: TidalRepl,
    private val aiModelProvider: AiModelProvider,
) : Tool<AppCommand, AppCommandTool.Result>(
    argsSerializer = AppCommand.serializer(),
    resultSerializer = Result.serializer(),
    name = "app_control",
    description = """
        Tool to allow the user to control the app or query about app specific settings (ex: llm model).
        Check the user intent to the list of commands supported and call this tool when appropriate.
    """.trimIndent()
) {
    private val log = logging("AppCommandTool")

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String
    )

    override suspend fun execute(args: AppCommand): Result {
        log.info { "AppCommandTool: Executing command: $args" }

        return when (args) {
            is AppCommand.Mute -> executeMute()
            is AppCommand.ChangeModel -> executeChangeModel(args.modelId)
            is AppCommand.GetModel -> Result(true, aiModelProvider.currentKoogModel.id)
        }
    }

    private suspend fun executeMute(): Result {
        return try {
            log.info { "AppCommandTool: Executing mute/hush" }
            tidalRepl.hush()
            Result(
                success = true,
                message = "All sounds muted and patterns stopped"
            )
        } catch (e: Exception) {
            log.error { "AppCommandTool: Failed to mute: ${e.message}" }
            Result(
                success = false,
                message = "Failed to mute: ${e.message}"
            )
        }
    }

    private suspend fun executeChangeModel(modelId: String): Result {
        val model = AiModel.entries.find { it.id == modelId }
        
        return if (model != null) {
            try {
                log.info { "AppCommandTool: Changing model to ${model.displayName}" }
                aiModelProvider.selectModel(model)
                Result(
                    success = true,
                    message = "Changed AI model to ${model.displayName}. The new model will be used for the next session."
                )
            } catch (e: Exception) {
                log.error { "AppCommandTool: Failed to change model: ${e.message}" }
                Result(
                    success = false,
                    message = "Failed to change model: ${e.message}"
                )
            }
        } else {
            log.warn { "AppCommandTool: Unknown model ID: $modelId" }
            Result(
                success = false,
                message = "Unknown model ID: $modelId. Available: ${AiModel.entries.joinToString { it.id }}"
            )
        }
    }
}
