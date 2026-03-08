package org.balch.orpheus.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds the default 12-voice + effects wiring graph descriptor.
 * Returns the ODWG binary format consumed by the C++ graph runtime.
 *
 * Graph structure:
 *   12x Plaits voices -> per-voice volume + pan -> summing tree ->
 *   master volume -> clouds -> rings -> drive -> warps ->
 *   delay (sends: grains+drive+warps, LFO mod) ->
 *   reverb (parallel send from drive) ->
 *   hard clip -> master out (interleaved stereo)
 */
fun buildDefaultWiringGraph(): ByteArray = wiringGraph {
    // Default pan values matching Kotlin StereoPlugin and C++ defaults:
    // 0,1=center, 2,3=left(-0.3), 4,5=right(0.3), 6=left(-0.7), 7=right(0.7), 8-11=center
    val defaultPans = floatArrayOf(0f, 0f, -0.3f, -0.3f, 0.3f, 0.3f, -0.7f, 0.7f, 0f, 0f, 0f, 0f)

    // Constant-power pan gains from a -1..+1 pan position
    fun panGains(pan: Float): Pair<Float, Float> {
        val angle = ((pan + 1f) * 0.5f) * (PI.toFloat() * 0.5f)
        return cos(angle) to sin(angle)
    }

    val voiceOutsL = mutableListOf<UnitRef>()
    val voiceOutsR = mutableListOf<UnitRef>()
    val plaitsUnits = mutableListOf<UnitRef>()

    for (v in 0 until 12) {
        val p = plaits("v${v}_p") { moduleIndex = v.toFloat() }
        plaitsUnits.add(p)
        val vol = multiply("v${v}_vol") { inputB = 1.0f }
        val (gl, gr) = panGains(defaultPans[v])
        val pL = multiply("v${v}_pL") { inputB = gl }
        val pR = multiply("v${v}_pR") { inputB = gr }

        p.out to vol.inputA
        vol.out to pL.inputA
        vol.out to pR.inputA
        voiceOutsL.add(pL)
        voiceOutsR.add(pR)
    }

    // Summing tree: each passThrough port supports max 4 sources.
    // Groups of 4, then recurse until a single sum node remains.
    fun buildSumTree(prefix: String, outs: List<UnitRef>): UnitRef {
        if (outs.size <= 4) {
            val s = passThrough(prefix)
            for (o in outs) { o.out to s.input }
            return s
        }
        val groups = outs.chunked(4).mapIndexed { i, grp ->
            val g = passThrough("${prefix}_g$i")
            for (o in grp) { o.out to g.input }
            g
        }
        return buildSumTree(prefix, groups)
    }

    val sumL = buildSumTree("sumL", voiceOutsL)
    val sumR = buildSumTree("sumR", voiceOutsR)

    // Master volume (stereo)
    // 0.4 = 0.8 volume × 0.5 headroom (matching procedural path's master_gain)
    val mvL = multiply("mvL") { inputB = 0.4f }
    val mvR = multiply("mvR") { inputB = 0.4f }
    sumL.out to mvL.inputA
    sumR.out to mvR.inputA

    // Clouds/Grains (stereo in/out) - bypassed by default via engine atomics
    val grains = clouds("grains")
    mvL.out to grains.inputA
    mvR.out to grains.inputB

    // Rings/Resonator with full 4-input excitation/bypass architecture
    // Drum inputs = post-grains (Clouds output)
    // Synth inputs = master volume output (pre-effects)
    // targetMix: 0=drum only, 0.5=both, 1=synth only
    // mix: wet/dry on resonated signal

    // Excitation path: drum + synth inputs scaled by targetMix gains
    val drumExGainL = multiply("drumExGainL") { inputB = 1.0f }
    val drumExGainR = multiply("drumExGainR") { inputB = 1.0f }
    val synthExGainL = multiply("synthExGainL") { inputB = 1.0f }
    val synthExGainR = multiply("synthExGainR") { inputB = 1.0f }

    grains.out to drumExGainL.inputA
    grains.outRight to drumExGainR.inputA
    mvL.out to synthExGainL.inputA
    mvR.out to synthExGainR.inputA

    // Sum excitation to mono for Rings input
    val exciteSumL = passThrough("exciteSumL")
    drumExGainL.out to exciteSumL.input
    synthExGainL.out to exciteSumL.input

    val exciteSumR = passThrough("exciteSumR")
    drumExGainR.out to exciteSumR.input
    synthExGainR.out to exciteSumR.input

    // Mix L+R to mono for Rings
    val resoMix = add("resoMix")
    val resoHalf = multiply("resoHalf") { inputB = 0.5f }
    exciteSumL.out to resoMix.inputA
    exciteSumR.out to resoMix.inputB
    resoMix.out to resoHalf.inputA

    val reso = rings("resonator")
    resoHalf.out to reso.input

    // Bypass path: inverse gains (dry signal)
    val drumBpGainL = multiply("drumBpGainL") { inputB = 0.0f }
    val drumBpGainR = multiply("drumBpGainR") { inputB = 0.0f }
    val synthBpGainL = multiply("synthBpGainL") { inputB = 0.0f }
    val synthBpGainR = multiply("synthBpGainR") { inputB = 0.0f }

    grains.out to drumBpGainL.inputA
    grains.outRight to drumBpGainR.inputA
    mvL.out to synthBpGainL.inputA
    mvR.out to synthBpGainR.inputA

    val bypassSumL = passThrough("bypassSumL")
    drumBpGainL.out to bypassSumL.input
    synthBpGainL.out to bypassSumL.input

    val bypassSumR = passThrough("bypassSumR")
    drumBpGainR.out to bypassSumR.input
    synthBpGainR.out to bypassSumR.input

    // Wet/dry mix on resonated signal
    val wetGainL = multiply("wetGainL") { inputB = 0.5f }
    val wetGainR = multiply("wetGainR") { inputB = 0.5f }
    val dryGainL = multiply("dryGainL") { inputB = 0.5f }
    val dryGainR = multiply("dryGainR") { inputB = 0.5f }

    reso.out to wetGainL.inputA
    reso.outRight to wetGainR.inputA
    exciteSumL.out to dryGainL.inputA
    exciteSumR.out to dryGainR.inputA

    // Final resonator output: wet + dry + bypass
    val resoOutL = passThrough("resoOutL")
    val resoOutR = passThrough("resoOutR")
    wetGainL.out to resoOutL.input
    dryGainL.out to resoOutL.input
    bypassSumL.out to resoOutL.input
    wetGainR.out to resoOutR.input
    dryGainR.out to resoOutR.input
    bypassSumR.out to resoOutR.input

    // Drive / limiter (stereo) - after resonator, matching JSyn chain order
    val driveL = limiter("driveL") { driveAmount = 1.0f }
    val driveR = limiter("driveR") { driveAmount = 1.0f }
    resoOutL.out to driveL.input
    resoOutR.out to driveR.input

    // Warps (source-routed internally via engine source buffers)
    val warp = warps("warps")
    // No fixed input connections — unit_process_warps reads from source buffers

    // Dual Delay (stereo in/out) - bypassed by default via engine atomics
    // JSyn sends grains+distortion+warps all to delay (summed at input)
    val delay = dualDelay("delay")
    grains.out to delay.inputA
    grains.outRight to delay.inputB
    driveL.out to delay.inputA
    driveR.out to delay.inputB
    warp.out to delay.inputA
    warp.outRight to delay.inputB

    // HyperLFO → Delay modulation (LFO output modulates delay time)
    val lfo = hyperLfo("lfo")
    lfo.out to delay.inputC

    // Master clock (sample-accurate tempo generator)
    val clock = clock("clock")

    // Grids drum pattern generator — clocked from master clock
    val gridsUnit = grids("grids")
    clock.out to gridsUnit.inputA          // 24 PPQN clock ticks
    clock.outRight to gridsUnit.inputB     // beat pulse (unused but available)

    // Marbles random sequencer (Flux) — clocked from master clock
    val marblesUnit = marbles("marbles")
    clock.out to marblesUnit.inputA        // 24 PPQN clock input

    // Wire Grids triggers to drum voices (voices 8, 9, 10)
    gridsUnit.out to plaitsUnits[8].gate        // kick → voice 8
    gridsUnit.outRight to plaitsUnits[9].gate   // snare → voice 9
    gridsUnit.aux to plaitsUnits[10].gate       // hat → voice 10

    // Beat-quantized looper
    val looper = looper("looper")
    driveL.out to looper.inputA         // audio in L (post-drive)
    driveR.out to looper.inputB         // audio in R
    clock.outRight to looper.inputC     // beat pulse for quantization
    looper.out to delay.inputA          // looper output → delay
    looper.outRight to delay.inputB

    // Reverb (Dattorro plate) — parallel send from drive output
    // Wet-only output sums into clip inputs alongside delay output
    val reverb = reverb("reverb")
    driveL.out to reverb.inputA
    driveR.out to reverb.inputB

    // Master clip + output
    val clipL = hardClip("clipL")
    val clipR = hardClip("clipR")
    val master = masterOut("master")
    delay.out to clipL.input
    delay.outRight to clipR.input
    reverb.out to clipL.input
    reverb.outRight to clipR.input
    clipL.out to master.inputA
    clipR.out to master.inputB

    // Port map for nativeSetPort routing
    portMap {
        map("org.balch.orpheus.plugins.stereo", "master_vol", "mvL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.stereo", "master_vol", "mvR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.distortion", "drive", "driveL", IPORT_DRIVE)
        map("org.balch.orpheus.plugins.distortion", "drive", "driveR", IPORT_DRIVE)
        // Per-quad volume: sets inputB on all voice volume multiply nodes in each quad
        for (v in 0..3) map("org.balch.orpheus.plugins.stereo", "quad_vol_0", "v${v}_vol", IPORT_INPUT_B)
        for (v in 4..7) map("org.balch.orpheus.plugins.stereo", "quad_vol_1", "v${v}_vol", IPORT_INPUT_B)
        for (v in 8..11) map("org.balch.orpheus.plugins.stereo", "quad_vol_2", "v${v}_vol", IPORT_INPUT_B)
        // Tempo clock
        map("org.balch.orpheus.plugins.tempo", "bpm", "clock", IPORT_INPUT_A)
        map("org.balch.orpheus.plugins.tempo", "run", "clock", IPORT_INPUT_B)
        // Resonator targetMix-derived gains
        map("org.balch.orpheus.plugins.resonator", "drum_ex_gain", "drumExGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "drum_ex_gain", "drumExGainR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "synth_ex_gain", "synthExGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "synth_ex_gain", "synthExGainR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "drum_bp_gain", "drumBpGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "drum_bp_gain", "drumBpGainR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "synth_bp_gain", "synthBpGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "synth_bp_gain", "synthBpGainR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "wet_gain", "wetGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "wet_gain", "wetGainR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "dry_gain", "dryGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.resonator", "dry_gain", "dryGainR", IPORT_INPUT_B)
        // Per-voice pan gains (constant-power, computed in Kotlin)
        for (v in 0 until 12) {
            map("org.balch.orpheus.plugins.stereo", "voice_pan_L_$v", "v${v}_pL", IPORT_INPUT_B)
            map("org.balch.orpheus.plugins.stereo", "voice_pan_R_$v", "v${v}_pR", IPORT_INPUT_B)
        }
    }
}
