package org.balch.orpheus.djapp.di

import android.app.Application
import android.content.Context
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.core.presets.SynthPresetRepository
import java.io.File

@ContributesTo(AppScope::class)
interface AndroidDjAppModule {
    companion object {
        @Provides
        fun provideContext(application: Application): Context = application

        @Provides
        @SingleIn(AppScope::class)
        fun provideAppPreferencesRepository(context: Context): AppPreferencesRepository {
            val log = logging("AndroidDjAppPrefs")
            val json = Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
            val settingsFile: File by lazy {
                File(context.filesDir, "settings.json")
            }
            return object : BaseAppPreferencesRepository() {
                override suspend fun load(): AppPreferences {
                    return runCatchingSuspend {
                        if (settingsFile.exists()) {
                            json.decodeFromString<AppPreferences>(settingsFile.readText())
                        } else {
                            AppPreferences()
                        }
                    }.onFailure { e ->
                        log.error { "Failed to load preferences: ${e.message}" }
                    }.getOrDefault(AppPreferences())
                }

                override suspend fun save(preferences: AppPreferences) {
                    runCatchingSuspend {
                        settingsFile.writeText(json.encodeToString(preferences))
                    }.onFailure { e ->
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
                override suspend fun list(): List<SynthPreset> = emptyList()
                override suspend fun delete(name: String) {}
            }
    }
}
