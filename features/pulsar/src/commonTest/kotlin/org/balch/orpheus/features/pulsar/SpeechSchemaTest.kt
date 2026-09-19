package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SpeechCue
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeSpeech
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SpeechSchemaTest {

    private fun speechVibe(sections: List<Section>, speech: VibeSpeech?, stepCount: Int = 32) = Vibe(
        name = "Speech Schema Test",
        bpm = 80f,
        rootNote = RootNote.E,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
            swingAmount = 0f, ghostProbability = 0f,
            noteRangeLow = 36, noteRangeHigh = 72,
            rhythmDensity = RhythmPattern.SPARSE.density,
        ),
        tracks = List(8) {
            TrackVoice(
                engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
                engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
                role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
            )
        },
        stepCount = stepCount,
        arrangement = Arrangement(sections = sections),
        speech = speech,
    )

    @Test
    fun vibeSpeechBoundsPhraseCountAndBlankPhrases() {
        assertFailsWith<IllegalArgumentException> { VibeSpeech(phrases = emptyList()) }
        assertFailsWith<IllegalArgumentException> { VibeSpeech(phrases = List(5) { "p$it" }) }
        assertFailsWith<IllegalArgumentException> { VibeSpeech(phrases = listOf("ok", " ")) }
        assertFailsWith<IllegalArgumentException> { VibeSpeech(phrases = listOf("ok"), wordsPerMinute = 40) }
    }

    @Test
    fun speechCueRejectsOutOfRangeFields() {
        assertFailsWith<IllegalArgumentException> { SpeechCue(phrase = 4) }
        assertFailsWith<IllegalArgumentException> { SpeechCue(phrase = 0, beat = -1f) }
        assertFailsWith<IllegalArgumentException> { SpeechCue(phrase = 0, everyLoops = 2, loopPhase = 2) }
        assertFailsWith<IllegalArgumentException> { SpeechCue(phrase = 0, chance = 1.5f) }
        assertFailsWith<IllegalArgumentException> {
            Section(name = "busy", speech = List(5) { SpeechCue(phrase = 0) })
        }
    }

    @Test
    fun vibeRequiresSpeechForCuesAPhraseForEachCueAndABeatInsideTheLoop() {
        val cue = Section(name = "talk", speech = listOf(SpeechCue(phrase = 1)))
        assertFailsWith<IllegalArgumentException> { speechVibe(listOf(cue), speech = null) }
        assertFailsWith<IllegalArgumentException> {
            speechVibe(listOf(cue), speech = VibeSpeech(phrases = listOf("only one")))
        }
        val late = Section(name = "late", speech = listOf(SpeechCue(phrase = 0, beat = 5f)))
        assertFailsWith<IllegalArgumentException> {
            speechVibe(listOf(late), speech = VibeSpeech(phrases = listOf("hi")), stepCount = 16)
        }
    }

    @Test
    fun arrangementCapsTotalCuesAtTheWireRowCount() {
        val full = Section(name = "full", speech = List(SpeechCue.MAX_PER_SECTION) { SpeechCue(phrase = 0) })
        assertFailsWith<IllegalArgumentException> {
            Arrangement(sections = List(7) { full })   // 28 cues > 24 rows
        }
    }
}
