// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/engine/wavetable_engine.cc

package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsDsp
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId
import org.balch.orpheus.plugins.plaits.PlaitsWavetables
import org.balch.orpheus.plugins.plaits.dsp.Differentiator
import org.balch.orpheus.plugins.plaits.dsp.WavetableOscillator
import kotlin.math.max
import kotlin.math.min

private const val NUM_BANKS = 4
private const val NUM_WAVES_PER_BANK = 64
private const val NUM_WAVES = 192
private const val TABLE_SIZE = 128
private const val TABLE_SIZE_F = 128.0f
private const val A0 = (440.0f / 8.0f) / 44100.0f

/**
 * 8x8x3 wave terrain synthesis engine.
 * Trilinear interpolation through 8 corner waves per sample for smooth morphing.
 *
 * Parameter mapping:
 * - note → pitch
 * - timbre → X axis (waveform selection within bank row)
 * - morph → Y axis (waveform selection across rows)
 * - harmonics → Z axis (bank selection, with quantization at high values)
 *
 * Aux output: bit-crushed version of main output.
 */
class WavetableEngine : PlaitsEngine {
    override val id = PlaitsEngineId.WAVETABLE
    override val displayName = id.displayName
    override val alreadyEnveloped = false
    override val outGain = 0.5f

    private var phase = 0.0f

    private var xPreLp = 0.0f
    private var yPreLp = 0.0f
    private var zPreLp = 0.0f

    private var xLp = 0.0f
    private var yLp = 0.0f
    private var zLp = 0.0f

    private var previousX = 0.0f
    private var previousY = 0.0f
    private var previousZ = 0.0f
    private var previousF0 = A0

    private val diffOut = Differentiator()

    // wave_map_: maps (bank * 64 + row * 8 + column) -> offset into WAV_INTEGRATED_WAVES
    private val waveMap = IntArray(NUM_BANKS * NUM_WAVES_PER_BANK)

    override fun init() {
        phase = 0.0f
        xLp = 0.0f; yLp = 0.0f; zLp = 0.0f
        xPreLp = 0.0f; yPreLp = 0.0f; zPreLp = 0.0f
        previousX = 0.0f; previousY = 0.0f; previousZ = 0.0f
        previousF0 = A0
        diffOut.init()
        loadWaveMap()
    }

    override fun reset() {}

    private fun loadWaveMap() {
        for (bank in 0 until NUM_BANKS) {
            for (wave in 0 until NUM_WAVES_PER_BANK) {
                val i = bank * NUM_WAVES_PER_BANK + wave
                var w = i
                if (bank == NUM_BANKS - 1) {
                    // No user data: scramble using modular arithmetic
                    w = (w * 101) % NUM_WAVES
                }
                if (w >= NUM_WAVES) w = NUM_WAVES - 1
                waveMap[i] = w * (TABLE_SIZE + 4) // 132 samples per wave
            }
        }
    }

    private fun readWave(x: Int, y: Int, z: Int, phaseIntegral: Int, phaseFractional: Float): Float {
        val idx = x + y * 8 + z * NUM_WAVES_PER_BANK
        return WavetableOscillator.interpolateWaveHermite(
            waveMap[idx], phaseIntegral, phaseFractional
        )
    }

    override fun render(params: EngineParameters, out: FloatArray, aux: FloatArray?, size: Int): Boolean {
        val f0 = PlaitsDsp.noteToFrequency(params.note)

        // ONE_POLE pre-smoothing
        xPreLp += 0.2f * (params.timbre * 6.9999f - xPreLp)
        yPreLp += 0.2f * (params.morph * 6.9999f - yPreLp)
        zPreLp += 0.05f * (params.harmonics * 6.9999f - zPreLp)

        val x = xPreLp
        val y = yPreLp
        val z = zPreLp

        val quantization = min(max(z - 3.0f, 0.0f), 1.0f)
        val lpCoefficient = min(max(2.0f * f0 * (4.0f - 3.0f * quantization), 0.01f), 0.1f)

        var xI = x.toInt(); var xF = x - xI
        var yI = y.toInt(); var yF = y - yI
        var zI = z.toInt(); var zF = z - zI

        xF += quantization * (clampFrac(xF, 16.0f) - xF)
        yF += quantization * (clampFrac(yF, 16.0f) - yF)
        zF += quantization * (clampFrac(zF, 16.0f) - zF)

        val xMod = PlaitsDsp.ParameterInterpolator(previousX, xI.toFloat() + xF, size)
        val yMod = PlaitsDsp.ParameterInterpolator(previousY, yI.toFloat() + yF, size)
        val zMod = PlaitsDsp.ParameterInterpolator(previousZ, zI.toFloat() + zF, size)
        val f0Mod = PlaitsDsp.ParameterInterpolator(previousF0, f0, size)

        previousX = xI.toFloat() + xF
        previousY = yI.toFloat() + yF
        previousZ = zI.toFloat() + zF
        previousF0 = f0

        for (i in 0 until size) {
            val sampleF0 = f0Mod.next()

            val gain = (1.0f / (sampleF0 * 131072.0f)) * (0.95f - sampleF0)
            val cutoff = min(TABLE_SIZE_F * sampleF0, 1.0f)

            // ONE_POLE smooth per-sample
            xLp += lpCoefficient * (xMod.next() - xLp)
            yLp += lpCoefficient * (yMod.next() - yLp)
            zLp += lpCoefficient * (zMod.next() - zLp)

            val sx = xLp; val sy = yLp; val sz = zLp
            val sxI = sx.toInt(); val sxF = sx - sxI
            val syI = sy.toInt(); val syF = sy - syI
            val szI = sz.toInt(); val szF = sz - szI

            phase += sampleF0
            if (phase >= 1.0f) phase -= 1.0f

            val p = phase * TABLE_SIZE_F
            val pI = p.toInt()
            val pF = p - pI

            // Z axis mirror: fold 4-7 back to 3-0
            val z0 = if (szI >= 4) 7 - szI else szI
            val z1 = if (szI + 1 >= 4) 7 - (szI + 1) else szI + 1

            // Trilinear interpolation
            val x0y0z0 = readWave(sxI, syI, z0, pI, pF)
            val x1y0z0 = readWave(sxI + 1, syI, z0, pI, pF)
            val xy0z0 = x0y0z0 + (x1y0z0 - x0y0z0) * sxF

            val x0y1z0 = readWave(sxI, syI + 1, z0, pI, pF)
            val x1y1z0 = readWave(sxI + 1, syI + 1, z0, pI, pF)
            val xy1z0 = x0y1z0 + (x1y1z0 - x0y1z0) * sxF

            val xyz0 = xy0z0 + (xy1z0 - xy0z0) * syF

            val x0y0z1 = readWave(sxI, syI, z1, pI, pF)
            val x1y0z1 = readWave(sxI + 1, syI, z1, pI, pF)
            val xy0z1 = x0y0z1 + (x1y0z1 - x0y0z1) * sxF

            val x0y1z1 = readWave(sxI, syI + 1, z1, pI, pF)
            val x1y1z1 = readWave(sxI + 1, syI + 1, z1, pI, pF)
            val xy1z1 = x0y1z1 + (x1y1z1 - x0y1z1) * sxF

            val xyz1 = xy0z1 + (xy1z1 - xy0z1) * syF

            var mix = xyz0 + (xyz1 - xyz0) * szF
            mix = diffOut.process(cutoff, mix) * gain
            out[i] = mix
            // Bit-crushed aux output
            aux?.let { it[i] = (mix * 32.0f).toInt().toFloat() / 32.0f }
        }

        return false
    }

    companion object {
        /** Soft quantization helper: push x toward nearest integer boundary. */
        private fun clampFrac(x: Float, amount: Float): Float {
            var v = x - 0.5f
            v *= amount
            v = v.coerceIn(-0.5f, 0.5f)
            return v + 0.5f
        }
    }
}
