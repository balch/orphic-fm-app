package org.balch.orpheus.core.ai

import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.preferences.AppPreferencesRepository


val Gemini3_Flash_Preview: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-3-flash-preview",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Completion,
        LLMCapability.MultipleChoices,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
    ),
    contextLength = 1_048_576,
    maxOutputTokens = 65_536,
)

/**
 * Available AI models for the application.
 */
enum class AiModel(
    val id: String,
    val displayName: String,
    val koogModel: LLModel,
) {
    FLASH_25("flash_25", "Flash 2.5", GoogleModels.Gemini2_5Flash),
    PRO_25("pro_25", "Pro 2.5", GoogleModels.Gemini2_5Pro),
    FLASH_30("flash_30", "Flash 3.0 - Preview", Gemini3_Flash_Preview);
    companion object {
        val DEFAULT = FLASH_30
        
        fun fromId(id: String): AiModel = entries.find { it.id == id } ?: DEFAULT
    }
}

/**
 * Provides the selected AI model for AI functionality.
 * 
 * Persists model selection via preferences and exposes reactive state.
 */
@SingleIn(AppScope::class)
class AiModelProvider @Inject constructor(
    private val preferencesRepository: AppPreferencesRepository,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val log = logging("AiModelProvider")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Reactive state for the current model */
    private val _selectedModel = MutableStateFlow(AiModel.DEFAULT)
    val selectedModel: StateFlow<AiModel> = _selectedModel.asStateFlow()

    /** Current Koog LLM model */
    val currentKoogModel: LLModel get() = _selectedModel.value.koogModel

    /** List of available models */
    val availableModels: List<AiModel> = AiModel.entries

    init {
        // Load saved model from preferences on startup
        scope.launch {
            loadSelectedModel()
        }
    }

    private suspend fun loadSelectedModel() {
        withContext(dispatcherProvider.io) {
            runCatchingSuspend {
                val prefs = preferencesRepository.load()
                val modelId = prefs.selectedAiModel
                if (modelId != null) {
                    _selectedModel.value = AiModel.fromId(modelId)
                    log.info { "Loaded saved model: ${_selectedModel.value.displayName}" }
                }
            }.exceptionOrNull()?.let { e ->
                log.error(e) { "Failed to load selected model: ${e.message}" }
            }
        }
    }

    /**
     * Select a new AI model and persist the choice.
     */
    suspend fun selectModel(model: AiModel) {
        withContext(dispatcherProvider.io) {
            runCatchingSuspend {
                val prefs = preferencesRepository.load()
                preferencesRepository.save(prefs.copy(selectedAiModel = model.id))
                _selectedModel.value = model
                log.info { "Selected model: ${model.displayName}" }
            }.exceptionOrNull()?.let { e ->
                log.error(e) { "Failed to save model selection: ${e.message}" }
            }
        }
    }
}

