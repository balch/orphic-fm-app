package org.balch.orpheus.core.presets

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.features.presets.BundledPresets
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * WASM implementation of SynthPresetRepository using browser localStorage.
 */
@Inject
class WasmSynthPresetRepository : SynthPresetRepository {
    private val log = logging("WasmSynthPresetRepository")


    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val keyPrefix = "orpheus_preset_"
    private var initialized = false

    private fun ensurePresetsInitialized() {
        if (initialized) return
        initialized = true
        copyBundledPresets()
    }

    private fun copyBundledPresets() {
        BundledPresets.presets.forEach { (name, jsonString) ->
            runCatching {
                val key = keyForPreset(name)
                if (localStorage[key] == null) {
                    localStorage[key] = jsonString
                    log.debug { "Copied bundled preset: $name" }
                }
            }.onFailure { e ->
                log.error { "Failed to copy bundled preset $name: ${e.message}" }
            }
        }
    }

    private fun keyForPreset(name: String): String {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$keyPrefix$safeName"
    }

    override suspend fun save(preset: SynthPreset) {
        ensurePresetsInitialized()
        runCatchingSuspend {
            val key = keyForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            localStorage[key] = jsonString
            log.info { "Saved preset '${preset.name}'" }
        }.onFailure { e ->
            log.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    override suspend fun load(name: String): SynthPreset? {
        ensurePresetsInitialized()
        return runCatchingSuspend {
            val key = keyForPreset(name)
            val jsonString = localStorage[key]
            if (jsonString != null) {
                val preset = json.decodeFromString<SynthPreset>(jsonString)
                log.info { "Loaded preset '$name'" }
                preset
            } else {
                log.debug { "No preset found: '$name'" }
                null
            }
        }.onFailure { e ->
            log.error { "Failed to load preset '$name': ${e.message}" }
        }.getOrNull()
    }

    override suspend fun delete(name: String) {
        ensurePresetsInitialized()
        runCatchingSuspend {
            val key = keyForPreset(name)
            localStorage.removeItem(key)
            log.info { "Deleted preset '$name'" }
        }.onFailure { e ->
            log.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }

    override suspend fun list(): List<SynthPreset> {
        ensurePresetsInitialized()
        return runCatchingSuspend {
            val presets = mutableListOf<SynthPreset>()
            for (i in 0 until localStorage.length) {
                val key = localStorage.key(i)
                if (key != null && key.startsWith(keyPrefix)) {
                    runCatchingSuspend {
                        val jsonString = localStorage[key]
                        if (jsonString != null) {
                            presets.add(json.decodeFromString<SynthPreset>(jsonString))
                        }
                    }.onFailure { e ->
                        log.warn { "Failed to parse preset at key $key: ${e.message}" }
                    }
                }
            }
            presets.sortedByDescending { it.createdAt }
        }.onFailure { e ->
            log.error { "Failed to list presets: ${e.message}" }
        }.getOrDefault(emptyList())
    }
}
