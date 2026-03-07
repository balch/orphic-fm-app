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
 *   master volume -> drive (limiter) -> clouds -> rings -> warps ->
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

    for (v in 0 until 12) {
        val p = plaits("v${v}_p") { moduleIndex = v.toFloat() }
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

    // Drive / limiter (stereo)
    val driveL = limiter("driveL") { driveAmount = 1.0f }
    val driveR = limiter("driveR") { driveAmount = 1.0f }
    mvL.out to driveL.input
    mvR.out to driveR.input

    // Clouds (stereo in/out) - bypassed by default via engine atomics
    val grains = clouds("grains")
    driveL.out to grains.inputA
    driveR.out to grains.inputB

    // Rings (mono in -> stereo out) - bypassed by default
    // Mix L+R to mono for rings input
    val ringsMix = add("ringsMix")
    val ringsHalf = multiply("ringsHalf") { inputB = 0.5f }
    val reso = rings("resonator")
    grains.out to ringsMix.inputA
    grains.outRight to ringsMix.inputB
    ringsMix.out to ringsHalf.inputA
    ringsHalf.out to reso.input

    // Warps (stereo in/out) - bypassed by default
    val warp = warps("warps")
    reso.out to warp.inputA
    reso.outRight to warp.inputB

    // Master clip + output
    val clipL = hardClip("clipL")
    val clipR = hardClip("clipR")
    val master = masterOut("master")
    warp.out to clipL.input
    warp.outRight to clipR.input
    clipL.out to master.inputA
    clipR.out to master.inputB

    // Port map for nativeSetPort routing
    portMap {
        map("org.balch.orpheus.plugins.stereo", "master_vol", "mvL", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.stereo", "master_vol", "mvR", IPORT_INPUT_B)
        map("org.balch.orpheus.plugins.distortion", "drive", "driveL", IPORT_DRIVE)
        map("org.balch.orpheus.plugins.distortion", "drive", "driveR", IPORT_DRIVE)
    }
}
