package org.balch.orpheus.djapp.ai.licklab

import org.balch.orpheus.features.pulsar.models.Vibe

data class TakeNote(
    val startBeat: Double,
    val endBeat: Double,
    val midi: Int,
    val velocity: Float,
    val slidIn: Boolean,
    val glideRate: Float,
)

/**
 * The vibe and slot a take was played in, so it exports against them after a vibe change. [followsChords] marks a
 * take armed on a FOLLOW or ROOT_ONLY track: the sequencer transposes a pasted lick with the chords, live play did not.
 */
data class TakeContext(val vibe: Vibe, val slot: SlotRef, val followsChords: Boolean = false)

/** [context] rides on the take, so readers on any thread export it from the immutable UI state. */
data class Take(
    val id: Long,
    val notes: List<TakeNote>,
    val startBar: Int,
    val beatsPerBar: Int,
    val starred: Boolean = false,
    val context: TakeContext? = null,
)

/** Collects played notes against a beat clock and closes a take after one bar of silence. */
class TakeRecorder(private val beatsPerBar: Int) {
    private val notes = ArrayList<TakeNote>()
    private var open: TakeNote? = null
    private var nextId = 1L

    /** Starts a note, ending any sounding one at [beat]. Returns the take a long gap closed, if any. */
    fun noteOn(beat: Double, midi: Int, velocity: Float, slidIn: Boolean, glideRate: Float): Take? {
        val closed = tick(beat)
        close(beat)
        open = TakeNote(beat, beat, midi, velocity, slidIn, glideRate)
        return closed
    }

    fun noteOff(beat: Double) = close(beat)

    // A note that lasted no time (two keys let go together) never sounded, so it is dropped.
    private fun close(beat: Double) {
        open?.takeIf { beat > it.startBeat }?.let { notes += it.copy(endBeat = beat) }
        open = null
    }

    /** Closes the take once nothing has sounded for a bar. */
    fun tick(beat: Double): Take? {
        val last = notes.lastOrNull() ?: return null
        return if (open == null && beat - last.endBeat >= beatsPerBar) flush(beat) else null
    }

    /** Ends the take now (stop button). */
    fun flush(beat: Double): Take? {
        noteOff(beat)
        if (notes.isEmpty()) return null
        val take = Take(nextId++, notes.toList(), TakeExport.snappedBar(notes.first().startBeat, beatsPerBar), beatsPerBar)
        notes.clear()
        return take
    }
}
