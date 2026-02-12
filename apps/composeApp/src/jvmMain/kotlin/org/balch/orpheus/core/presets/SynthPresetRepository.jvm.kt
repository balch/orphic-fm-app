package org.balch.orpheus.core.presets

import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import org.balch.orpheus.core.plugin.PortValue
import java.io.File

/**
 * JVM implementation of SynthPresetRepository using JSON files.
 * Stores presets in ~/.config/orpheus/presets/
 */
@Inject
class JvmSynthPresetRepository : SynthPresetRepository {
    private val log = logging("JvmSynthPresetRepository")


    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(PortValue::class) {
                subclass(PortValue.FloatValue::class)
                subclass(PortValue.IntValue::class)
                subclass(PortValue.BoolValue::class)
            }
        }
    }

    private val presetsDir: File by lazy {
        val userHome = System.getProperty("user.home")
        File(userHome, ".config/orpheus/presets").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                log.info { "Created presets directory: ${dir.absolutePath}" }
            }
        }
    }

    private fun fileForPreset(name: String): File {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(presetsDir, "$safeName.json")
    }

    override suspend fun save(preset: SynthPreset) {
        runCatchingSuspend {
            val file = fileForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            file.writeText(jsonString)
            log.info { "Saved preset '${preset.name}' to ${file.name}" }
        }.onFailure { e ->
            log.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    override suspend fun load(name: String): SynthPreset? {
        return runCatchingSuspend {
            val file = fileForPreset(name)
            if (file.exists()) {
                val jsonString = file.readText()
                val preset = json.decodeFromString<SynthPreset>(jsonString)
                log.info { "Loaded preset '${name}' from ${file.name}" }
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
        runCatchingSuspend {
            val file = fileForPreset(name)
            if (file.exists()) {
                file.delete()
                log.info { "Deleted preset '$name'" }
            }
        }.onFailure { e ->
            log.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }

    override suspend fun list(): List<SynthPreset> {
        return runCatchingSuspend {
            presetsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    runCatchingSuspend {
                        json.decodeFromString<SynthPreset>(file.readText())
                    }.onFailure { e ->
                        log.warn { "Failed to parse preset file ${file.name}: ${e.message}" }
                    }.getOrNull()
                }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
        }.onFailure { e ->
            log.error { "Failed to list presets: ${e.message}" }
        }.getOrDefault(emptyList())
    }
}
