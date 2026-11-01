package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.runtime.Immutable

/** A note on the constellation, in beats on the Lick Lab clock; [endBeat] is null while it sounds. */
@Immutable
data class LiveNote(
    val midi: Int,
    val startBeat: Double,
    val endBeat: Double? = null,
    val name: String = LickPitch.noteName(midi),
    /** Scale degree in the chosen slot, null when off the scale. */
    val degree: Int? = null,
)

/** A track the user can arm: melodic and chordal ones only. */
@Immutable
data class TrackChip(val index: Int, val name: String, val engine: String)

@Immutable
data class LickLabUiState(
    val shown: Boolean = false,
    val armedTrack: Int? = null,
    val callerTrack: Int? = null,
    val slot: SlotRef = SlotRef(LickSlot.Lead),
    val keyMode: KeyMode = KeyMode.InKey,
    val recording: Boolean = false,
    /** Degree 0 of [slot] in the vibe's own key. */
    val baseMidi: Int = 60,
    /** "B minor · 0 = B4". */
    val keyLabel: String = "",
    /** The vibe's scale as semitones above its root, for degree math in the UI. */
    val scale: List<Int> = listOf(0, 2, 4, 5, 7, 9, 11),
    /** Entries in the vibe's lick rotation pool, each a [LickSlot.Pool] export target. */
    val poolSize: Int = 0,
    /** Tracks with a solo melodic override, each a [LickSlot.Solo] export target. */
    val soloTracks: Set<Int> = emptySet(),
    val chordRootDegree: Int = -1,
    /** The sounding note in the vibe's own key, before any Kraken shift. */
    val heldMidi: Int? = null,
    val answerNotes: List<LiveNote> = emptyList(),
    val callNotes: List<LiveNote> = emptyList(),
    /** Newest last, at most [LickLabViewModel.MAX_TAKES]. */
    val takes: List<Take> = emptyList(),
    val tracks: List<TrackChip> = emptyList(),
    /** The Kraken shift in effect (-1 home); played notes move with it, exported degrees do not. */
    val krakenTarget: Int = -1,
)
