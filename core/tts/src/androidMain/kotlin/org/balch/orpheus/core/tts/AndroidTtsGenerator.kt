package org.balch.orpheus.core.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.balch.orpheus.core.coroutines.DispatcherProvider
import java.io.File

/**
 * Synthesizes offline with the device's TTS engine. `synthesizeToFile` writes a WAV rather than
 * speaking, so the phrase reaches the DSP graph as samples and never touches audio focus.
 *
 * Requires a `<queries>` entry for `android.intent.action.TTS_SERVICE` in the app manifest;
 * without it [isAvailable] reads false on API 30+ even when an engine is installed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidTtsGenerator(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : TtsGenerator {

    private val log = logging("AndroidTtsGenerator")

    override val isAvailable: Boolean by lazy {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    }

    /** One synthesis at a time: the engine carries a single utterance listener and speech rate. */
    private val lock = Mutex()
    private var engine: TextToSpeech? = null
    private var pending: CompletableDeferred<Boolean>? = null

    /** The voice the shared engine will speak in next. Read by the device test. */
    internal val activeVoiceName: String? get() = engine?.voice?.name

    override suspend fun generate(text: String, voice: String?, speakingRate: Int?): TtsAudioResult? {
        if (!isAvailable) return null

        return withContext(dispatcherProvider.io) {
            lock.withLock {
                val tts = engine() ?: return@withLock null
                var file: File? = null
                try {
                    val picked = pickVoice(tts, voice)
                    // The engine is shared: no voice means the default, not whatever was set last.
                    (picked ?: tts.defaultVoice)?.let { tts.setVoice(it) }
                    tts.setSpeechRate(speechRateScale(speakingRate))

                    file = File.createTempFile("orpheus_tts_", ".wav", context.cacheDir)
                    val done = CompletableDeferred<Boolean>()
                    pending = done

                    val queued = tts.synthesizeToFile(text, Bundle(), file, UTTERANCE_ID)
                    if (queued != TextToSpeech.SUCCESS) {
                        log.warn { "synthesizeToFile refused the request" }
                        return@withLock null
                    }
                    if (withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) { done.await() } != true) {
                        log.warn { "TTS synthesis failed or timed out" }
                        return@withLock null
                    }

                    val result = WavDecoder.decode(file.readBytes())
                    if (result == null) {
                        log.warn { "TTS wrote ${file.length()} bytes that did not decode as WAV" }
                    } else {
                        log.info {
                            "Generated TTS: ${result.samples.size} samples " +
                                "(${result.samples.size / result.sampleRate.toFloat()}s), " +
                                "voice=$voice -> ${picked?.name ?: "engine default"}"
                        }
                    }
                    result
                } catch (e: Exception) {
                    log.warn { "TTS generation failed: ${e.message}" }
                    null
                } finally {
                    pending = null
                    file?.delete()
                }
            }
        }
    }

    override suspend fun listVoices(): List<String> {
        if (!isAvailable) return emptyList()

        return withContext(dispatcherProvider.io) {
            val usable = lock.withLock { engine()?.usableVoices() }.orEmpty()
            if (usable.isEmpty()) return@withContext emptyList()

            // Prefer the curated names vibes author against, keeping only what this device can honour.
            val aliased = VoiceAliases.table
                .filter { alias -> usable.any { it.matches(alias) } }
                .map { it.name }
            aliased.ifEmpty { usable.map { it.name }.sorted() }.take(MAX_VOICES)
        }
    }

    /** Binds the engine on first use and waits for it to report ready. Null if it never does. */
    private suspend fun engine(): TextToSpeech? {
        engine?.let { return it }

        val ready = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) {
            log.warn { "TTS engine never finished initializing" }
            tts.shutdown()
            return null
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { pending?.complete(true) }

            @Suppress("OVERRIDE_DEPRECATION") // abstract in the base class; superseded by onError(String, Int)
            override fun onError(utteranceId: String?) { pending?.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) {
                log.warn { "TTS utterance failed with code $errorCode" }
                pending?.complete(false)
            }
        })
        engine = tts
        return tts
    }

    private fun pickVoice(tts: TextToSpeech, requested: String?): Voice? {
        if (requested.isNullOrBlank()) return null
        val usable = tts.usableVoices()

        // A platform id round-tripping from the Speech panel's dropdown wins outright.
        usable.firstOrNull { it.name == requested }?.let { return it }

        val alias = VoiceAliases.resolve(requested) ?: return null
        // The API exposes no gender and Google's ids carry none, so the alias names ids in
        // preference order. Uninstalled ones are not usable, so a UK id waits for its voice data.
        alias.androidVoices.firstNotNullOfOrNull { id -> usable.firstOrNull { it.name == id } }
            ?.let { return it }
        // Engines with other id schemes get the nearest locale: en-GB before any en-*.
        return usable.firstOrNull { it.locale.toLanguageTag().equals(alias.bcp47, ignoreCase = true) }
            ?: usable.firstOrNull { it.matches(alias) }
    }

    private fun TextToSpeech.usableVoices(): List<Voice> =
        voices.orEmpty().filter {
            !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }

    /** Exact language tag first, then the bare language, so en-US stands in for en-GB. */
    private fun Voice.matches(alias: VoiceAlias): Boolean {
        val tag = locale.toLanguageTag()
        return tag.equals(alias.bcp47, ignoreCase = true) ||
            locale.language.equals(alias.bcp47.substringBefore('-'), ignoreCase = true)
    }

    private companion object {
        const val UTTERANCE_ID = "orpheus_tts"
        const val INIT_TIMEOUT_MS = 5_000L
        const val SYNTHESIS_TIMEOUT_MS = 15_000L
        const val MAX_VOICES = 10
    }
}
