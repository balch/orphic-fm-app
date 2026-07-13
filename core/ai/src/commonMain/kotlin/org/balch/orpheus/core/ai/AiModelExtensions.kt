package org.balch.orpheus.core.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
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

// Anthropic models newer than Koog 1.0.0's AnthropicModels constants (which stop at
// Opus 4.7). Capability list mirrors Koog's Opus_4_7 definition.
private val anthropicCapabilities: List<LLMCapability> = listOf(
    // Temperature mirrors Koog's Opus_4_7 metadata; the API rejects sampling params on
    // these models — never set a temperature for them.
    LLMCapability.Temperature,
    LLMCapability.Tools,
    LLMCapability.ToolChoice,
    LLMCapability.Vision.Image,
    LLMCapability.Document,
    LLMCapability.Completion,
    LLMCapability.Schema.JSON.Basic,
    LLMCapability.Schema.JSON.Standard,
    LLMCapability.Thinking,
    LLMCapability.PromptCaching,
)

val Opus4_8: LLModel = LLModel(
    provider = LLMProvider.Anthropic,
    id = "claude-opus-4-8",
    capabilities = anthropicCapabilities,
    contextLength = 1_000_000L,
    maxOutputTokens = 128_000L,
)

val Sonnet5: LLModel = LLModel(
    provider = LLMProvider.Anthropic,
    id = "claude-sonnet-5",
    capabilities = anthropicCapabilities,
    contextLength = 1_000_000L,
    maxOutputTokens = 128_000L,
)

/** Premium tier: requires the API org to allow 30-day data retention or requests 400. */
val Fable5: LLModel = LLModel(
    provider = LLMProvider.Anthropic,
    id = "claude-fable-5",
    capabilities = anthropicCapabilities,
    contextLength = 1_000_000L,
    maxOutputTokens = 128_000L,
)

/**
 * Anthropic models that reject `thinking: {type: "enabled", budget_tokens: N}` (400)
 * and take adaptive thinking instead. Opus 4.7+ generation; Haiku 4.5 still uses the
 * budgeted shape.
 */
private val adaptiveThinkingModelIds = setOf(
    AnthropicModels.Opus_4_7.id, Opus4_8.id, Sonnet5.id, Fable5.id,
)

val LLModel.usesAdaptiveThinking: Boolean
    get() = id in adaptiveThinkingModelIds

/**
 * Model-version map for constructing Koog's AnthropicLLMClient. Koog's own default map
 * is internal AND its request serializer throws "Unsupported model" for any LLModel
 * missing from it — which includes every model newer than the pinned Koog 1.0.0 knows
 * (Opus 4.8, Sonnet 5, Fable 5). This map is Koog's published defaults plus ours; pass
 * it via AnthropicClientSettings(modelVersionsMap = ...) or the new models cannot
 * serialize a single request.
 */
val anthropicModelVersionsMap: Map<LLModel, String> = mapOf(
    AnthropicModels.Haiku_4_5 to "claude-haiku-4-5-20251001",
    AnthropicModels.Sonnet_4 to "claude-sonnet-4-20250514",
    AnthropicModels.Sonnet_4_5 to "claude-sonnet-4-5-20250929",
    AnthropicModels.Sonnet_4_6 to "claude-sonnet-4-6",
    AnthropicModels.Opus_4 to "claude-opus-4-20250514",
    AnthropicModels.Opus_4_1 to "claude-opus-4-1-20250805",
    AnthropicModels.Opus_4_5 to "claude-opus-4-5-20251101",
    AnthropicModels.Opus_4_6 to "claude-opus-4-6",
    AnthropicModels.Opus_4_7 to "claude-opus-4-7",
    Opus4_8 to Opus4_8.id,
    Sonnet5 to Sonnet5.id,
    Fable5 to Fable5.id,
)
