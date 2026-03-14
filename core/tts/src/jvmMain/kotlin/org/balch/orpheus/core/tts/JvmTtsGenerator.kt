package org.balch.orpheus.core.tts

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.coroutines.DispatcherProvider
import java.io.File
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class JvmTtsGenerator(
    private val dispatcherProvider: DispatcherProvider,
) : TtsGenerator {

    private val log = logging("JvmTtsGenerator")

    override val isAvailable: Boolean =
        System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true

    private var cachedVoices: List<String>? = null

    override suspend fun listVoices(): List<String> {
        if (!isAvailable) return emptyList()
        cachedVoices?.let { return it }

        return withContext(dispatcherProvider.io) {
            try {
                val process = ProcessBuilder("say", "-v", "?")
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                val completed = process.waitFor(10, TimeUnit.SECONDS)
                if (!completed || process.exitValue() != 0) {
                    log.warn { "say -v ? failed" }
                    return@withContext emptyList()
                }
                // Each line: "VoiceName     language  # description"
                val allVoices = output.lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        line.trim().split("\\s+".toRegex()).firstOrNull()
                    }
                    .distinct()
                val voices = curateVoices(allVoices)
                cachedVoices = voices
                log.d { "Found ${voices.size} TTS voices" }
                voices
            } catch (e: Exception) {
                log.warn { "Failed to list voices: ${e.message}" }
                emptyList()
            }
        }
    }

    companion object {
        private const val MAX_VOICES = 10

        // Preferred voices ranked by dramatic/interesting quality.
        // These tend to have distinctive character for speech synthesis music.
        private val PREFERRED_VOICES = listOf(
            "Daniel",       // deep British male — closest to "One of These Days"
            "Tom",          // deep American male
            "Alex",         // natural American male
            "Fred",         // classic robotic
            "Whisper",      // breathy
            "Zarvox",       // alien/robotic
            "Trinoids",     // sci-fi harmonic
            "Bad News",     // ominous
            "Good News",    // bright contrast
            "Cellos",       // musical
            "Organ",        // organ-like
            "Boing",        // percussive
            "Albert",       // old-style
            "Ralph",        // nasal character
            "Samantha",     // clear female
            "Karen",        // Australian female
            "Moira",        // Irish female
            "Tessa",        // South African female
        )
    }

    private fun curateVoices(allVoices: List<String>): List<String> {
        if (allVoices.size <= MAX_VOICES) return allVoices

        // Pick preferred voices that exist on this system, in rank order
        val preferred = PREFERRED_VOICES.filter { it in allVoices }.take(MAX_VOICES)
        if (preferred.size >= MAX_VOICES) return preferred

        // Fill remaining slots with alphabetically sorted voices not already selected
        val remaining = allVoices.filter { it !in preferred }.sorted()
        return preferred + remaining.take(MAX_VOICES - preferred.size)
    }

    override suspend fun generate(text: String, voice: String?, speakingRate: Int?): TtsAudioResult? {
        if (!isAvailable) return null

        return withContext(dispatcherProvider.io) {
            var tmpFile: File? = null
            try {
                tmpFile = File.createTempFile("orpheus_tts_", ".wav")
                val cmd = mutableListOf(
                    "say", "-o", tmpFile.absolutePath,
                    "--file-format=WAVE", "--data-format=LEI16@44100"
                )
                if (voice != null) {
                    cmd.addAll(listOf("-v", voice))
                }
                if (speakingRate != null) {
                    cmd.addAll(listOf("-r", speakingRate.toString()))
                }
                cmd.add(text)

                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true).start()

                val output = process.inputStream.bufferedReader().readText()
                val completed = process.waitFor(15, TimeUnit.SECONDS)
                if (!completed || process.exitValue() != 0) {
                    log.warn { "say command failed: exit=${process.exitValue()}, output=$output" }
                    return@withContext null
                }

                val audioStream = AudioSystem.getAudioInputStream(tmpFile)
                val bytes = audioStream.readBytes()
                audioStream.close()

                // Convert 16-bit PCM to float
                val sampleCount = bytes.size / 2
                val samples = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val lo = bytes[i * 2].toInt() and 0xFF
                    val hi = bytes[i * 2 + 1].toInt()
                    val sample16 = (hi shl 8) or lo
                    samples[i] = sample16 / 32768f
                }

                log.info { "Generated TTS: ${samples.size} samples (${samples.size / 44100f}s), voice=$voice, rate=$speakingRate" }
                TtsAudioResult(samples, 44100)
            } catch (e: Exception) {
                log.warn { "TTS generation failed: ${e.message}" }
                null
            } finally {
                tmpFile?.delete()
            }
        }
    }
}
