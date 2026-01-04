package org.balch.orpheus.core.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.balch.orpheus.BuildKonfig
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import kotlin.coroutines.cancellation.CancellationException

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
class GeminiKeyProvider @Inject constructor(
    private val preferencesRepository: AppPreferencesRepository,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val log = logging("GeminiKeyProvider")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /** Build-time key from local.properties */
    private val buildTimeKey: String? = BuildKonfig.GEMINI_API_KEY.takeIf { it.isNotEmpty() }
    
    /** Reactive state for the current API key */
    private val _apiKeyState = MutableStateFlow<String?>(buildTimeKey)
    val apiKeyState: StateFlow<String?> = _apiKeyState.asStateFlow()
    
    /** Reactive state for whether a user-provided key is active */
    private val _isUserProvidedKey = MutableStateFlow(false)
    val isUserProvidedKey: StateFlow<Boolean> = _isUserProvidedKey.asStateFlow()
    
    /** Current API key (build-time or user-provided) */
    val apiKey: String? get() = _apiKeyState.value
    
    /** Whether any API key is configured */
    val isApiKeySet: Boolean get() = !_apiKeyState.value.isNullOrEmpty()
    
    init {
        // Load user key from preferences on startup
        scope.launch {
            loadUserKey()
        }
    }
    
    private suspend fun loadUserKey() {
        withContext(dispatcherProvider.io) {
            try {
                val prefs = preferencesRepository.load()
                val userKey = prefs.userApiKeys[AiProvider.GOOGLE.id]

                if (buildTimeKey.isNullOrEmpty() && !userKey.isNullOrEmpty()) {
                    log.debug { "Loaded user-provided API key" }
                    _apiKeyState.value = userKey
                    _isUserProvidedKey.value = true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log.error { "Failed to load user API key: ${e.message}" }
            }
        }
    }
    
    /**
     * Save a user-provided API key.
     * Only has effect if no build-time key is configured.
     */
    suspend fun saveApiKey(key: String): Boolean =
        withContext(dispatcherProvider.io) {
            if (!buildTimeKey.isNullOrEmpty()) {
                log.warn { "Cannot save user key - build-time key is configured" }
                return@withContext false
            }

            if (!AiProvider.GOOGLE.validateKeyFormat(key)) {
                log.warn { "Invalid API key format" }
                return@withContext false
            }

            return@withContext try {
                val prefs = preferencesRepository.load()
                val updatedKeys = prefs.userApiKeys.toMutableMap().apply {
                    put(AiProvider.GOOGLE.id, key)
                }
                preferencesRepository.save(prefs.copy(userApiKeys = updatedKeys))

                _apiKeyState.value = key
                _isUserProvidedKey.value = true
                log.debug { "Saved user-provided API key" }
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log.error { "Failed to save API key: ${e.message}" }
                false
            }
        }

    /**
     * Remove the user-provided API key.
     */
    suspend fun clearApiKey() {
        withContext(dispatcherProvider.io) {
            if (!_isUserProvidedKey.value) {
                log.debug { "No user key to clear" }
                return@withContext
            }

            try {
                val prefs = preferencesRepository.load()
                val updatedKeys = prefs.userApiKeys.toMutableMap().apply {
                    remove(AiProvider.GOOGLE.id)
                }
                preferencesRepository.save(prefs.copy(userApiKeys = updatedKeys))

                _apiKeyState.value = null
                _isUserProvidedKey.value = false
                log.debug { "Cleared user-provided API key" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error { "Failed to clear API key: ${e.message}" }
            }
        }
    }
}
