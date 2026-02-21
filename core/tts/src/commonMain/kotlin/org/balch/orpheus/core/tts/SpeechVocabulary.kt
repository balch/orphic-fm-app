package org.balch.orpheus.core.tts

/**
 * Maps known words to their LPC word bank locations.
 *
 * The Plaits Speech engine has 5 pre-encoded word banks:
 * - Bank 0: Colors (red, orange, yellow, green, blue, indigo, violet)
 * - Bank 1: Numbers (zero through ten)
 * - Bank 2: Letters (a through z)
 * - Bank 3: NATO alphabet (alpha through zulu)
 * - Bank 4: Synth vocabulary (analog, circuit, clock, ... waveform)
 */
object SpeechVocabulary {

    data class WordBankEntry(
        val bank: Int,
        val wordIndex: Int,
        val totalWords: Int,
        val durationMs: Int
    )

    /** Maps the `harmonics` parameter value (0..1) to select a word bank in LPC mode. */
    fun harmonicsForBank(bank: Int): Float {
        // harmonics * 6.0 gives the group value; LPC mode starts at group > 2.0
        // Banks map via HysteresisQuantizer2 on (group - 2.0) * 0.275
        // bank 0 → group ~3.6, bank 1 → group ~4.5, etc.
        // Simplified: space banks evenly from 0.5 to 0.95
        return when (bank) {
            0 -> 0.55f
            1 -> 0.62f
            2 -> 0.70f
            3 -> 0.80f
            4 -> 0.92f
            else -> 0.55f
        }
    }

    /** Maps a word index within a bank to the `morph` parameter value (0..1). */
    fun morphForWord(wordIndex: Int, totalWords: Int): Float {
        if (totalWords <= 1) return 0.5f
        return wordIndex.toFloat() / (totalWords - 1).toFloat()
    }

    fun resolveWord(text: String): WordBankEntry? = vocabulary[text.lowercase()]

    /** Resolve a single character to its letter entry (for spelling). */
    fun resolveLetter(ch: Char): WordBankEntry? {
        val lower = ch.lowercaseChar()
        if (lower in 'a'..'z') {
            val index = lower - 'a'
            return WordBankEntry(bank = 2, wordIndex = index, totalWords = 26, durationMs = 350)
        }
        // Map digits to bank 1
        if (lower in '0'..'9') {
            val index = lower - '0'
            return WordBankEntry(bank = 1, wordIndex = index, totalWords = 11, durationMs = 350)
        }
        return null
    }

    private val vocabulary: Map<String, WordBankEntry> = buildMap {
        // Bank 0: Colors (7 words)
        val colors = listOf("red", "orange", "yellow", "green", "blue", "indigo", "violet")
        colors.forEachIndexed { i, word ->
            put(word, WordBankEntry(0, i, colors.size, if (word.length > 4) 500 else 400))
        }

        // Bank 1: Numbers (11 words: zero through ten)
        val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        numbers.forEachIndexed { i, word ->
            put(word, WordBankEntry(1, i, numbers.size, if (word.length > 4) 400 else 300))
        }
        // Aliases
        put("0", WordBankEntry(1, 0, numbers.size, 400))
        put("1", WordBankEntry(1, 1, numbers.size, 300))
        put("2", WordBankEntry(1, 2, numbers.size, 300))
        put("3", WordBankEntry(1, 3, numbers.size, 350))
        put("4", WordBankEntry(1, 4, numbers.size, 300))
        put("5", WordBankEntry(1, 5, numbers.size, 300))
        put("6", WordBankEntry(1, 6, numbers.size, 300))
        put("7", WordBankEntry(1, 7, numbers.size, 350))
        put("8", WordBankEntry(1, 8, numbers.size, 300))
        put("9", WordBankEntry(1, 9, numbers.size, 300))
        put("10", WordBankEntry(1, 10, numbers.size, 300))

        // Bank 2: Letters (26 words: a through z)
        for (i in 0 until 26) {
            val letter = ('a' + i).toString()
            put(letter, WordBankEntry(2, i, 26, 300))
        }

        // Bank 3: NATO alphabet (26 words)
        val nato = listOf(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
            "golf", "hotel", "india", "juliet", "kilo", "lima",
            "mike", "november", "oscar", "papa", "quebec", "romeo",
            "sierra", "tango", "uniform", "victor", "whiskey", "xray",
            "yankee", "zulu"
        )
        nato.forEachIndexed { i, word ->
            put(word, WordBankEntry(3, i, nato.size, if (word.length > 5) 500 else 400))
        }

        // Bank 4: Synth vocabulary (18 words)
        val synth = listOf(
            "analog", "circuit", "clock", "control", "digital",
            "electronic", "filter", "frequency", "generator", "instrument",
            "knob", "machine", "modular", "modulator", "operator",
            "oscillator", "patch", "sequencer", "synthesizer", "vca",
            "voltage", "waveform"
        )
        synth.forEachIndexed { i, word ->
            put(word, WordBankEntry(4, i, synth.size, if (word.length > 6) 600 else 450))
        }
    }
}
