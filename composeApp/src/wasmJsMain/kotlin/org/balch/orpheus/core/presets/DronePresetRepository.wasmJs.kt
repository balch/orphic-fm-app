package org.balch.orpheus.core.presets

import dev.zacsweers.metro.Inject
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.balch.orpheus.util.Logger
import org.w3c.dom.get
import org.w3c.dom.set
import orpheus.composeapp.generated.resources.Res

/**
 * WASM implementation of DronePresetRepository using browser localStorage.
 */
@Inject
class WasmDronePresetRepository : DronePresetRepository {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val keyPrefix = "orpheus_preset_"
    private var initialized = false

    private suspend fun ensurePresetsInitialized() {
        if (initialized) return
        initialized = true
        copyBundledPresets()
    }

    private suspend fun copyBundledPresets() {
        val bundledPresets = listOf(
            "F__Minor_Drift.json",
            "Warm_Pad.json",
            "Dark_Ambient.json",
            "Swirly_Dreams.json"
        )
        bundledPresets.forEach { filename ->
            try {
                val presetName = filename.removeSuffix(".json")
                val key = keyForPreset(presetName)
                
                if (localStorage[key] == null) {
                    val path = "files/presets/$filename"
                    val bytes = Res.readBytes(path)
                    val jsonString = bytes.decodeToString()
                    localStorage[key] = jsonString
                    Logger.debug { "Copied bundled preset: $filename" }
                }
            } catch (e: Exception) {
                Logger.error { "Failed to copy bundled preset $filename: ${e.message}" }
            }
        }
    }

    private fun keyForPreset(name: String): String {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$keyPrefix$safeName"
    }

    override suspend fun save(preset: DronePreset) {
        ensurePresetsInitialized()
        try {
            val key = keyForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            localStorage[key] = jsonString
            Logger.info { "Saved preset '${preset.name}'" }
        } catch (e: Exception) {
            Logger.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    override suspend fun load(name: String): DronePreset? {
        ensurePresetsInitialized()
        return try {
            val key = keyForPreset(name)
            val jsonString = localStorage[key]
            if (jsonString != null) {
                val preset = json.decodeFromString<DronePreset>(jsonString)
                Logger.info { "Loaded preset '$name'" }
                preset
            } else {
                Logger.debug { "No preset found: '$name'" }
                null
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }

    override suspend fun delete(name: String) {
        ensurePresetsInitialized()
        try {
            val key = keyForPreset(name)
            localStorage.removeItem(key)
            Logger.info { "Deleted preset '$name'" }
        } catch (e: Exception) {
            Logger.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }

    override suspend fun list(): List<DronePreset> {
        ensurePresetsInitialized()
        return try {
            val presets = mutableListOf<DronePreset>()
            for (i in 0 until localStorage.length) {
                val key = localStorage.key(i)
                if (key != null && key.startsWith(keyPrefix)) {
                    try {
                        val jsonString = localStorage[key]
                        if (jsonString != null) {
                            presets.add(json.decodeFromString<DronePreset>(jsonString))
                        }
                    } catch (e: Exception) {
                        Logger.warn { "Failed to parse preset at key $key: ${e.message}" }
                    }
                }
            }
            presets.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Logger.error { "Failed to list presets: ${e.message}" }
            emptyList()
        }
    }
}
