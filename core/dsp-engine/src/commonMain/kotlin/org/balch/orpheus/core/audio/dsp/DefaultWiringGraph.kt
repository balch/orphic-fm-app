package org.balch.orpheus.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds the default 12-voice + 3-drum-voice + effects wiring graph descriptor.
 * Returns the ODWG binary format consumed by the C++ graph runtime.
 *
 * Graph structure:
 *   12x Plaits voices -> per-voice volume + pan -> summing tree ->
 *   master volume -> clouds -> rings -> drive -> warps ->
 *   delay (sends: grains+drive+warps, LFO mod) ->
 *   reverb (parallel send from drive) ->
 *   hard clip -> master out (interleaved stereo)
 *
 *   3x Dedicated drum voices (slots 12-14) -> drum sum ->
 *     MAIN path: drum resonator -> limiter -> clip -> master out
 *     FX path: drum sum -> main resonator excitation inputs
 *   Drum routing toggle: drumDirectGain (MAIN) vs drumChainGain (FX)
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
    val duoVoiceUnits = mutableListOf<UnitRef>()

    for (duo in 0 until 6) {
        val vA = duo * 2
        val vB = vA + 1
        val dv = duoVoice("duo${duo}_dv") { moduleIndex = duo.toFloat() }
        duoVoiceUnits.add(dv)

        // Voice A downstream: volume + pan
        val volA = multiply("v${vA}_vol") { inputB = 1.0f }
        val (glA, grA) = panGains(defaultPans[vA])
        val pLA = multiply("v${vA}_pL") { inputB = glA }
        val pRA = multiply("v${vA}_pR") { inputB = grA }

        dv.out to volA.inputA       // OPORT_OUT = voice A
        volA.out to pLA.inputA
        volA.out to pRA.inputA
        voiceOutsL.add(pLA)
        voiceOutsR.add(pRA)

        // Voice B downstream: volume + pan
        val volB = multiply("v${vB}_vol") { inputB = 1.0f }
        val (glB, grB) = panGains(defaultPans[vB])
        val pLB = multiply("v${vB}_pL") { inputB = glB }
        val pRB = multiply("v${vB}_pR") { inputB = grB }

        dv.aux to volB.inputA       // OPORT_AUX = voice B
        volB.out to pLB.inputA
        volB.out to pRB.inputA
        voiceOutsL.add(pLB)
        voiceOutsR.add(pRB)
    }

    // ── Dedicated drum voices (3 slots) ──
    val drumSlotGains = floatArrayOf(1.2f, 0.6f, 0.5f)
    val drumPlaitsUnits = mutableListOf<UnitRef>()
    val drumOutsL = mutableListOf<UnitRef>()
    val drumOutsR = mutableListOf<UnitRef>()

    for (d in 0 until 3) {
        val dp = plaits("d${d}_p") { moduleIndex = (12 + d).toFloat() }
        drumPlaitsUnits.add(dp)
        val dv = multiply("d${d}_vol") { inputB = drumSlotGains[d] }
        val (gl, gr) = panGains(0f)  // center pan
        val dL = multiply("d${d}_pL") { inputB = gl }
        val dR = multiply("d${d}_pR") { inputB = gr }

        dp.out to dv.inputA
        dv.out to dL.inputA
        dv.out to dR.inputA
        drumOutsL.add(dL)
        drumOutsR.add(dR)
    }

    // Drum sum (all 3 drums per channel)
    val drumSumL = passThrough("drumSumL")
    val drumSumR = passThrough("drumSumR")
    for (d in drumOutsL) { d.out to drumSumL.input }
    for (d in drumOutsR) { d.out to drumSumR.input }

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

    // Master volume pass-through (volume is applied in masterOut after pan:
    // pan → volume → peak → clip → output)
    val mvL = multiply("mvL") { inputB = 1.0f }
    val mvR = multiply("mvR") { inputB = 1.0f }
    sumL.out to mvL.inputA
    sumR.out to mvR.inputA

    // Clouds/Grains (stereo in/out) - bypassed by default via engine atomics
    val grains = clouds("grains")
    mvL.out to grains.inputA
    mvR.out to grains.inputB

    // Rings/Resonator with full 4-input excitation/bypass architecture
    // Drum inputs = post-grains (Clouds output) + dedicated drum FX path
    // Synth inputs = master volume output (pre-effects)
    // targetMix: 0=drum only, 0.5=both, 1=synth only
    // mix: wet/dry on resonated signal

    // Excitation path: drum + synth inputs scaled by targetMix gains
    val drumExGainL = multiply("drumExGainL") { inputB = 1.0f }
    val drumExGainR = multiply("drumExGainR") { inputB = 1.0f }
    val synthExGainL = multiply("synthExGainL") { inputB = 1.0f }
    val synthExGainR = multiply("synthExGainR") { inputB = 1.0f }

    // Drum FX path: drum sum → gain gate → drum excitation (no grains/synth mixing)
    val drumChainGainL = multiply("drumChainGainL") { inputB = 0.0f }
    val drumChainGainR = multiply("drumChainGainR") { inputB = 0.0f }
    drumSumL.out to drumChainGainL.inputA
    drumSumR.out to drumChainGainR.inputA

    // Drum excitation = drum chain only (no grains merge to prevent synth leak)
    drumChainGainL.out to drumExGainL.inputA
    drumChainGainR.out to drumExGainR.inputA

    // Synth excitation = post-grains output (grains processes synth signal)
    grains.out to synthExGainL.inputA
    grains.outRight to synthExGainR.inputA

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

    // Drum bypass = drum sum (drums that bypass resonator go straight to output)
    drumSumL.out to drumBpGainL.inputA
    drumSumR.out to drumBpGainR.inputA
    // Synth bypass = post-grains (synth that bypasses resonator goes straight to output)
    grains.out to synthBpGainL.inputA
    grains.outRight to synthBpGainR.inputA

    val bypassSumL = passThrough("bypassSumL")
    drumBpGainL.out to bypassSumL.input
    synthBpGainL.out to bypassSumL.input

    val bypassSumR = passThrough("bypassSumR")
    drumBpGainR.out to bypassSumR.input
    synthBpGainR.out to bypassSumR.input

    // Wet/dry mix on resonated signal
    val wetGainL = multiply("wetGainL") { inputB = 0.0f }
    val wetGainR = multiply("wetGainR") { inputB = 0.0f }
    val dryGainL = multiply("dryGainL") { inputB = 1.0f }
    val dryGainR = multiply("dryGainR") { inputB = 1.0f }

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

    // Drive / limiter (stereo) - after resonator
    val driveL = limiter("driveL") { driveAmount = 1.0f }
    val driveR = limiter("driveR") { driveAmount = 1.0f }
    resoOutL.out to driveL.input
    resoOutR.out to driveR.input

    // Warps (source-routed internally via engine source buffers)
    val warp = warps("warps")
    // No fixed input connections — unit_process_warps reads from source buffers

    // Dual Delay (stereo in/out) - bypassed by default via engine atomics
    // grains+distortion+warps all feed delay (summed at input)
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
    clock.outRight to marblesUnit.inputA    // beat pulse (matches Kotlin's quarter-note clock)

    // Wire Grids triggers to dedicated drum voices (slots 12, 13, 14)
    gridsUnit.out to drumPlaitsUnits[0].gate        // kick → drum voice 12
    gridsUnit.outRight to drumPlaitsUnits[1].gate   // snare → drum voice 13
    gridsUnit.aux to drumPlaitsUnits[2].gate        // hat → drum voice 14

    // Beat-quantized looper
    val looper = looper("looper")
    driveL.out to looper.inputA         // audio in L (post-drive)
    driveR.out to looper.inputB         // audio in R
    clock.outRight to looper.inputC     // beat pulse for quantization
    looper.out to delay.inputA          // looper output → delay
    looper.outRight to delay.inputB

    // Global Bender (pitch CV + timbre CV + audio)
    val benderUnit = bender("bender")
    benderUnit.aux to delay.inputA       // bender audio → delay send
    benderUnit.aux to delay.inputB

    // Reverb (Dattorro plate) — parallel send from drive output
    // Wet-only output sums into clip inputs alongside delay output
    val reverb = reverb("reverb")
    driveL.out to reverb.inputA
    driveR.out to reverb.inputB

    // Master output (no hard clip)
    val master = masterOut("master")

    // ── Drum MAIN path routing ──
    // Default: MAIN mode (drumDirectGain=1, drumChainGain=0)

    // MAIN path: drum sum → gain gate → dedicated resonator chain
    val drumDirectGainL = multiply("drumDirectGainL") { inputB = 1.0f }
    val drumDirectGainR = multiply("drumDirectGainR") { inputB = 1.0f }
    drumSumL.out to drumDirectGainL.inputA
    drumSumR.out to drumDirectGainR.inputA

    // Dedicated drum resonator dry path
    val drumDirectResoDryGainL = multiply("drumDirectResoDryGainL") { inputB = 1.0f }
    val drumDirectResoDryGainR = multiply("drumDirectResoDryGainR") { inputB = 1.0f }
    drumDirectGainL.out to drumDirectResoDryGainL.inputA
    drumDirectGainR.out to drumDirectResoDryGainR.inputA

    // Mix L+R to mono for drum Rings input
    val drumResoMix = add("drumResoMix")
    val drumResoHalf = multiply("drumResoHalf") { inputB = 0.5f }
    drumDirectGainL.out to drumResoMix.inputA
    drumDirectGainR.out to drumResoMix.inputB
    drumResoMix.out to drumResoHalf.inputA

    val drumReso = rings("drumResonator") { moduleIndex = 1.0f }
    drumResoHalf.out to drumReso.input

    val drumDirectResoWetGainL = multiply("drumDirectResoWetGainL") { inputB = 0.0f }
    val drumDirectResoWetGainR = multiply("drumDirectResoWetGainR") { inputB = 0.0f }
    drumReso.out to drumDirectResoWetGainL.inputA
    drumReso.outRight to drumDirectResoWetGainR.inputA

    // Sum dry + wet
    val drumDirectResoSumL = add("drumDirectResoSumL")
    val drumDirectResoSumR = add("drumDirectResoSumR")
    drumDirectResoDryGainL.out to drumDirectResoSumL.inputA
    drumDirectResoWetGainL.out to drumDirectResoSumL.inputB
    drumDirectResoDryGainR.out to drumDirectResoSumR.inputA
    drumDirectResoWetGainR.out to drumDirectResoSumR.inputB

    // Limiter (drive=1.0)
    val drumDirectLimiterL = limiter("drumDirectLimiterL") { driveAmount = 1.0f }
    val drumDirectLimiterR = limiter("drumDirectLimiterR") { driveAmount = 1.0f }
    drumDirectResoSumL.out to drumDirectLimiterL.input
    drumDirectResoSumR.out to drumDirectLimiterR.input

    // MAIN path output → master (direct to output, no hard clip)
    drumDirectLimiterL.out to master.inputA
    drumDirectLimiterR.out to master.inputB

    // Per-String Bender (4 strings × 2 voices + audio)
    val psb = perStringBender("psb")
    psb.out to master.inputA               // per-string audio → output
    psb.outRight to master.inputB
    warp.out to master.inputA              // Warps direct to output
    warp.outRight to master.inputB
    delay.out to master.inputA
    delay.outRight to master.inputB
    reverb.out to master.inputA
    reverb.outRight to master.inputB

    // Port map for nativeSetPort routing
    portMap {
        // master_vol is applied in masterOut (engine atomic), not via graph ports
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
        // Per-drum volume
        map("org.balch.orpheus.plugins.drum", "bd_vol", "d0_vol", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "sd_vol", "d1_vol", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "hh_vol", "d2_vol", IPORT_INPUT_B)
        // Per-drum pan
        for (d in 0 until 3) {
            map("org.balch.orpheus.plugins.drum", "drum_pan_L_$d", "d${d}_pL", IPORT_INPUT_B)
            map("org.balch.orpheus.plugins.drum", "drum_pan_R_$d", "d${d}_pR", IPORT_INPUT_B)
        }
        // Drum FX/MAIN bypass toggle
        map("org.balch.orpheus.plugins.drum", "drum_chain_gain_l", "drumChainGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "drum_chain_gain_r", "drumChainGainR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "drum_direct_gain_l", "drumDirectGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "drum_direct_gain_r", "drumDirectGainR", IPORT_INPUT_B)
        // Drum direct resonator wet/dry
        map("org.balch.orpheus.plugins.drum", "drum_direct_reso_dry_l", "drumDirectResoDryGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "drum_direct_reso_dry_r", "drumDirectResoDryGainR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "drum_direct_reso_wet_l", "drumDirectResoWetGainL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.drum", "drum_direct_reso_wet_r", "drumDirectResoWetGainR", IPORT_INPUT_B)
        // Drum direct limiter drive
        map("org.balch.orpheus.plugins.distortion", "drum_drive", "drumDirectLimiterL", IPORT_DRIVE)
        map("org.balch.orpheus.plugins.distortion", "drum_drive", "drumDirectLimiterR", IPORT_DRIVE)
    }
}
