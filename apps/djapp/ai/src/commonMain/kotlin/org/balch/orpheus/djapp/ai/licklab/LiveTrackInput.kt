package org.balch.orpheus.djapp.ai.licklab

import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol

/** Plays one Pulsar track live through its `track_N_live_*` ports. Owns the strike counter. */
class LiveTrackInput(private val controller: SynthController) {
    internal var strikeCount = 0

    fun arm(track: Int) = int(PulsarSymbol.TRACK_0_LIVE_ACTIVE, track, 1)

    fun disarm(track: Int) {
        int(PulsarSymbol.TRACK_0_LIVE_GATE, track, 0)
        int(PulsarSymbol.TRACK_0_LIVE_ACTIVE, track, 0)
    }

    /**
     * Velocity and glide land before the note, and the note before the strike, so nothing sounds stale.
     * The engine latches a count seen a block before its gate, so the rising gate does not strike again.
     */
    fun strike(track: Int, midi: Float, velocity: Float, glide: Float) {
        float(PulsarSymbol.TRACK_0_LIVE_VELOCITY, track, velocity)
        float(PulsarSymbol.TRACK_0_LIVE_GLIDE, track, glide)
        float(PulsarSymbol.TRACK_0_LIVE_NOTE, track, midi)
        // The port clamps at 2^24; wrapping to 1 still differs from the previous count.
        strikeCount = if (strikeCount >= STRIKE_WRAP) 1 else strikeCount + 1
        int(PulsarSymbol.TRACK_0_LIVE_STRIKE, track, strikeCount)
        int(PulsarSymbol.TRACK_0_LIVE_GATE, track, 1)
    }

    /** A pitch change with the gate held: no re-strike, [glide] 0 is a hammer-on. */
    fun legato(track: Int, midi: Float, glide: Float) {
        float(PulsarSymbol.TRACK_0_LIVE_GLIDE, track, glide)
        float(PulsarSymbol.TRACK_0_LIVE_NOTE, track, midi)
    }

    /** Bend follows the finger, so it never glides. */
    fun bend(track: Int, midi: Float) {
        float(PulsarSymbol.TRACK_0_LIVE_GLIDE, track, 0f)
        float(PulsarSymbol.TRACK_0_LIVE_NOTE, track, midi)
    }

    fun release(track: Int) = int(PulsarSymbol.TRACK_0_LIVE_GATE, track, 0)

    private fun id(first: PulsarSymbol, track: Int) = PulsarSymbol.entries[first.ordinal + track].controlId

    private fun int(first: PulsarSymbol, track: Int, value: Int) {
        controller.setPluginControl(id(first, track), PortValue.IntValue(value))
    }

    private fun float(first: PulsarSymbol, track: Int, value: Float) {
        controller.setPluginControl(id(first, track), PortValue.FloatValue(value))
    }

    companion object {
        internal const val STRIKE_WRAP = 16_000_000
    }
}
