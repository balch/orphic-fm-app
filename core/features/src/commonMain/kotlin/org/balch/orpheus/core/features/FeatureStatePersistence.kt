package org.balch.orpheus.core.features

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository

/**
 * Controls whether [FeatureStatePersistence.bind] restores saved state on startup.
 */
enum class RestoreStrategy {
    /** Restore saved state on startup. Used by apps without a preset system (DJ app). */
    USER_PREFERENCES,
    /** Save state but don't restore — presets are the source of truth (Orpheus). */
    PRESET,
}

/**
 * Reusable persistence for feature ViewModels.
 *
 * Debounce-saves state changes to [AppPreferencesRepository] (always).
 * Restores saved state on startup only when caller passes [RestoreStrategy.USER_PREFERENCES].
 */
@SingleIn(FeatureScope::class)
@Inject
class FeatureStatePersistence(
    private val appPreferencesRepository: AppPreferencesRepository,
    private val dispatcherProvider: DispatcherProvider,
    private val scope: FeatureCoroutineScope,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Bind a feature's state flow for persistence.
     *
     * @param stateFlow the feature's UI state flow to observe
     * @param serializer kotlinx.serialization serializer for the state type
     * @param reader extracts the JSON string from [AppPreferences]
     * @param writer returns a new [AppPreferences] with the JSON string written
     * @param restoreStrategy whether to restore on startup or let presets handle it
     * @param stripTransient removes transient fields before saving (e.g., peak meters)
     * @param onRestore pushes restored values into control flows
     */
    fun <T : Any> bind(
        stateFlow: StateFlow<T>,
        serializer: KSerializer<T>,
        reader: (AppPreferences) -> String?,
        writer: (AppPreferences, String) -> AppPreferences,
        restoreStrategy: RestoreStrategy,
        stripTransient: (T) -> T = { it },
        onRestore: (T) -> Unit,
    ) {
        val tag = serializer.descriptor.serialName

        // Debounced save — always active in both apps
        scope.launch(dispatcherProvider.io) {
            stateFlow.drop(1).debounce(2_000L).collect { state ->
                val toSave = stripTransient(state)
                val jsonStr = json.encodeToString(serializer, toSave)
                appPreferencesRepository.update { writer(it, jsonStr) }
                log.debug { "Saved $tag" }
            }
        }

        // Restore on startup only when strategy is USER_PREFERENCES
        if (restoreStrategy == RestoreStrategy.USER_PREFERENCES) {
            scope.launch(dispatcherProvider.io) {
                val jsonStr = appPreferencesRepository.load().let(reader)
                if (jsonStr == null) {
                    log.debug { "No saved state for $tag" }
                    return@launch
                }
                val saved = try {
                    json.decodeFromString(serializer, jsonStr)
                } catch (e: Exception) {
                    log.warn(e) { "Failed to decode $tag" }
                    return@launch
                }
                log.info { "Restoring $tag" }
                onRestore(saved)
            }
        }
    }

    companion object {
        private val log = logging("FeatureStatePersistence")
    }
}
