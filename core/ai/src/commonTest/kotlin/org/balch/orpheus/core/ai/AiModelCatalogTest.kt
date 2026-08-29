package org.balch.orpheus.core.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiModelCatalogTest {

    @Test
    fun defaultIsFlashLatest() {
        assertEquals(AiModel.FLASH_LATEST, AiModel.DEFAULT)
    }

    @Test
    fun catalogIsExactlyTheApprovedLineupInPickerOrder() {
        assertEquals(
            listOf("HAIKU", "SONNET", "OPUS", "FABLE", "PRO_LATEST", "FLASH_LATEST"),
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
    fun supersededGoogleIdsLandOnTheirSuccessorTier() {
        // The Google slots were renamed when they moved onto floating aliases; a user who
        // had picked Pro must not be silently dropped onto the Flash default.
        assertEquals(AiModel.PRO_LATEST, AiModel.fromId("pro_31"))
        assertEquals(AiModel.FLASH_LATEST, AiModel.fromId("flash_35"))
    }

    @Test
    fun anthropicEntriesWireTheLatestModelIds() {
        assertEquals("claude-sonnet-5", AiModel.SONNET.llmModel.id)
        assertEquals("claude-opus-5", AiModel.OPUS.llmModel.id)
        assertEquals("claude-fable-5", AiModel.FABLE.llmModel.id)
        assertEquals("Sonnet 5", AiModel.SONNET.displayName)
        assertEquals("Opus 5", AiModel.OPUS.displayName)
        assertEquals("Fable 5", AiModel.FABLE.displayName)
        assertEquals(AiProvider.Anthropic, AiModel.SONNET.aiProvider)
        assertEquals(AiProvider.Anthropic, AiModel.OPUS.aiProvider)
        assertEquals(AiProvider.Anthropic, AiModel.FABLE.aiProvider)
    }

    @Test
    fun googleEntriesRideFloatingAliases() {
        // Deliberate: these upgrade without a store release. If Google hot-swaps a bad
        // build, this is the line to pin. See GeminiProLatest.
        assertEquals("gemini-pro-latest", AiModel.PRO_LATEST.llmModel.id)
        assertEquals("gemini-flash-latest", AiModel.FLASH_LATEST.llmModel.id)
        assertEquals(AiProvider.Google, AiModel.PRO_LATEST.aiProvider)
        assertEquals(AiProvider.Google, AiModel.FLASH_LATEST.aiProvider)
    }

    @Test
    fun adaptiveThinkingPredicateCoversOpus47PlusGeneration() {
        assertTrue(Opus5.usesAdaptiveThinking)
        assertTrue(Opus4_8.usesAdaptiveThinking)
        assertTrue(Sonnet5.usesAdaptiveThinking)
        assertTrue(AnthropicModels.Fable_5.usesAdaptiveThinking)
        assertTrue(AnthropicModels.Opus_4_7.usesAdaptiveThinking)
        assertFalse(AnthropicModels.Haiku_4_5.usesAdaptiveThinking)
        assertFalse(GeminiProLatest.usesAdaptiveThinking)
        assertFalse(GeminiFlashLatest.usesAdaptiveThinking)
    }

    @Test
    fun onlyTheHeavyTiersDialEffortDown() {
        assertEquals("medium", Opus5.anthropicEffort)
        assertEquals("medium", AnthropicModels.Fable_5.anthropicEffort)
        assertNull(Sonnet5.anthropicEffort)
        // Haiku 4.5 rejects output_config outright — it must never carry a value.
        assertNull(AnthropicModels.Haiku_4_5.anthropicEffort)
    }

    @Test
    fun everyEffortModelAlsoUsesAdaptiveThinking() {
        // anthropicThinkingParams only emits output_config on the adaptive branch, so an
        // effort value on a budgeted model would be silently dropped.
        AiModel.entries
            .filter { it.aiProvider == AiProvider.Anthropic && it.llmModel.anthropicEffort != null }
            .forEach { entry ->
                assertTrue(
                    entry.llmModel.usesAdaptiveThinking,
                    "${entry.name} sets effort but is not on the adaptive-thinking branch",
                )
            }
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
