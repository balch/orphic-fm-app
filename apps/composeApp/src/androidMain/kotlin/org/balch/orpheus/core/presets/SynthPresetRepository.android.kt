package org.balch.orpheus.core.presets

import android.content.Context
import com.diamondedge.logging.logging
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Android implementation of SynthPresetRepository using JSON files.
 * Stores presets in the app's files directory.
 * Context is injected via DI.
 */
@Inject
class AndroidSynthPresetRepository(
    private val context: Context
) : SynthPresetRepository {
    private val log = logging("AndroidSynthPresetRepository")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val presetsDir: File by lazy {
        File(context.filesDir, "presets").also { dir ->
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
        try {
            val file = fileForPreset(preset.name)
            val jsonString = json.encodeToString(preset)
            file.writeText(jsonString)
            log.info { "Saved preset '${preset.name}'" }
        } catch (e: Exception) {
            log.error { "Failed to save preset '${preset.name}': ${e.message}" }
        }
    }

    override suspend fun load(name: String): SynthPreset? {
        return try {
            val file = fileForPreset(name)
            if (file.exists()) {
                val jsonString = file.readText()
                json.decodeFromString<SynthPreset>(jsonString)
            } else null
        } catch (e: Exception) {
            log.error { "Failed to load preset '$name': ${e.message}" }
            null
        }
    }

    override suspend fun delete(name: String) {
        try {
            val file = fileForPreset(name)
            if (file.exists()) {
                file.delete()
                log.info { "Deleted preset '$name'" }
            }
        } catch (e: Exception) {
            log.error { "Failed to delete preset '$name': ${e.message}" }
        }
    }

    override suspend fun list(): List<SynthPreset> {
        return try {
            presetsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        json.decodeFromString<SynthPreset>(file.readText())
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
        } catch (e: Exception) {
            log.error { "Failed to list presets: ${e.message}" }
            emptyList()
        }
    }
}
