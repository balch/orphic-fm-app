package org.balch.songe.core.midi

import dev.atsushieno.ktmidi.MidiAccess

/**
 * Platform-specific factory to create the appropriate MidiAccess implementation.
 */
expect fun createMidiAccess(): MidiAccess
