package org.balch.orpheus.core.midi

import dev.atsushieno.ktmidi.EmptyMidiAccess
import dev.atsushieno.ktmidi.MidiAccess

/**
 * Android actual implementation - uses EmptyMidiAccess as a placeholder.
 * Full Android MIDI could be added later using AndroidMidiAccess from ktmidi.
 */
actual fun createMidiAccess(): MidiAccess {
    // For now, return empty - Android touch input is primary
    // To enable Android MIDI, would need to pass Context to AndroidMidiAccess
    return EmptyMidiAccess()
}
