package org.balch.orpheus.core.ai

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

private val geminiCapabilities: List<LLMCapability> = listOf(
    LLMCapability.Temperature,
    LLMCapability.Completion,
    LLMCapability.MultipleChoices,
    LLMCapability.Tools,
    LLMCapability.ToolChoice,
    LLMCapability.Schema.JSON.Basic,
    LLMCapability.Schema.JSON.Standard,
    LLMCapability.Thinking,
)

val Gemini3_1ProPreview: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-3.1-pro-preview",
    capabilities = geminiCapabilities,
    contextLength = 1_048_576L,
    maxOutputTokens = 65_536L,
)

val Gemini3_5FlashPreview: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-3.5-flash",
    capabilities = geminiCapabilities,
    contextLength = 1_048_576L,
    maxOutputTokens = 65_536L,
)

val AiModelProvider.currentKoogModel: LLModel
    get() = selectedModel.value.llmModel
