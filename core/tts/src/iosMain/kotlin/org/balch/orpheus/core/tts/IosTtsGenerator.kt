package org.balch.orpheus.core.tts

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.core.coroutines.DispatcherProvider
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate

/**
 * Synthesizes offline with `AVSpeechSynthesizer.writeUtterance`, which hands back PCM buffers
 * instead of speaking. Nothing here touches `AVAudioSession`, so it cannot disturb the render
 * callback that owns the output.
 *
 * AVFoundation delivers those buffers on the **main dispatch queue**, so a caller that blocks the
 * main thread gets no audio at all. Suspending callers are fine; [SYNTHESIS_TIMEOUT_MS] is what
 * turns a blocked one into a logged null instead of a hang. Measured on the simulator: mono
 * float32 at 22.05 kHz, ~100 buffers for a two second phrase.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosTtsGenerator(
    private val dispatcherProvider: DispatcherProvider,
) : TtsGenerator {

    private val log = logging("IosTtsGenerator")

    override val isAvailable: Boolean = true

    /** The synthesizer holds per-utterance state, so one phrase at a time. */
    private val lock = Mutex()

    override suspend fun generate(text: String, voice: String?, speakingRate: Int?): TtsAudioResult? =
        lock.withLock {
            // Deliberately NOT the main dispatcher: writeUtterance does not require it, and
            // hopping to the main queue would deadlock any caller blocking that thread.
            withContext(dispatcherProvider.default) {
                try {
                    val picked = pickVoice(voice)
                    val utterance = AVSpeechUtterance.speechUtteranceWithString(text).apply {
                        setRate(utteranceRate(speakingRate))
                        picked?.let { setVoice(it) }
                    }

                    val chunks = mutableListOf<FloatArray>()
                    var sampleRate = 0
                    val done = CompletableDeferred<Unit>()
                    val synthesizer = AVSpeechSynthesizer()

                    synthesizer.writeUtterance(utterance) { buffer ->
                        val pcm = buffer as? AVAudioPCMBuffer
                        // A zero-length buffer is how AVFoundation says "that's the whole phrase".
                        if (pcm == null || pcm.frameLength.toInt() == 0) {
                            done.complete(Unit)
                        } else {
                            if (sampleRate == 0) sampleRate = pcm.format.sampleRate.toInt()
                            chunks.add(pcm.toFloats())
                        }
                    }

                    if (withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) { done.await() } == null) {
                        log.warn { "TTS synthesis timed out" }
                        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
                        return@withContext null
                    }

                    val total = chunks.sumOf { it.size }
                    if (total == 0 || sampleRate <= 0) {
                        log.warn { "TTS produced no audio for voice=$voice" }
                        return@withContext null
                    }

                    val samples = FloatArray(total)
                    var offset = 0
                    chunks.forEach { chunk ->
                        chunk.copyInto(samples, offset)
                        offset += chunk.size
                    }

                    log.info {
                        "Generated TTS: $total samples (${total / sampleRate.toFloat()}s), " +
                            "voice=$voice -> ${picked?.identifier ?: "system default"}"
                    }
                    TtsAudioResult(samples, sampleRate)
                } catch (e: Exception) {
                    log.warn { "TTS generation failed: ${e.message}" }
                    null
                }
            }
        }

    override suspend fun listVoices(): List<String> = withContext(dispatcherProvider.default) {
        val available = speechVoices()
        if (available.isEmpty()) return@withContext emptyList()

        // Prefer the curated names vibes author against, keeping only what this device can honour.
        val aliased = VoiceAliases.table
            .filter { alias -> available.any { it.matches(alias) } }
            .map { it.name }
        aliased.ifEmpty { available.mapNotNull { it.name }.sorted() }.take(MAX_VOICES)
    }

    private fun pickVoice(requested: String?): AVSpeechSynthesisVoice? {
        if (requested.isNullOrBlank()) return null
        val available = speechVoices()

        // The alias's identifiers first: the compact and enhanced downloads of a voice share a name.
        val alias = VoiceAliases.resolve(requested)
        alias?.iosVoices
            ?.firstNotNullOfOrNull { id -> available.firstOrNull { it.identifier == id } }
            ?.let { return it }

        // A name round-tripping from the Speech panel's dropdown.
        available.firstOrNull { it.name == requested }?.let { return it }

        if (alias == null) return null
        return available.firstOrNull { it.language.equals(alias.bcp47, ignoreCase = true) }
            ?: available.firstOrNull { it.matches(alias) }
    }

    private fun speechVoices(): List<AVSpeechSynthesisVoice> =
        AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()

    /** Exact language tag first, then the bare language, so en-US stands in for en-GB. */
    private fun AVSpeechSynthesisVoice.matches(alias: VoiceAlias): Boolean =
        language.equals(alias.bcp47, ignoreCase = true) ||
            language.substringBefore('-').equals(alias.bcp47.substringBefore('-'), ignoreCase = true)

    /** WPM as a multiplier of the default rate, held inside AVFoundation's 0..1 scale. */
    private fun utteranceRate(wpm: Int?): Float =
        (AVSpeechUtteranceDefaultSpeechRate * speechRateScale(wpm))
            .coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)

    @OptIn(ExperimentalForeignApi::class)
    private fun AVAudioPCMBuffer.toFloats(): FloatArray {
        val frames = frameLength.toInt()
        if (frames <= 0) return FloatArray(0)

        // TTS output is mono; with a non-interleaved format channel 0 is the whole phrase.
        floatChannelData?.let { channels ->
            val source = channels[0] ?: return FloatArray(0)
            return FloatArray(frames) { source[it] }
        }
        // Some voices write Int16 rather than Float32.
        int16ChannelData?.let { channels ->
            val source = channels[0] ?: return FloatArray(0)
            return FloatArray(frames) { source[it] / 32768f }
        }
        log.warn { "TTS buffer had neither float nor int16 channel data" }
        return FloatArray(0)
    }

    private companion object {
        const val SYNTHESIS_TIMEOUT_MS = 15_000L
        const val MAX_VOICES = 10
    }
}
