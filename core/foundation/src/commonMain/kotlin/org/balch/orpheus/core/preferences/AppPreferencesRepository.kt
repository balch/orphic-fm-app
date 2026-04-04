package org.balch.orpheus.core.preferences

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repository for persisting app preferences.
 * Platform-specific implementations are provided via DI.
 */
interface AppPreferencesRepository {
    suspend fun load(): AppPreferences
    suspend fun save(preferences: AppPreferences)

    /**
     * Atomic read-modify-write: loads current prefs, applies [transform], and saves.
     * Serialized by a mutex so concurrent callers don't clobber each other's fields.
     */
    suspend fun update(transform: (AppPreferences) -> AppPreferences)
}

/**
 * Mixin that provides a mutex-based [update] for any [AppPreferencesRepository].
 * Platform implementations should extend this instead of implementing the interface directly.
 */
abstract class BaseAppPreferencesRepository : AppPreferencesRepository {
    private val mutex = Mutex()

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        mutex.withLock {
            val current = load()
            save(transform(current))
        }
    }
}
