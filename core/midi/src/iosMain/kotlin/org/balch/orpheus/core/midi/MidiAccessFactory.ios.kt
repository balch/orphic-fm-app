package org.balch.orpheus.core.midi

import com.diamondedge.logging.logging
import dev.atsushieno.ktmidi.EmptyMidiAccess
import dev.atsushieno.ktmidi.MidiAccess

private val log = logging("MidiAccessFactory")

/**
 * iOS actual implementation.
 *
 * Returns EmptyMidiAccess for now. A future implementation can use
 * ktmidi's CoreMidiAccess or a custom wrapper around CoreMIDI.framework.
 */
actual fun createMidiAccess(): MidiAccess {
    log.info { "iOS MIDI access not yet implemented — returning empty access" }
    return EmptyMidiAccess()
}
