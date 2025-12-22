package org.balch.songe.core.midi

import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.WebMidiAccess

/**
 * WASM actual implementation - uses WebMidiAccess for Web MIDI API.
 */
actual fun createMidiAccess(): MidiAccess {
    return WebMidiAccess()
}
