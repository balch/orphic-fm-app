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
    const val CMD_SET_MASTER_VOLUME = 20
    const val CMD_SET_DRIVE = 21
    const val CMD_SET_DELAY_MIX = 22
    const val CMD_SET_VIBRATO = 23
    const val CMD_SET_BEND = 24
}
