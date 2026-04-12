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

    // ── Pulsar dedicated delay/reverb ──
    val pulsarDelayUnit = pulsarDelay("pulsarDelay")
    val pulsarReverbUnit = pulsarReverb("pulsarReverb")

    // ── Sum all sources into pre-limiter bus (ADD accumulates, then scale) ──
    // Left bus: horn L + turntable + delay L + reverb L + pulsarDelay L + pulsarReverb L
    val busL = add("busL")
    hornUnit.out to busL.inputA
    turntableUnit.out to busL.inputA
    delay.out to busL.inputA
    rev.out to busL.inputA
    pulsarDelayUnit.out to busL.inputA
    pulsarReverbUnit.out to busL.inputA

    // Right bus: horn R + turntable + delay R + reverb R + pulsarDelay R + pulsarReverb R
    val busR = add("busR")
    hornUnit.outRight to busR.inputA
    turntableUnit.out to busR.inputA
    delay.outRight to busR.inputA
    rev.outRight to busR.inputA
    pulsarDelayUnit.outRight to busR.inputA
    pulsarReverbUnit.outRight to busR.inputA

    // Attenuate summed bus to prevent clipping (6 sources → -8dB ≈ 0.4)
    val busAttenL = multiply("busAttenL") { inputB = 0.4f }
    val busAttenR = multiply("busAttenR") { inputB = 0.4f }
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
        // Tempo clock
        map("org.balch.orpheus.plugins.tempo", "bpm", "clock", IPORT_INPUT_A)
        map("org.balch.orpheus.plugins.tempo", "run", "clock", IPORT_INPUT_B)
    }
}
