package org.balch.orpheus.features.record.store

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.presets.PresetJson
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.features.record.telemetry.TakeTelemetry
import org.balch.orpheus.features.record.telemetry.TelemetryJson
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Takes live in `<root>/<take-id>/{audio.wav,preset.json,telemetry.json}`. */
@ContributesBinding(AppScope::class)
class FileTakeStore(private val root: File) : TakeStore {

    @Inject
    constructor() : this(File(System.getProperty("user.home"), ".config/orpheus/recordings"))

    override suspend fun list(): List<TakeInfo> = withContext(Dispatchers.IO) {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return@withContext emptyList()
        dirs.mapNotNull { dir ->
            val wav = File(dir, AUDIO_FILE)
            if (!wav.isFile) return@mapNotNull null
            val duration = telemetryDuration(dir) ?: wavDuration(wav) ?: return@mapNotNull null
            TakeInfo(dir.name, duration, dir.lastModified(), wav.absolutePath)
        }.sortedByDescending { it.createdAtMs }
    }

    override suspend fun delete(takeId: String) {
        withContext(Dispatchers.IO) { takeDir(takeId)?.deleteRecursively() }
    }

    override fun open(takeId: String, sampleRate: Int): TakeWriter {
        val dir = requireNotNull(takeDir(takeId)) { "Invalid take id: $takeId" }
        check(!dir.exists()) { "Take folder already exists: $takeId" }
        dir.mkdirs()
        return FileTakeWriter(dir, WavFileWriter(File(dir, AUDIO_FILE), sampleRate))
    }

    /** Null for ids that would resolve outside [root]. */
    private fun takeDir(takeId: String): File? {
        val dir = File(root, takeId)
        return dir.takeIf { it.canonicalFile.parentFile == root.canonicalFile }
    }

    private fun telemetryDuration(dir: File): Float? = runCatching {
        TelemetryJson.decodeFromString<TakeTelemetry>(File(dir, TELEMETRY_FILE).readText()).durationSec
    }.getOrNull()

    private fun wavDuration(wav: File): Float? = runCatching {
        RandomAccessFile(wav, "r").use { raf ->
            if (raf.length() < WAV_HEADER_BYTES) return null
            val header = ByteArray(WAV_HEADER_BYTES).also { raf.readFully(it) }
            val rate = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            if (rate <= 0) return null
            (raf.length() - WAV_HEADER_BYTES) / BYTES_PER_FRAME.toFloat() / rate
        }
    }.getOrNull()

    /** writeAudio is guarded by the WAV writer's lock; finish and abort are mutually exclusive and run once. */
    private class FileTakeWriter(private val dir: File, private val wav: WavFileWriter) : TakeWriter {
        override fun writeAudio(interleaved: FloatArray, frames: Int) = wav.write(interleaved, frames)

        private var done = false

        @Synchronized
        override fun finish(preset: SynthPreset, telemetry: TakeTelemetry) {
            if (done) return
            done = true
            wav.close()
            File(dir, PRESET_FILE).writeText(PresetJson.encodeToString(preset))
            File(dir, TELEMETRY_FILE).writeText(TelemetryJson.encodeToString(telemetry))
        }

        @Synchronized
        override fun abort() {
            if (done) return
            done = true
            runCatching { wav.close() }
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val AUDIO_FILE = "audio.wav"
        const val PRESET_FILE = "preset.json"
        const val TELEMETRY_FILE = "telemetry.json"
        const val WAV_HEADER_BYTES = 44
        const val BYTES_PER_FRAME = 8
    }
}
