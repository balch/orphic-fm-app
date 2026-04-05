package org.balch.orpheus.core.audio.dsp

/**
 * Minimal DSP wiring graph for DJ App (DJ + Pulsar app).
 *
 * Topology:
 *   Pulsar → Horn (Leslie) → Limiter (distortion/warmth) → Master Out
 *   Turntable → direct + send gains → Limiter → Master Out
 *   Pulsar/Turntable → gated sends → Delay/Reverb → Limiter → Master Out
 *
 * All paths go through the limiter for warmth/saturation.
 * Reverb and delay are purely send-based — zero send = zero effect.
 */
fun buildDjAppWiringGraph(): ByteArray = wiringGraph {
    val pulsarUnit = pulsar("pulsar")
    val turntableUnit = turntable("turntable")
    val hornUnit = horn("horn")
    val delay = dualDelay("delay")
    val rev = reverb("reverb")
    val driveL = limiter("driveL") { driveAmount = 1.0f }
    val driveR = limiter("driveR") { driveAmount = 1.0f }
    val master = masterOut("master")

    // Master clock (sample-accurate tempo generator)
    val clockUnit = clock("clock")

    // ── Pulsar → Horn (Leslie inline) ──
    pulsarUnit.out to hornUnit.inputA
    pulsarUnit.outRight to hornUnit.inputB

    // ── Turntable → send gains → effects ──
    val ttDelaySend = multiply("ttDelaySend") { inputB = 0.0f }
    val ttReverbSend = multiply("ttReverbSend") { inputB = 0.0f }
    turntableUnit.out to ttDelaySend.inputA
    turntableUnit.out to ttReverbSend.inputA

    ttDelaySend.out to delay.inputA
    ttDelaySend.out to delay.inputB

    ttReverbSend.out to rev.inputA
    ttReverbSend.out to rev.inputB

    // ── Pulsar send gains for delay/reverb ──
    val pulsarSendMix = add("pulsarSendMix")
    val pulsarSendHalf = multiply("pulsarSendHalf") { inputB = 0.5f }
    pulsarUnit.out to pulsarSendMix.inputA
    pulsarUnit.outRight to pulsarSendMix.inputB
    pulsarSendMix.out to pulsarSendHalf.inputA
    val pulsarDelaySend = multiply("pulsarDelaySend") { inputB = 0.0f }
    val pulsarReverbSend = multiply("pulsarReverbSend") { inputB = 0.0f }
    pulsarSendHalf.out to pulsarDelaySend.inputA
    pulsarSendHalf.out to pulsarReverbSend.inputA

    pulsarDelaySend.out to delay.inputA
    pulsarDelaySend.out to delay.inputB
    pulsarReverbSend.out to rev.inputA
    pulsarReverbSend.out to rev.inputB

    // ── Sum all sources into pre-limiter bus (ADD accumulates, then scale) ──
    // Left bus: horn L + turntable + delay L + reverb L
    val busL = add("busL")
    hornUnit.out to busL.inputA
    turntableUnit.out to busL.inputA
    delay.out to busL.inputA
    rev.out to busL.inputA

    // Right bus: horn R + turntable + delay R + reverb R
    val busR = add("busR")
    hornUnit.outRight to busR.inputA
    turntableUnit.out to busR.inputA
    delay.outRight to busR.inputA
    rev.outRight to busR.inputA

    // Attenuate summed bus to prevent clipping (4 sources → -6dB = 0.5)
    val busAttenL = multiply("busAttenL") { inputB = 0.5f }
    val busAttenR = multiply("busAttenR") { inputB = 0.5f }
    busL.out to busAttenL.inputA
    busR.out to busAttenR.inputA

    // ── Attenuated bus → Limiter → Master ──
    busAttenL.out to driveL.input
    busAttenR.out to driveR.input
    driveL.out to master.inputA
    driveR.out to master.inputB

    portMap {
        // Distortion (limiter drive)
        map("org.balch.orpheus.plugins.distortion", "drive", "driveL", IPORT_DRIVE)
        map("org.balch.orpheus.plugins.distortion", "drive", "driveR", IPORT_DRIVE)
        // DJ Turntable send levels
        map("org.balch.orpheus.plugins.dj", "delay_send", "ttDelaySend", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.dj", "reverb_send", "ttReverbSend", IPORT_INPUT_B)
        // Pulsar beat machine send levels
        map("org.balch.orpheus.plugins.pulsar", "delay_send", "pulsarDelaySend", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.pulsar", "reverb_send", "pulsarReverbSend", IPORT_INPUT_B)
        // Tempo clock
        map("org.balch.orpheus.plugins.tempo", "bpm", "clock", IPORT_INPUT_A)
        map("org.balch.orpheus.plugins.tempo", "run", "clock", IPORT_INPUT_B)
    }
}
