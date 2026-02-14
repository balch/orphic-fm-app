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
import org.balch.orpheus.core.controller.ControlEventOrigin
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.speech.SpeechEventBus
import org.balch.orpheus.core.speech.SpeechVocabulary
import org.balch.orpheus.features.ai.PanelExpansionEventBus
import org.balch.orpheus.core.PanelId

/**
 * AI tool for making the synthesizer speak words using the Plaits Speech engine.
 *
 * Sequences words from the pre-encoded LPC word banks (colors, numbers, letters,
 * NATO alphabet, synth vocabulary). Unknown words are spelled letter-by-letter.
 */
@ContributesIntoSet(AppScope::class, binding<Tool<*, *>>())
class SpeechTool @Inject constructor(
    private val synthController: SynthController,
    private val speechEventBus: SpeechEventBus,
    private val panelExpansionEventBus: PanelExpansionEventBus,
) : Tool<SpeechTool.Args, SpeechTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "speech",
    description = """
        Make the synthesizer speak words using the Speech engine (engine 17).
        Available vocabulary:
        - Colors: red, orange, yellow, green, blue, indigo, violet
        - Numbers: zero through ten
        - Letters: a through z
        - NATO alphabet: alpha, bravo, charlie, ..., zulu
        - Synth terms: analog, circuit, clock, control, digital, electronic, filter, frequency, generator, instrument, knob, machine, modular, modulator, operator, oscillator, patch, sequencer, synthesizer, vca, voltage, waveform

        Unknown words are spelled letter-by-letter. Words are played sequentially on the specified voice pair.
    """.trimIndent()
) {
    private val log = logging("SpeechTool")

    @Serializable
    data class Args(
        @property:LLMDescription("Voice pair to use for speech (1-6). Defaults to 1.")
        val pair: Int = 1,

        @property:LLMDescription("List of words to speak, e.g. [\"hello\", \"one\", \"two\", \"three\"]")
        val words: List<String>,

        @property:LLMDescription("Speech speed (0.0=fast, 1.0=slow). Defaults to 0.3.")
        val speed: Float = 0.3f,

        @property:LLMDescription("Prosody/intonation amount (0.0=flat, 1.0=expressive). Defaults to 0.5.")
        val prosody: Float = 0.5f,
    )

    @Serializable
    data class Result(
        val success: Boolean,
        val message: String,
        val wordsSpoken: Int = 0,
        val wordsSpelled: Int = 0,
    )

    override suspend fun execute(args: Args): Result {
        val pairIndex = (args.pair - 1).coerceIn(0, 5)
        val pairNum = pairIndex + 1
        log.info { "Speaking ${args.words.size} words on pair $pairNum" }

        // Set engine to Speech (ordinal 17)
        val engineSymbol = VoiceSymbol.pairEngine(pairIndex)
        synthController.setPluginControl(
            id = engineSymbol.controlId,
            value = PortValue.IntValue(17),
            origin = ControlEventOrigin.AI
        )

        // Set prosody and speed
        synthController.setPluginControl(
            id = VoiceSymbol.pairProsody(pairIndex).controlId,
            value = PortValue.FloatValue(args.prosody.coerceIn(0f, 1f)),
            origin = ControlEventOrigin.AI
        )
        synthController.setPluginControl(
            id = VoiceSymbol.pairSpeed(pairIndex).controlId,
            value = PortValue.FloatValue(args.speed.coerceIn(0f, 1f)),
            origin = ControlEventOrigin.AI
        )

        // Expand speech panel
        panelExpansionEventBus.expand(PanelId.SPEECH)

        val fullText = args.words.joinToString(" ")
        var wordsSpoken = 0
        var wordsSpelled = 0

        // Resolve and sequence each word
        val resolvedWords = mutableListOf<Pair<String, List<SpeechVocabulary.WordBankEntry>>>()
        for (word in args.words) {
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
        val voiceA = pairIndex * 2
        for ((wordIdx, pair) in resolvedWords.withIndex()) {
            val (word, entries) = pair
            speechEventBus.emitSpeaking(word, wordIdx, resolvedWords.size)

            for (entry in entries) {
                // Set harmonics (bank selection)
                synthController.setPluginControl(
                    id = VoiceSymbol.pairHarmonics(pairIndex).controlId,
                    value = PortValue.FloatValue(SpeechVocabulary.harmonicsForBank(entry.bank)),
                    origin = ControlEventOrigin.AI
                )
                // Small delay for bank selection to take effect
                delay(30)

                // Set morph (word selection within bank) via FM_DEPTH which maps to plaits.setMorph()
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
            // Brief pause between words
            delay(100)
        }

        speechEventBus.emitDone(fullText)

        return Result(
            success = true,
            message = "Spoke '$fullText' on pair $pairNum",
            wordsSpoken = wordsSpoken,
            wordsSpelled = wordsSpelled,
        )
    }
}
