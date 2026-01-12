package org.balch.orpheus.core.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.preferences.AppPreferencesRepository

/**
 * Provides the Gemini API key for AI functionality.
 * 
 * Priority:
 * 1. Build-time key from BuildKonfig (local.properties)
 * 2. User-provided key stored in preferences
 * 
 * Exposes reactive state so UI can respond to key changes.
 */
@SingleIn(AppScope::class)
class AiKeyRepository @Inject constructor(
    private val preferencesRepository: AppPreferencesRepository,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val log = logging("AiKeyRepository")

    private var lastUsedKey: Triple<AiProvider, String, Boolean>? = null

    val isApiKeySet: Boolean get() = lastUsedKey != null
    val isUserProvidedKey: Boolean get() = lastUsedKey?.third == true

    suspend fun getKey(aiProvider: AiProvider): Pair<String, Boolean>? =
        withContext(dispatcherProvider.io) {
            // Priority: 1. Build-time default key, 2. User-provided key from preferences
            val result = aiProvider.defaultKey()?.let { key ->
                key to false // false = not user-provided
            } ?: getPreferenceKey(aiProvider)?.let { key ->
                key to true // true = user-provided
            }
            
            // Track last used key for debugging/reference
            result?.also { (key, isUserKey) ->
                lastUsedKey = Triple(aiProvider, key, isUserKey)
            }
        }

    private suspend fun getPreferenceKey(aiProvider: AiProvider): String? =
        runCatchingSuspend {
            preferencesRepository.load().userApiKeys[aiProvider.id]
        }.getOrNull()

    suspend fun setKey(aiProvider: AiProvider, key: String): Boolean =
        withContext(dispatcherProvider.io) {
            runCatchingSuspend {
                if (!aiProvider.validateKeyFormat(key)) {
                    log.warn { "Invalid API key format" }
                    return@withContext false
                }

                val prefs = preferencesRepository.load()
                val updatedKeys = prefs.userApiKeys.toMutableMap().apply {
                    put(aiProvider.id, key)
                }
                preferencesRepository.save(prefs.copy(userApiKeys = updatedKeys))
                log.debug { "Saved user-provided API key" }
                true
            }
                .onSuccess { _ -> lastUsedKey = Triple(aiProvider, key, false) }
                .onFailure { e ->
                    log.error(e) { "Failed to save user API key: $aiProvider" }
                }.getOrNull() ?: false
        }

    /**
     * Remove the user-provided API key.
     */
    suspend fun clearApiKey(aiProvider: AiProvider) {
        withContext(dispatcherProvider.io) {
            runCatchingSuspend {
                val prefs = preferencesRepository.load()
                val updatedKeys = prefs.userApiKeys.toMutableMap().apply {
                    remove(aiProvider.id)
                }
                preferencesRepository.save(prefs.copy(userApiKeys = updatedKeys))
                log.debug { "Cleared user-provided API key" }
            }.onSuccess { _ -> lastUsedKey = null}
            .onFailure { e ->
                log.e(e) { "Failed to clear user API key: $aiProvider" }
            }
        }
    }
}

