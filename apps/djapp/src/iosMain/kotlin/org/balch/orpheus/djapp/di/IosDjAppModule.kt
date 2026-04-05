package org.balch.orpheus.djapp.di

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.core.presets.SynthPresetRepository
import platform.Foundation.NSUserDefaults

@ContributesTo(AppScope::class)
interface IosDjAppModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideAppPreferencesRepository(): AppPreferencesRepository {
            val log = logging("IosDjAppPrefs")
            val json = Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
            val settingsKey = "orpheus_dj_preferences"
            return object : BaseAppPreferencesRepository() {
                override suspend fun load(): AppPreferences {
                    return try {
                        val jsonString = NSUserDefaults.standardUserDefaults.stringForKey(settingsKey)
                        if (jsonString != null) {
                            json.decodeFromString<AppPreferences>(jsonString)
                        } else {
                            AppPreferences()
                        }
                    } catch (e: Exception) {
                        log.error { "Failed to load preferences: ${e.message}" }
                        AppPreferences()
                    }
                }

                override suspend fun save(preferences: AppPreferences) {
                    try {
                        val jsonString = json.encodeToString(preferences)
                        NSUserDefaults.standardUserDefaults.setObject(jsonString, forKey = settingsKey)
                    } catch (e: Exception) {
                        log.error { "Failed to save preferences: ${e.message}" }
                    }
                }
            }
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideSynthPresetRepository(): SynthPresetRepository =
            object : SynthPresetRepository {
                override suspend fun save(preset: SynthPreset) {}
                override suspend fun load(name: String): SynthPreset? = null
                override suspend fun delete(name: String) {}
                override suspend fun list(): List<SynthPreset> = emptyList()
            }
    }
}
