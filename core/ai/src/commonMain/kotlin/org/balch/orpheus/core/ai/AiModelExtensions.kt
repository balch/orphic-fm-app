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

/**
 * Google's floating aliases, chosen deliberately over pinned ids (`gemini-3.7-flash`) so
 * shipped builds pick up new Gemini releases without a store update.
 *
 * The tradeoff is real and accepted: Google hot-swaps these on every release with two weeks'
 * email notice, and an alias may resolve to a preview or experimental build with tighter rate
 * limits. [FLASH_LATEST][AiModel.FLASH_LATEST] is the app default, so a bad swap lands on every
 * new user first. If generation starts failing for no reason we changed, pin these to a
 * concrete id before debugging anything else.
 *
 * Anthropic has no equivalent — its unversioned ids (`claude-opus-5`) are already the alias
 * form, and the dated ones are the pinned snapshots.
 */
val GeminiProLatest: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-pro-latest",
    capabilities = geminiCapabilities,
    contextLength = 1_048_576L,
    maxOutputTokens = 65_536L,
)

/** @see GeminiProLatest for why this is an alias rather than a pinned id. */
val GeminiFlashLatest: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-flash-latest",
    capabilities = geminiCapabilities,
    contextLength = 1_048_576L,
    maxOutputTokens = 65_536L,
)

val AiModelProvider.currentKoogModel: LLModel
    get() = selectedModel.value.llmModel

// Anthropic models newer than Koog 1.3.0's AnthropicModels constants (which stop at Opus 5).
// Capability list mirrors Koog's Opus_5 definition.
private val anthropicCapabilities: List<LLMCapability> = listOf(
    // Temperature mirrors Koog's Opus_5 metadata; the API rejects sampling params on
    // these models — never set a temperature for them.
    LLMCapability.Temperature,
    LLMCapability.Tools,
    // The app never forces a tool; Opus 5.5 400s on tool_choice any/tool, auto is fine.
    LLMCapability.ToolChoice,
    LLMCapability.Vision.Image,
    LLMCapability.Document,
    LLMCapability.Completion,
    LLMCapability.Schema.JSON.Basic,
    LLMCapability.Schema.JSON.Standard,
    LLMCapability.Thinking,
    LLMCapability.PromptCaching,
)

val Opus5_5: LLModel = LLModel(
    provider = LLMProvider.Anthropic,
    id = "claude-opus-5-5",
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
    AnthropicModels.Opus_4_7.id,
    AnthropicModels.Opus_4_8.id,
    AnthropicModels.Opus_5.id,
    Opus5_5.id,
    AnthropicModels.Sonnet_5.id,
    AnthropicModels.Fable_5.id,
)

val LLModel.usesAdaptiveThinking: Boolean
    get() = id in adaptiveThinkingModelIds

/**
 * Models where `output_config.effort` is pinned to `medium`, trading reasoning depth for
 * latency on the heaviest tiers. Opus 5.5 already defaults to `medium`; it is listed so
 * the value stays explicit if Anthropic moves that default. Sonnet 5 is left at the default —
 * it is already the quick one — and Haiku 4.5 must never receive the field at all (pre-4.6
 * models reject it), which is handled by only emitting effort on the adaptive-thinking
 * branch. Fast mode was the other candidate lever here and does not fit: it is Opus-only,
 * doubles the token price, and needs a per-key research-preview grant this app's
 * bring-your-own-key users will not have.
 */
private val reducedEffortModelIds = setOf(AnthropicModels.Opus_5.id, Opus5_5.id, AnthropicModels.Fable_5.id)

/** `output_config.effort` value for [model], or null to leave the API default alone. */
val LLModel.anthropicEffort: String?
    get() = if (id in reducedEffortModelIds) "medium" else null

/**
 * Anthropic models with "preserved thinking": a replayed thinking block is rejected (400)
 * if anything before it in the request changed, for accounts created on or after
 * 2026-08-31. History must be append-only, so Koog's history compression (which summarizes
 * older turns and then replays the trailing tool-call turn with its thinking) is unsafe.
 */
private val appendOnlyHistoryModelIds = setOf(Opus5_5.id)

val LLModel.requiresAppendOnlyHistory: Boolean
    get() = id in appendOnlyHistoryModelIds

/**
 * Model-version map for constructing Koog's AnthropicLLMClient. Koog's own default map
 * is internal AND its request serializer throws "Unsupported model" for any LLModel
 * missing from it — which includes every model newer than the pinned Koog 1.3.0 knows
 * (Opus 5.5). This map is Koog's published defaults plus ours; pass it via
 * AnthropicClientSettings(modelVersionsMap = ...) or the new models cannot serialize a
 * single request. Any catalog addition needs an entry here too.
 */
val anthropicModelVersionsMap: Map<LLModel, String> = mapOf(
    AnthropicModels.Fable_5 to "claude-fable-5",
    AnthropicModels.Haiku_4_5 to "claude-haiku-4-5-20251001",
    AnthropicModels.Sonnet_4 to "claude-sonnet-4-20250514",
    AnthropicModels.Sonnet_4_5 to "claude-sonnet-4-5-20250929",
    AnthropicModels.Sonnet_4_6 to "claude-sonnet-4-6",
    AnthropicModels.Sonnet_5 to "claude-sonnet-5",
    AnthropicModels.Opus_4 to "claude-opus-4-20250514",
    AnthropicModels.Opus_4_1 to "claude-opus-4-1-20250805",
    AnthropicModels.Opus_4_5 to "claude-opus-4-5-20251101",
    AnthropicModels.Opus_4_6 to "claude-opus-4-6",
    AnthropicModels.Opus_4_7 to "claude-opus-4-7",
    AnthropicModels.Opus_4_8 to "claude-opus-4-8",
    AnthropicModels.Opus_5 to "claude-opus-5",
    Opus5_5 to Opus5_5.id,
)
