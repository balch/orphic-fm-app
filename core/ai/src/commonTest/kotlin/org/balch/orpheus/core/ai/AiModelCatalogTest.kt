package org.balch.orpheus.core.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiModelCatalogTest {

    @Test
    fun defaultIsFlash35() {
        assertEquals(AiModel.FLASH_35, AiModel.DEFAULT)
    }

    @Test
    fun catalogIsExactlyTheApprovedLineupInPickerOrder() {
        assertEquals(
            listOf("HAIKU", "SONNET", "OPUS", "FABLE", "PRO_31", "FLASH_35"),
            AiModel.entries.map { it.name },
        )
    }

    @Test
    fun removedAndUnknownIdsFallBackToDefault() {
        assertEquals(AiModel.DEFAULT, AiModel.fromId("pro_30"))
        assertEquals(AiModel.DEFAULT, AiModel.fromId("flash_30"))
        assertEquals(AiModel.DEFAULT, AiModel.fromId("nonsense"))
    }

    @Test
    fun tierIdsAreStableSoSavedSelectionsUpgrade() {
        assertEquals(AiModel.SONNET, AiModel.fromId("Sonnet"))
        assertEquals(AiModel.OPUS, AiModel.fromId("opus"))
        assertEquals(AiModel.FABLE, AiModel.fromId("fable"))
    }

    @Test
    fun anthropicEntriesWireTheLatestModelIds() {
        assertEquals("claude-sonnet-5", AiModel.SONNET.llmModel.id)
        assertEquals("claude-opus-4-8", AiModel.OPUS.llmModel.id)
        assertEquals("claude-fable-5", AiModel.FABLE.llmModel.id)
        assertEquals("Sonnet 5", AiModel.SONNET.displayName)
        assertEquals("Opus 4.8", AiModel.OPUS.displayName)
        assertEquals("Fable 5", AiModel.FABLE.displayName)
        assertEquals(AiProvider.Anthropic, AiModel.SONNET.aiProvider)
        assertEquals(AiProvider.Anthropic, AiModel.OPUS.aiProvider)
        assertEquals(AiProvider.Anthropic, AiModel.FABLE.aiProvider)
    }

    @Test
    fun adaptiveThinkingPredicateCoversOpus47PlusGeneration() {
        assertTrue(Opus4_8.usesAdaptiveThinking)
        assertTrue(Sonnet5.usesAdaptiveThinking)
        assertTrue(Fable5.usesAdaptiveThinking)
        assertTrue(AnthropicModels.Opus_4_7.usesAdaptiveThinking)
        assertFalse(AnthropicModels.Haiku_4_5.usesAdaptiveThinking)
        assertFalse(Gemini3_1ProPreview.usesAdaptiveThinking)
        assertFalse(Gemini3_5FlashPreview.usesAdaptiveThinking)
    }

    @Test
    fun anthropicVersionsMapCoversEveryAnthropicCatalogModel() {
        // Koog's request serializer throws "Unsupported model" for any LLModel missing
        // from the map the client is constructed with — a catalog entry absent here
        // cannot send a single request (this is how Sonnet 5 broke on first use).
        AiModel.entries
            .filter { it.aiProvider == AiProvider.Anthropic }
            .forEach { entry ->
                val version = anthropicModelVersionsMap[entry.llmModel]
                assertTrue(
                    !version.isNullOrBlank(),
                    "anthropicModelVersionsMap is missing ${entry.name} (${entry.llmModel.id})",
                )
            }
    }
}
