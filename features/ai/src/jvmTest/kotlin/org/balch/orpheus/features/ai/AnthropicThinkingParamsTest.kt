package org.balch.orpheus.features.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.balch.orpheus.core.ai.Fable5
import org.balch.orpheus.core.ai.Opus4_8
import org.balch.orpheus.core.ai.Sonnet5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AnthropicThinkingParamsTest {

    @Test
    fun adaptiveModelsSendRawAdaptiveThinkingWithSummarizedDisplay() {
        for (model in listOf(Opus4_8, Sonnet5, Fable5)) {
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
    fun haikuKeepsBudgetedThinking() {
        val params = anthropicThinkingParams(AnthropicModels.Haiku_4_5)
        assertTrue(params.thinking is AnthropicThinking.Enabled)
        assertNull(params.additionalProperties)
        // Must exceed the 4096 thinking budget: Koog's request default (2048) does not,
        // and Anthropic 400s the pairing ("max_tokens must be greater than
        // thinking.budget_tokens").
        assertEquals(16_000, params.maxTokens)
        assertEquals(AnthropicCacheControl.Default, params.cacheControl, "request-level cache_control")
    }
}
