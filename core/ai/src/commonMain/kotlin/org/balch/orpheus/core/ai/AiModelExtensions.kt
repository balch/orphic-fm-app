package org.balch.orpheus.core.ai

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

// Gemini 3.0 Flash Preview — not yet in Koog's built-in GoogleModels
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
    contextLength = 1_048_576L,
    maxOutputTokens = 65_536L,
)

// Gemini 3.1 Pro Preview — refined reasoning, software engineering & agentic workflows
val Gemini3_1_Pro_Preview: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-3.1-pro-preview",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Completion,
        LLMCapability.MultipleChoices,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
    ),
    contextLength = 1_048_576L,
    maxOutputTokens = 65_536L,
)

// Claude Opus 4.6 — not yet in published Koog 0.6.3; declared manually
val Claude_Opus_4_6: LLModel = LLModel(
    provider = LLMProvider.Anthropic,
    id = "claude-opus-4-6",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Vision.Image,
        LLMCapability.Document,
        LLMCapability.Completion,
    ),
    contextLength = 200_000L,
    maxOutputTokens = 1_000_000L,
)

val AiModelProvider.currentKoogModel: LLModel
    get() = selectedModel.value.llmModel
