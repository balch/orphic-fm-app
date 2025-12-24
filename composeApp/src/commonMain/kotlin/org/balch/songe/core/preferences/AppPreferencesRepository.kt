package org.balch.songe.core.preferences

expect class AppPreferencesRepository() {
    suspend fun load(): AppPreferences
    suspend fun save(preferences: AppPreferences)
}
