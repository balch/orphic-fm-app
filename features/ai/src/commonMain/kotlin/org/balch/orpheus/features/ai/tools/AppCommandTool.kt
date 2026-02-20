package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import org.balch.orpheus.core.di.FeatureScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonClassDiscriminator
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.currentKoogModel
import org.balch.orpheus.core.coroutines.runCatchingSuspend
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

@Serializable
data class AppCommandResult(
    val success: Boolean,
    val message: String
)

/**
 * Tool for executing built-in commands to control the app
 */
@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
class AppCommandTool @Inject constructor(
    private val tidalRepl: TidalRepl,
    private val aiModelProvider: AiModelProvider,
) : ToolProvider {

    override val tool by lazy {
        object : Tool<AppCommand, AppCommandResult>(
            argsSerializer = AppCommand.serializer(),
            resultSerializer = AppCommandResult.serializer(),
            name = "app_control",
            description = """
        Tool to allow the user to control the app or query about app specific settings (ex: llm model).
        Check the user intent to the list of commands supported and call this tool when appropriate.
    """.trimIndent()
        ) {
            override suspend fun execute(args: AppCommand): AppCommandResult {
                return executeInternal(args)
            }
        }
    }

    private val log = logging("AppCommandTool")

    private suspend fun executeInternal(args: AppCommand): AppCommandResult {
        log.debug { "Executing command: $args" }

        return when (args) {
            is AppCommand.Mute -> executeMute()
            is AppCommand.ChangeModel -> executeChangeModel(args.modelId)
            is AppCommand.GetModel -> AppCommandResult(true, aiModelProvider.currentKoogModel.id)
        }
    }

    private fun executeMute(): AppCommandResult =
        runCatching {
            log.debug { "Executing mute/hush" }
            tidalRepl.hush()
        }.fold(
            onSuccess = {
                AppCommandResult(
                    success = true,
                    message = "All sounds muted and patterns stopped"
                )
            },
            onFailure = { e ->
                log.error(e) { "AppCommandTool: Failed to mute: ${e.message}" }
                AppCommandResult(
                    success = false,
                    message = "Failed to mute: ${e.message}"
                )
            }
        )

    private suspend fun executeChangeModel(modelId: String): AppCommandResult {
        val model = AiModel.entries.find { it.id == modelId }

        return if (model != null) {
            runCatchingSuspend {
                log.debug { "Changing model to ${model.displayName}" }
                aiModelProvider.selectModel(model)
            }.fold(
                onSuccess = {
                    AppCommandResult(
                        success = true,
                        message = "Changed AI model to ${model.displayName}. The new model will be used for the next session."
                    )
                },
                onFailure = {
                    log.error(it) { "Failed to change model: ${it.message}" }
                    AppCommandResult(
                        success = false,
                        message = "Error AI model to ${model.displayName}. The new model will be used for the next session."
                    )
                }
            )
        } else {
            log.warn { "AppCommandTool: Unknown model ID: $modelId" }
            AppCommandResult(
                success = false,
                message = "Unknown model ID: $modelId. Available: ${AiModel.entries.joinToString { it.id }}"
            )
        }
    }
}
