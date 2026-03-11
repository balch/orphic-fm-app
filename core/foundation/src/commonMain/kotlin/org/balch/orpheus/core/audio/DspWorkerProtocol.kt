package org.balch.orpheus.core.audio

/**
 * Command IDs for the main-thread ↔ DSP Worker postMessage protocol.
 *
 * Shared between [DspWorkerProxy] (main thread) and
 * [CommandDispatcher] (worker thread) so they stay in sync.
 */
object DspWorkerProtocol {
    const val CMD_INIT = 0
    const val CMD_START = 1
    const val CMD_STOP = 2
    const val CMD_SET_PORT = 10
    const val CMD_VOICE_GATE = 11
    const val CMD_VOICE_TUNE = 12
    const val CMD_TRIGGER_DRUM = 13
    const val CMD_VOICE_ENGINE = 14
    const val CMD_VOICE_ACTIVE = 15
    const val CMD_VOICE_HOLD = 16
    const val CMD_VOICE_HARMONICS = 17
    const val CMD_VOICE_TIMBRE = 18
    const val CMD_VOICE_MORPH = 19
    const val CMD_SET_MASTER_VOLUME = 20
    const val CMD_SET_DRIVE = 21
    const val CMD_SET_DELAY_MIX = 22
    const val CMD_SET_VIBRATO = 23
    const val CMD_SET_BEND = 24
    const val CMD_VOICE_DECAY = 25
    const val CMD_SET_VIBRATO_RATE = 26
    const val CMD_LOAD_GRAPH = 30
}
