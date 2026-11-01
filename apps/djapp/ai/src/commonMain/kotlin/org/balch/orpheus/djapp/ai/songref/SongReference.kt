package org.balch.orpheus.djapp.ai.songref

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType

@LLMDescription("A pasted song page parsed into key, chords, sections, lyric phrase slots and melodies in the vibe's Lick terms.")
@Serializable
data class SongReference(
    val ok: Boolean,
    val message: String,
    val source: SongSource? = null,
    val key: SongKey? = null,
    val chords: List<SongChord> = emptyList(),
    val sections: List<SongSection> = emptyList(),
    val phraseSlots: List<PhraseSlot> = emptyList(),
    val melodies: List<SongMelody> = emptyList(),
    @property:LLMDescription("Things the parser could not read or had to guess.")
    val warnings: List<String> = emptyList(),
)

@LLMDescription("Where the text came from.")
@Serializable
data class SongSource(
    @property:LLMDescription("ultimate-guitar, html or plain.")
    val format: String,
    val title: String? = null,
    val artist: String? = null,
    @property:LLMDescription("Number of text lines read.")
    val lines: Int,
    @property:LLMDescription("Capo fret. Chord names, keys and melody pitches are at concert pitch (already transposed by the capo); a chord's shape is the fingering relative to the capo.")
    val capo: Int = 0,
)

@LLMDescription("The song's key in vibe terms.")
@Serializable
data class SongKey(
    val rootNote: RootNote,
    val scaleType: ScaleType,
    @property:LLMDescription("Readable name, e.g. \"A dorian\".")
    val name: String,
    @property:LLMDescription("0 to 1: how far the best key scored above the runner-up.")
    val confidence: Float,
    @property:LLMDescription("request, page+chords, page, chords, chords+notes or notes.")
    val from: String,
    @property:LLMDescription("The next two likeliest keys.")
    val alternatives: List<String> = emptyList(),
)

@LLMDescription("A distinct chord and how often it occurs.")
@Serializable
data class SongChord(
    @property:LLMDescription("As written, e.g. \"Am7\" or \"C/E\".")
    val name: String,
    val rootNote: RootNote,
    @property:LLMDescription("Normalized quality such as m, 7, maj7, sus4; empty for major.")
    val quality: String,
    @property:LLMDescription("Index of the chord root in the key's scale, null when it is outside the scale.")
    val degree: Int?,
    val count: Int,
    @property:LLMDescription("Fret shape the chord was named from, when the page gave one.")
    val shape: String? = null,
)

enum class SectionKind { INTRO, VERSE, PRE_CHORUS, CHORUS, BRIDGE, SOLO, RIFF, INTERLUDE, OUTRO, OTHER }

@LLMDescription("One labelled stretch of the song.")
@Serializable
data class SongSection(
    val index: Int,
    val name: String,
    val kind: SectionKind,
    val repeats: Int = 1,
    @property:LLMDescription("Index of an earlier section with the same chords, null when unique.")
    val sameAs: Int? = null,
    val chords: List<String> = emptyList(),
    @property:LLMDescription("Scale degree per chord, null where the chord is outside the key.")
    val degrees: List<Int?> = emptyList(),
    @property:LLMDescription("Indexes into phraseSlots.")
    val phraseSlots: List<Int> = emptyList(),
    @property:LLMDescription("Indexes into melodies.")
    val melodies: List<Int> = emptyList(),
    @property:LLMDescription("How the tab's guitar strikes this section's chords, null when it has no chordal tab.")
    val pluck: SongPluck? = null,
)

enum class PluckGrid { DOWNBEATS, EIGHTHS, EIGHTH_OFFBEATS, SIXTEENTHS, SIXTEENTH_OFFBEATS, TRIPLETS, MIXED }

@LLMDescription("How the tab strikes its chords: the beat grid of the strokes and the engine voice mode that re-picks a held chord on it.")
@Serializable
data class SongPluck(
    @property:LLMDescription("Beat grid the strokes fall on: downbeats, eighths, eighth off-beats (reggae skank), sixteenths, sixteenth off-beats (funk upticks), triplets, or mixed. With SPACING a pattern that only fits sixteenths is reported as mixed (PLUCK), never as a 16th re-pick.")
    val grid: PluckGrid,
    @property:LLMDescription("The OrpheusEngine.lpgMode (and TrackSectionOverride.lpgMode) that plucks on this grid: PLUCK for one bloom per note, PLUCK_REPEAT_* to re-pick a held note.")
    val lpgMode: LpgMode,
    @property:LLMDescription("Distinct stroke positions in a 4-beat bar, 0 = the downbeat, 0.5 = the \"&\" of 1.")
    val onsets: List<Float>,
    val picksPerBar: Float,
    @property:LLMDescription("BARS: onsets read from bar lines. SPACING: the tab has no bar lines, so onsets count from the first stroke and may sit shifted against the real beat (an off-beat skank can read as EIGHTHS or DOWNBEATS); use the genre to decide the phase.")
    val rhythm: RhythmSource,
)

@LLMDescription("One lyric line reduced to its length, so the agent can write its own words to fit.")
@Serializable
data class PhraseSlot(
    val index: Int,
    @property:LLMDescription("Index of the section it sits in.")
    val section: Int,
    val text: String,
    val syllables: Int,
    val chords: List<String> = emptyList(),
)

enum class RhythmSource { BARS, SPACING }

@LLMDescription("One melody note.")
@Serializable
data class MelodyNote(
    @property:LLMDescription("Midi note, null for a rest.")
    val midi: Int?,
    @property:LLMDescription("Scientific name, C4 = 60.")
    val name: String?,
    @property:LLMDescription("Lick scale degree, as the vibe's LickStep.scaleDegree.")
    val degree: Int,
    val beats: Float,
    @property:LLMDescription("Tab technique mark such as hammer-on or slide, when present.")
    val technique: String? = null,
    @property:LLMDescription("True when the note is not in the key's scale and was snapped to the nearest tone.")
    val offScale: Boolean = false,
)

@LLMDescription("A melody read from the page, already as a vibe Lick. Every lick is at most 16 beats (4 bars), the longest a vibe at stepCount 64 plays; play the parts of a longer line from consecutive pool slots in consecutive sections (lickIndex).")
@Serializable
data class SongMelody(
    val index: Int,
    val section: Int?,
    val label: String,
    val phraseSlot: Int? = null,
    @property:LLMDescription("BARS: durations from bar lines. SPACING: guessed from tab column spacing.")
    val rhythm: RhythmSource,
    val notes: List<MelodyNote>,
    @property:LLMDescription("At most 16 beats and 64 steps; a longer line arrives as consecutive parts, so use consecutive pool slots (lickIndex) in consecutive sections.")
    val lick: Lick,
    @property:LLMDescription("The lickOctave to give the track so the lick sounds at the written pitch.")
    val lickOctave: Int,
    val offScaleNotes: Int = 0,
    @property:LLMDescription("True when the melody was cut to the parser's melody limit.")
    val truncated: Boolean = false,
)
