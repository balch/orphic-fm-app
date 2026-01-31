package org.balch.orpheus.core.ai

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

val AiModelProvider.currentKoogModel: LLModel
    get() = selectedModel.value.llmModel
