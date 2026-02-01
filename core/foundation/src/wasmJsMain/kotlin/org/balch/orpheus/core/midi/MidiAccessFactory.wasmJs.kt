package org.balch.orpheus.core.midi

import com.diamondedge.logging.logging
import dev.atsushieno.ktmidi.EmptyMidiAccess
import dev.atsushieno.ktmidi.MidiAccess

private val log = logging("MidiAccessFactory")

/**
 * WASM actual implementation - uses ktmidi's WebMidiAccess for browser MIDI support.
 */
// Cache the instance to prevent repeated permission requests and log spam
private val cachedMidiAccess by lazy {
    try {
        // ktmidi provides WebMidiAccess for WASM/JS targets
        // It wraps navigator.requestMIDIAccess()
        dev.atsushieno.ktmidi.WebMidiAccess()
    } catch (e: Exception) {
        log.warn { "Web MIDI API not available: ${e.message}" }
        EmptyMidiAccess()
    }
}

/**
 * WASM actual implementation - uses ktmidi's WebMidiAccess for browser MIDI support.
 */
actual fun createMidiAccess(): MidiAccess = cachedMidiAccess
