package org.balch.orpheus.core.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.preferences.AppPreferencesRepository

/**
 * Available AI models for the application.
 */
enum class AiModel(
    val id: String,
    val displayName: String,
    val aiProvider: AiProvider,
    val llmModel: LLModel,
) {
    HAIKU("Haiku", "Haiku 4.5", AiProvider.Anthropic, AnthropicModels.Haiku_4_5),
    SONNET("Sonnet", "Sonnet 5", AiProvider.Anthropic, Sonnet5),
    OPUS("opus", "Opus 5", AiProvider.Anthropic, Opus5),
    FABLE("fable", "Fable 5", AiProvider.Anthropic, AnthropicModels.Fable_5),
    // Google slots ride floating aliases, so the display name carries no version number —
    // it would go stale the first time Google hot-swaps. See GeminiProLatest.
    PRO_LATEST("pro_latest", "Pro (latest)", AiProvider.Google, GeminiProLatest),
    FLASH_LATEST("flash_latest", "Flash (latest)", AiProvider.Google, GeminiFlashLatest);

    companion object {
        val DEFAULT = FLASH_LATEST

        /**
         * Ids of retired entries, remapped to their successor tier so an upgrade keeps the
         * user's choice instead of silently dropping them onto [DEFAULT]. Ids of tiers that
         * no longer exist at all are deliberately absent — those should fall through.
         */
        private val supersededIds: Map<String, AiModel> = mapOf(
            "pro_31" to PRO_LATEST,
            "flash_35" to FLASH_LATEST,
        )

        fun fromId(id: String): AiModel =
            entries.find { it.id == id } ?: supersededIds[id] ?: DEFAULT
    }
}

/**
 * Provides the selected AI model for AI functionality.
 * 
 * Persists model selection via preferences and exposes reactive state.
 */
@Inject
@SingleIn(AppScope::class)
class AiModelProvider(
    private val preferencesRepository: AppPreferencesRepository,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: AppCoroutineScope,
) {
    private val log = logging("AiModelProvider")

    /** Reactive state for the current model */
    private val _selectedModel = MutableStateFlow(AiModel.DEFAULT)
    val selectedModel: StateFlow<AiModel> = _selectedModel.asStateFlow()

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
                    log.debug { "Loaded saved model: ${_selectedModel.value.displayName}" }
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
                preferencesRepository.update { it.copy(selectedAiModel = model.id) }
                _selectedModel.value = model
                log.debug { "Selected model: ${model.displayName}" }
            }.exceptionOrNull()?.let { e ->
                log.error(e) { "Failed to save model selection: ${e.message}" }
            }
        }
    }
}

