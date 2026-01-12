package org.balch.orpheus.core.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

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

val AiModel.koogModel: LLModel
    get() = when (this) {
        AiModel.OPUS -> AnthropicModels.Opus_4_5
        AiModel.FLASH_25 -> GoogleModels.Gemini2_5Flash
        AiModel.PRO_25 -> GoogleModels.Gemini2_5Pro
        AiModel.FLASH_30 -> Gemini3_Flash_Preview
    }

val AiModelProvider.currentKoogModel: LLModel
    get() = selectedModel.value.koogModel
