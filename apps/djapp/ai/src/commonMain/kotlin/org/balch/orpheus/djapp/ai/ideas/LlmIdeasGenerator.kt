package org.balch.orpheus.djapp.ai.ideas

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.ai.AiKeyRepository
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.ai.anthropicModelVersionsMap
import org.balch.orpheus.core.ai.deriveAiProviderFromKey
import org.balch.orpheus.core.di.FeatureScope

private val IDEAS_SYSTEM = """
    You suggest what a DJ could ask an AI music partner to do next with the vibe that is playing.
    Reply with only a JSON array of 2 or 3 objects, no other text:
    [{"text": "...", "prompt": "...", "mix": true}]
    - text: the idea as the user reads it on a card, under 90 characters.
    - prompt: the message sent to the partner when the user taps Try, written as the user would say it.
    - mix: true when the idea changes the Mixer (track levels, pans, reverb or delay sends, mutes, solos, macro knobs).
    Build on the user's latest moves and the conversation. Never repeat an idea already done.
""".trimIndent()

private const val MIXER_CLOSED_RULE =
    "The Mixer is closed: every idea has mix false and is about the song, sections, voices or melody."

@Serializable
private class IdeaJson(val text: String = "", val prompt: String = "", val mix: Boolean = false)

private val ideasJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val CODE_FENCE = Regex("```[A-Za-z]*")

/** The cards the strip shows at most. */
private const val MAX_IDEAS = 3

/**
 * The model's reply as cards: the first JSON array of ideas in it, past any code fence or a preamble that has
 * brackets of its own, blank cards dropped, mix ideas dropped while the Mixer is closed, and at most three.
 * Empty when no array parses.
 */
internal fun parseIdeas(raw: String, mixerOpen: Boolean): List<Idea> {
    val items = firstIdeasArray(raw.replace(CODE_FENCE, "")) ?: return emptyList()
    return items
        .filter { it.text.isNotBlank() && (mixerOpen || !it.mix) }
        .take(MAX_IDEAS)
        .map { Idea(text = it.text.trim(), prompt = it.prompt.trim().ifEmpty { it.text.trim() }, mix = it.mix) }
}

/** Tries each '[' in order against each later ']' from the last back; the first non-empty array of ideas wins. */
private fun firstIdeasArray(text: String): List<IdeaJson>? {
    val ends = text.indices.filter { text[it] == ']' }.reversed()
    for (start in text.indices) {
        if (text[start] != '[') continue
        for (end in ends) {
            if (end <= start) break
            val items = try {
                ideasJson.decodeFromString<List<IdeaJson>>(text.substring(start, end + 1))
            } catch (e: Exception) {
                continue
            }
            if (items.isNotEmpty()) return items
        }
    }
    return null
}

/** The user message for one ideas call. */
internal fun ideasPrompt(input: IdeasInput): String = buildString {
    append("[Vibe] ").append(input.vibeSummary)
    append("\n[Mixer] ").append(if (input.mixerOpen) "open" else "closed")
    if (input.mixSnapshot.isNotBlank()) append("\n[Mix]\n").append(input.mixSnapshot)
    if (input.recentMoves.isNotEmpty()) append("\n[User's recent moves, oldest first]\n").append(input.recentMoves.joinToString("\n"))
    if (input.recentChat.isNotEmpty()) append("\n[Recent conversation]\n").append(input.recentChat.joinToString("\n"))
    if (!input.mixerOpen) append("\n\n").append(MIXER_CLOSED_RULE)
}

/**
 * One short call to the selected model per refresh: no tools, and nothing shared with the agent's history.
 * No key, or any failure, gives an empty list.
 */
@Inject
@SingleIn(FeatureScope::class)
@ContributesBinding(FeatureScope::class, binding = binding<IdeasGenerator>())
class LlmIdeasGenerator(
    private val aiKeyRepository: AiKeyRepository,
    private val aiModelProvider: AiModelProvider,
) : IdeasGenerator {
    private val log = logging("LlmIdeasGenerator")

    /** The executor for the last key used; a new key builds a new one. */
    private var cached: Pair<String, MultiLLMPromptExecutor>? = null

    override suspend fun ideas(input: IdeasInput): List<Idea> {
        val model = aiModelProvider.selectedModel.value
        return try {
            val apiKey = aiKeyRepository.getKey(model.aiProvider)?.first ?: return emptyList()
            val request = prompt("DjIdeas") {
                system(IDEAS_SYSTEM)
                user(ideasPrompt(input))
            }
            val reply = executorFor(apiKey).execute(request, model.llmModel).textContent()
            parseIdeas(reply, input.mixerOpen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "Ideas call failed: ${e.message}" }
            emptyList()
        }
    }

    private fun executorFor(apiKey: String): MultiLLMPromptExecutor {
        cached?.let { (key, executor) -> if (key == apiKey) return executor }
        return MultiLLMPromptExecutor(llmClient(apiKey)).also { cached = apiKey to it }
    }

    /** Mirrors OrpheusAgent.createAgent's client choice. */
    private fun llmClient(apiKey: String): LLMClient {
        val httpFactory = KtorKoogHttpClient.Factory()
        return when (val provider = deriveAiProviderFromKey(key = apiKey)) {
            AiProvider.Google -> GoogleLLMClient(apiKey, httpClientFactory = httpFactory)
            // Koog's serializer needs the extended versions map for models newer than its constants.
            AiProvider.Anthropic -> AnthropicLLMClient(
                apiKey,
                settings = AnthropicClientSettings(modelVersionsMap = anthropicModelVersionsMap),
                httpClientFactory = httpFactory,
            )
            AiProvider.OpenAI -> OpenAILLMClient(apiKey, httpClientFactory = httpFactory)
            else -> error("Unsupported AI provider: $provider")
        }
    }
}
