package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * Phrases a vibe speaks, generated once with the platform's text-to-speech when the vibe
 * loads (macOS desktop today). [Section.speech] cues place them inside the loop.
 *
 * @param phrases 1..[MAX_PHRASES]; a phrase's index is its clip slot.
 * @param voice Platform voice name (macOS `say -v`); null = the system default.
 * @param wordsPerMinute Speaking rate, 80..300; null = the voice's default.
 */
@Serializable
data class VibeSpeech(
    val phrases: List<String>,
    val voice: String? = null,
    val wordsPerMinute: Int? = null,
) {
    init {
        require(phrases.size in 1..MAX_PHRASES) {
            "VibeSpeech.phrases must have 1..$MAX_PHRASES entries, got ${phrases.size}"
        }
        require(phrases.none { it.isBlank() }) { "VibeSpeech.phrases must not contain a blank phrase" }
        require(wordsPerMinute == null || wordsPerMinute in 80..300) {
            "VibeSpeech.wordsPerMinute must be 80..300, got $wordsPerMinute"
        }
    }

    companion object {
        /** Clip slots. MUST equal `kMaxSpeechClips` in `liborpheus_dsp/src/pulsar_speech.h`. */
        const val MAX_PHRASES = 4
    }
}

/**
 * One phrase placed inside a section's loop-cycle.
 *
 * @param phrase Index into [VibeSpeech.phrases].
 * @param beat Position in the loop-cycle in beats; null = the loop's end (the next downbeat).
 * @param alignEnd True: the phrase's last syllable lands on the position; false: its first
 *   does. An end-aligned phrase at the loop's end also guarantees track 0's kick there.
 * @param everyLoops Speak on every Nth loop-cycle of the section.
 * @param loopPhase Which of those N, counted from the section's first loop-cycle (0).
 * @param chance Roll per eligible loop-cycle, 0-1.
 * @param level Loudness, 0-1.
 */
@Serializable
data class SpeechCue(
    val phrase: Int,
    val beat: Float? = null,
    val alignEnd: Boolean = true,
    val everyLoops: Int = 1,
    val loopPhase: Int = 0,
    val chance: Float = 1f,
    val level: Float = 0.8f,
) {
    init {
        require(phrase in 0 until VibeSpeech.MAX_PHRASES) {
            "SpeechCue.phrase must be 0..${VibeSpeech.MAX_PHRASES - 1}, got $phrase"
        }
        require(beat == null || beat >= 0f) { "SpeechCue.beat must be >= 0, got $beat" }
        require(everyLoops >= 1) { "SpeechCue.everyLoops must be >= 1, got $everyLoops" }
        require(loopPhase in 0 until everyLoops) {
            "SpeechCue.loopPhase must be 0..${everyLoops - 1}, got $loopPhase"
        }
        require(chance in 0f..1f) { "SpeechCue.chance must be 0..1, got $chance" }
        require(level in 0f..1f) { "SpeechCue.level must be 0..1, got $level" }
    }

    companion object {
        const val MAX_PER_SECTION = 4

        /** Cue rows across an arrangement. MUST equal `kMaxSpeechCueRows` in `pulsar_speech.h`. */
        const val MAX_ROWS = 24
    }
}
