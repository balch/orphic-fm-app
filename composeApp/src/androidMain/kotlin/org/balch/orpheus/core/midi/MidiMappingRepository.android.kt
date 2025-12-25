package org.balch.orpheus.core.midi

import android.content.Context
import kotlinx.serialization.json.Json
import org.balch.orpheus.util.Logger
import java.io.File

/**
 * Android implementation of MidiMappingRepository using internal storage.
 */
actual class MidiMappingRepository actual constructor() {

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
        try {
            val file = fileForDevice(deviceName) ?: run {
                Logger.warn { "MidiMappingRepository not initialized with context" }
                return
            }
            val jsonString = json.encodeToString(mapping.forPersistence())
            file.writeText(jsonString)
            Logger.info { "Saved MIDI mappings for '$deviceName'" }
        } catch (e: Exception) {
            Logger.error { "Failed to save MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun load(deviceName: String): MidiMappingState? {
        return try {
            val file = fileForDevice(deviceName) ?: run {
                Logger.warn { "MidiMappingRepository not initialized with context" }
                return null
            }
            if (file.exists()) {
                val jsonString = file.readText()
                val mapping = json.decodeFromString<MidiMappingState>(jsonString)
                Logger.info { "Loaded MIDI mappings for '$deviceName'" }
                mapping
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.error { "Failed to load MIDI mappings for '$deviceName': ${e.message}" }
            null
        }
    }

    actual suspend fun delete(deviceName: String) {
        try {
            fileForDevice(deviceName)?.let { file ->
                if (file.exists()) {
                    file.delete()
                    Logger.info { "Deleted MIDI mappings for '$deviceName'" }
                }
            }
        } catch (e: Exception) {
            Logger.error { "Failed to delete MIDI mappings for '$deviceName': ${e.message}" }
        }
    }

    actual suspend fun listDevices(): List<String> {
        return try {
            getMappingsDir()?.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        } catch (e: Exception) {
            Logger.error { "Failed to list MIDI mapping devices: ${e.message}" }
            emptyList()
        }
    }
}
