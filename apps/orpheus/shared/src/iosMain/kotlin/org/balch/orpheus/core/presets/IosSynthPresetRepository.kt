package org.balch.orpheus.core.presets

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of SynthPresetRepository using NSUserDefaults.
 */
@SingleIn(AppScope::class)
@Inject
class IosSynthPresetRepository : SynthPresetRepository {
    private val log = logging("IosSynthPresetRepository")

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val keyPrefix = "orpheus_preset_"
    private fun keyForPreset(name: String): String {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$keyPrefix$safeName"
    }

    override suspend fun save(preset: SynthPreset) {

        try {
            val key = keyForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            NSUserDefaults.standardUserDefaults.setObject(jsonString, forKey = key)
            log.info { "Saved preset '${preset.name}'" }
        } catch (e: Exception) {
            log.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    override suspend fun load(name: String): SynthPreset? {

        return try {
            val key = keyForPreset(name)
            val jsonString = NSUserDefaults.standardUserDefaults.stringForKey(key)
            if (jsonString != null) {
                val preset = json.decodeFromString<SynthPreset>(jsonString)
                log.info { "Loaded preset '$name'" }
                preset
            } else {
                log.debug { "No preset found: '$name'" }
                null
            }
        } catch (e: Exception) {
            log.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }

    override suspend fun delete(name: String) {

        try {
            val key = keyForPreset(name)
            NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
            log.info { "Deleted preset '$name'" }
        } catch (e: Exception) {
            log.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }

    override suspend fun list(): List<SynthPreset> {

        return try {
            val defaults = NSUserDefaults.standardUserDefaults
            val dict = defaults.dictionaryRepresentation()
            val presets = mutableListOf<SynthPreset>()
            dict.keys.forEach { key ->
                val keyStr = key as? String ?: return@forEach
                if (keyStr.startsWith(keyPrefix)) {
                    try {
                        val jsonString = defaults.stringForKey(keyStr)
                        if (jsonString != null) {
                            presets.add(json.decodeFromString<SynthPreset>(jsonString))
                        }
                    } catch (e: Exception) {
                        log.warn { "Failed to parse preset at key $keyStr: ${e.message}" }
                    }
                }
            }
            presets.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            log.error { "Failed to list presets: ${e.message}" }
            emptyList()
        }
    }
}
