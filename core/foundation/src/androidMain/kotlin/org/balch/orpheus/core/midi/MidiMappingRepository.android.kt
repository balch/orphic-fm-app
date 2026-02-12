package org.balch.orpheus.core.midi

import android.content.Context
import com.diamondedge.logging.logging
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.coroutines.runCatchingSuspend
import java.io.File

/**
 * Android implementation of MidiMappingRepository using internal storage.
 */
actual class MidiMappingRepository actual constructor() {
    private val log = logging()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private var context: Context? = null

    /**
     * Initialize with Android context. Must be called before using the repository.
     */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    private fun getMappingsDir(): File? {
        return context?.let { ctx ->
            File(ctx.filesDir, "midi-mappings").also { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
        }
    }

    private fun fileForDevice(deviceName: String): File? {
        val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return getMappingsDir()?.let { File(it, "$safeName.json") }
    }

    actual suspend fun save(deviceName: String, mapping: MidiMappingState) {
        runCatchingSuspend {
            val file = fileForDevice(deviceName) ?: run {
                log.warn { "MidiMappingRepository not initialized with context" }
                return
            }
            val jsonString = json.encodeToString(mapping.forPersistence())
            file.writeText(jsonString)
            log.info { "Saved MIDI mappings for '$deviceName'" }
        }.onFailure { e ->
            log.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun load(deviceName: String): MidiMappingState? {
        return runCatchingSuspend {
            val file = fileForDevice(deviceName) ?: run {
                log.warn { "MidiMappingRepository not initialized with context" }
                return null
            }
            if (file.exists()) {
                val jsonString = file.readText()
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                log.d { "Loaded MIDI mappings for '$deviceName'" }
                mapping
            } else {
                null
            }
        }.onFailure { e ->
            log.error { "Failed to load MIDI mappings for '$deviceName': ${e.message}" }
        }.getOrNull()
    }

    actual suspend fun delete(deviceName: String) {
        runCatchingSuspend {
            fileForDevice(deviceName)?.let { file ->
                if (file.exists()) {
                    file.delete()
                    log.info { "Deleted MIDI mappings for '$deviceName'" }
                }
            }
        }.onFailure { e ->
            log.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun listDevices(): List<String> {
        return runCatchingSuspend {
            getMappingsDir()?.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        }.onFailure { e ->
            log.error { "Failed to list MIDI mapping devices: ${e.message}" }
        }.getOrDefault(emptyList())
    }
}
