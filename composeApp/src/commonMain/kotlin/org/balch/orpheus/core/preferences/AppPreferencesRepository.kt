package org.balch.orpheus.core.preferences

expect class AppPreferencesRepository() {
    suspend fun load(): AppPreferences
    suspend fun save(preferences: AppPreferences)
}
