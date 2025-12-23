package org.balch.songe.core.input

/**
 * Input events from keyboard or MIDI controllers.
 */
sealed class InputEvent {
    /**
     * Voice trigger on (key pressed or MIDI note on).
     * @param voiceIndex 0-7 for voices 1-8
     * @param velocity 0.0-1.0, 1.0 for keyboard
     */
    data class VoiceOn(val voiceIndex: Int, val velocity: Float = 1f) : InputEvent()

    /**
     * Voice trigger off (key released or MIDI note off).
     * @param voiceIndex 0-7 for voices 1-8
     */
    data class VoiceOff(val voiceIndex: Int) : InputEvent()

    /**
     * Tune adjustment for a voice.
     * @param voiceIndex 0-7 for voices 1-8
     * @param delta Relative change (-1.0 to 1.0)
     */
    data class TuneAdjust(val voiceIndex: Int, val delta: Float) : InputEvent()

    /**
     * Octave shift (affects base pitch).
     * @param octave Current octave offset (-2 to +2)
     */
    data class OctaveShift(val octave: Int) : InputEvent()

    /**
     * Pitch bend (from MIDI).
     * @param value -1.0 to 1.0, 0.0 = center
     */
    data class PitchBend(val value: Float) : InputEvent()

    /**
     * Mod wheel (from MIDI).
     * @param value 0.0 to 1.0
     */
    data class ModWheel(val value: Float) : InputEvent()
}

/**
 * Interface for input controllers (keyboard, MIDI).
 */
interface InputController {
    /**
     * Start listening for input events.
     */
    fun start(onEvent: (InputEvent) -> Unit)

    /**
     * Stop listening for input events.
     */
    fun stop()
}
