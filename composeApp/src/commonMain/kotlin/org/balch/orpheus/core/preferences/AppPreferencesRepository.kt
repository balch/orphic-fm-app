package org.balch.orpheus.core.preferences

/**
 * Repository for persisting app preferences.
 * Platform-specific implementations are provided via DI.
 */
interface AppPreferencesRepository {
    suspend fun load(): AppPreferences
    suspend fun save(preferences: AppPreferences)
}
