package org.balch.orpheus.features.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.balch.orpheus.core.ai.Opus4_8
import org.balch.orpheus.core.ai.Opus5
import org.balch.orpheus.core.ai.Sonnet5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AnthropicThinkingParamsTest {

    @Test
    fun adaptiveModelsSendRawAdaptiveThinkingWithSummarizedDisplay() {
        for (model in listOf(Opus5, Opus4_8, Sonnet5, AnthropicModels.Fable_5)) {
            val params = anthropicThinkingParams(model)
            assertNull(params.thinking, "typed thinking must stay null for ${model.id}")
            val thinking = params.additionalProperties?.get("thinking")?.jsonObject
                ?: fail("missing raw thinking object for ${model.id}")
            assertEquals("adaptive", thinking["type"]?.jsonPrimitive?.content)
            assertEquals("summarized", thinking["display"]?.jsonPrimitive?.content)
            assertEquals(16_000, params.maxTokens, "explicit maxTokens for ${model.id}")
            assertEquals(
                AnthropicCacheControl.Default,
                params.cacheControl,
                "request-level cache_control for ${model.id}",
            )
        }
    }

    @Test
    fun heavyTiersRequestMediumEffort() {
        for (model in listOf(Opus5, AnthropicModels.Fable_5)) {
            val effort = anthropicThinkingParams(model)
                .additionalProperties?.get("output_config")?.jsonObject
                ?.get("effort")?.jsonPrimitive?.content
            assertEquals("medium", effort, "output_config.effort for ${model.id}")
        }
    }

    @Test
    fun sonnetKeepsTheApiDefaultEffort() {
        // Sonnet is already the quick tier; dialling it down buys latency we don't need
        // and costs reasoning we do. Absent field == API default of "high".
        assertNull(anthropicThinkingParams(Sonnet5).additionalProperties?.get("output_config"))
    }

    @Test
    fun haikuKeepsBudgetedThinking() {
        val params = anthropicThinkingParams(AnthropicModels.Haiku_4_5)
        assertTrue(params.thinking is AnthropicThinking.Enabled)
        // Also guards effort: pre-4.6 models reject output_config, and this branch is the
        // only thing keeping the field off Haiku's request.
        assertNull(params.additionalProperties)
        // Must exceed the 4096 thinking budget: Koog's request default (2048) does not,
        // and Anthropic 400s the pairing ("max_tokens must be greater than
        // thinking.budget_tokens").
        assertEquals(16_000, params.maxTokens)
        assertEquals(AnthropicCacheControl.Default, params.cacheControl, "request-level cache_control")
    }
}
