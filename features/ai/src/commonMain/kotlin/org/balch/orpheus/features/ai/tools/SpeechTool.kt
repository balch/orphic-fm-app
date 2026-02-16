package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.speech.SpeechEventBus
import org.balch.orpheus.core.speech.SpeechVocabulary
import org.balch.orpheus.core.speech.TtsGenerator
import org.balch.orpheus.features.ai.PanelExpansionEventBus

/**
 * AI tool for making the synthesizer speak using either TTS (text-to-speech)
 * for arbitrary text, or the Plaits LPC speech engine for its word bank vocabulary.
 *
 * Mode is selected automatically: if `text` is provided and TTS is available, uses TTS.
 * If `words` is provided, uses the synth LPC engine. Can be overridden with `mode`.
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class SpeechTool @Inject constructor(
    private val synthController: SynthController,
    private val synthEngine: SynthEngine,
    private val ttsGenerator: TtsGenerator,
    private val speechEventBus: SpeechEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus,
) : Tool<SpeechTool.Args, SpeechTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "speech",
    description = buildDescription(ttsGenerator.isAvailable)
) {
    private val log = logging("SpeechTool")

    @Serializable
    data class Args(
        @property:LLMDescription(
            "Speech mode: 'auto' (default, picks best available), 'tts' (text-to-speech, any text), or 'synth' (LPC engine, limited vocabulary)."
        )
        val mode: String = "auto",

        @property:LLMDescription("Free-form text to speak using TTS. Use this for arbitrary sentences and phrases.")
        val text: String = "",

        @property:LLMDescription("List of words for synth/LPC mode, e.g. [\"hello\", \"one\", \"two\"]. Limited to built-in vocabulary.")
        val words: List<String> = emptyList(),

        @property:LLMDescription("TTS voice name (e.g. 'Samantha', 'Daniel'). Only used in TTS mode. Omit for default voice.")
        val voice: String? = null,

        @property:LLMDescription("Speech speed (0.0=fast, 1.0=slow). Defaults to 0.3.")
        val speed: Float = 0.3f,

        @property:LLMDescription("Prosody/intonation amount (0.0=flat, 1.0=expressive). Synth mode only. Defaults to 0.5.")
        val prosody: Float = 0.5f,

        @property:LLMDescription("Voice duo to use for synth mode speech (1-6). Defaults to 1.")
        val duo: Int = 1,
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String,
        val mode: String = "",
        val wordsSpoken: Int = 0,
        val wordsSpelled: Int = 0,
    )

    override suspend fun execute(args: Args): Result {
        val effectiveMode = resolveMode(args)
        panelExpansionEventBus.expand(PanelId.SPEECH)

        return when (effectiveMode) {
            "tts" -> executeTts(args)
            else -> executeSynth(args)
        }
    }

    private fun resolveMode(args: Args): String = when (args.mode) {
        "tts" -> if (ttsGenerator.isAvailable) "tts" else "synth"
        "synth" -> "synth"
        else -> when { // "auto"
            args.text.isNotBlank() && ttsGenerator.isAvailable -> "tts"
            args.words.isNotEmpty() -> "synth"
            ttsGenerator.isAvailable -> "tts"
            else -> "synth"
        }
    }

    // ── TTS mode ──────────────────────────────────────────────────────

    private suspend fun executeTts(args: Args): Result {
        val text = args.text.ifBlank { args.words.joinToString(" ") }
        if (text.isBlank()) {
            return Result(success = false, message = "No text provided for TTS", mode = "tts")
        }

        log.info { "TTS speaking: '$text' voice=${args.voice}" }
        speechEventBus.emitSpeaking(text, 0, 1)

        val speedWpm = speedToWpm(args.speed)
        val result = ttsGenerator.generate(text, args.voice, speedWpm)
        if (result == null) {
            speechEventBus.emitFailed("TTS generation failed")
            return Result(success = false, message = "TTS generation failed", mode = "tts")
        }

        synthEngine.loadTtsAudio(result.samples, result.sampleRate)
        synthEngine.playTts()

        // Wait for approximate playback duration + audio pipeline latency buffer
        val durationMs = (result.samples.size.toFloat() / result.sampleRate * 1000f).toLong() + 200
        delay(durationMs)

        speechEventBus.emitDone(text)
        return Result(
            success = true,
            message = "Spoke '$text' using TTS${args.voice?.let { " (voice: $it)" } ?: ""}",
            mode = "tts",
            wordsSpoken = text.split("\\s+".toRegex()).size,
        )
    }

    // ── Synth/LPC mode ───────────────────────────────────────────────

    private suspend fun executeSynth(args: Args): Result {
        val words = args.words.ifEmpty { args.text.split("\\s+".toRegex()).filter { it.isNotBlank() } }
        if (words.isEmpty()) {
            return Result(success = false, message = "No words provided for synth speech", mode = "synth")
        }

        val duoIndex = (args.duo - 1).coerceIn(0, 5)
        val duoNum = duoIndex + 1
        log.info { "Synth speaking ${words.size} words on duo $duoNum" }

        // Set engine to Speech (ordinal 17)
        synthController.setPluginControl(
            id = VoiceSymbol.duoEngine(duoIndex).controlId,
            value = PortValue.IntValue(17),
            origin = ControlEventOrigin.AI
        )

        // Set prosody and speed
        synthController.setPluginControl(
            id = VoiceSymbol.duoProsody(duoIndex).controlId,
            value = PortValue.FloatValue(args.prosody.coerceIn(0f, 1f)),
            origin = ControlEventOrigin.AI
        )
        synthController.setPluginControl(
            id = VoiceSymbol.duoSpeed(duoIndex).controlId,
            value = PortValue.FloatValue(args.speed.coerceIn(0f, 1f)),
            origin = ControlEventOrigin.AI
        )

        val fullText = words.joinToString(" ")
        var wordsSpoken = 0
        var wordsSpelled = 0

        // Resolve and sequence each word
        val resolvedWords = mutableListOf<Pair<String, List<SpeechVocabulary.WordBankEntry>>>()
        for (word in words) {
            val entry = SpeechVocabulary.resolveWord(word)
            if (entry != null) {
                resolvedWords.add(word to listOf(entry))
            } else {
                // Spell letter-by-letter
                val letters = word.lowercase().mapNotNull { ch ->
                    SpeechVocabulary.resolveLetter(ch)?.let { ch.toString() to it }
                }
                if (letters.isNotEmpty()) {
                    resolvedWords.add(word to letters.map { it.second })
                    wordsSpelled++
                }
            }
        }

        // Play each word
        val voiceA = duoIndex * 2
        for ((wordIdx, pair) in resolvedWords.withIndex()) {
            val (word, entries) = pair
            speechEventBus.emitSpeaking(word, wordIdx, resolvedWords.size)

            for (entry in entries) {
                // Set harmonics (bank selection)
                synthController.setPluginControl(
                    id = VoiceSymbol.duoHarmonics(duoIndex).controlId,
                    value = PortValue.FloatValue(SpeechVocabulary.harmonicsForBank(entry.bank)),
                    origin = ControlEventOrigin.AI
                )
                delay(30)

                // Set morph (word selection within bank)
                val morphValue = SpeechVocabulary.morphForWord(entry.wordIndex, entry.totalWords)
                synthController.setPluginControl(
                    id = VoiceSymbol.modDepth(voiceA).controlId,
                    value = PortValue.FloatValue(morphValue),
                    origin = ControlEventOrigin.AI
                )

                // Trigger voice
                synthController.emitPulseStart(voiceA)
                delay(50)
                synthController.emitPulseEnd(voiceA)

                // Wait for word duration
                delay(entry.durationMs.toLong())
            }

            wordsSpoken++
            delay(100)
        }

        speechEventBus.emitDone(fullText)

        return Result(
            success = true,
            message = "Spoke '$fullText' on duo $duoNum using synth engine",
            mode = "synth",
            wordsSpoken = wordsSpoken,
            wordsSpelled = wordsSpelled,
        )
    }

    companion object {
        /** Map 0.0-1.0 speed to WPM (inverted: 0.0=fast/300wpm, 1.0=slow/80wpm). */
        private fun speedToWpm(speed: Float): Int = (300 - speed * 220).toInt().coerceIn(80, 300)

        private val SYNTH_VOCABULARY = """
                |**Synth mode**: Uses the built-in LPC speech engine (engine 17) with a limited vocabulary:
                |- Colors: red, orange, yellow, green, blue, indigo, violet
                |- Numbers: zero through ten
                |- Letters: a through z
                |- NATO alphabet: alpha, bravo, charlie, ..., zulu
                |- Synth terms: analog, circuit, clock, control, digital, electronic, filter, frequency, generator, instrument, knob, machine, modular, modulator, operator, oscillator, patch, sequencer, synthesizer, vca, voltage, waveform
                |Unknown words are spelled letter-by-letter.
        """.trimMargin()

        fun buildDescription(ttsAvailable: Boolean): String = if (ttsAvailable) {
            """
                Make the synthesizer speak using text-to-speech (TTS) or the built-in synth speech engine.

                **TTS mode** (default): Speaks any arbitrary text using system TTS voices. Set `text` to the phrase you want spoken. Optionally set `voice` to a specific voice name.

                Available voices:
                - Natural: Samantha, Daniel, Karen, Moira, Tessa, Rishi, Flo, Shelley, Sandy, Grandma, Grandpa, Aman, Tara
                - Character: Fred, Junior, Kathy, Ralph, Reed, Rocko
                - Novelty: Albert, Bad, Bahh, Bells, Boing, Bubbles, Cellos, Good, Jester, Organ, Superstar, Trinoids, Whisper, Wobble, Zarvox

                $SYNTH_VOCABULARY

                Use TTS mode for natural speech and arbitrary text. Use synth mode for robotic/vocoder-style speech effects.
            """.trimIndent()
        } else {
            """
                Make the synthesizer speak words using the Speech engine (engine 17).
                $SYNTH_VOCABULARY

                Words are played sequentially on the specified voice duo.
            """.trimIndent()
        }
    }
}
