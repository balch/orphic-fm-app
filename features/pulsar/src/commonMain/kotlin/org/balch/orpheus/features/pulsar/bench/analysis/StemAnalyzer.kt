package org.balch.orpheus.features.pulsar.bench.analysis

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.dsp.StemLayout
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.StemSink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

enum class AnalysisKind { LEVEL, SPECTRUM, ENVELOPE, ONSETS, LOWEND, STEREO }

/** One stem's numbers; a field is null when its kind was not asked for. Values round to 0.1, dB floors at -120. */
@Serializable
data class StemAnalysis(
    val stem: String,
    val rmsDb: Float? = null,
    val peakDb: Float? = null,
    val crestDb: Float? = null,
    /** 31 third-octave bands, 20 Hz to 20 kHz; each band's power in dBFS (a full-scale sine reads -3 in its band). */
    val bandsHz: List<Float>? = null,
    val bandsDb: List<Float>? = null,
    val envelopeMs: Int? = null,
    val envelopeDb: List<Float>? = null,
    val onsets: Int? = null,
    val onsetsPerSecond: Float? = null,
    /** Null with fewer than two onsets. */
    val medianIntervalMs: Float? = null,
    /** Shares of the total power from 20 to 40 Hz and from 40 to 120 Hz; DC and below 20 Hz count in neither. */
    val subPct: Float? = null,
    val lowPct: Float? = null,
    val correlation: Float? = null,
    val sideToMidDb: Float? = null,
)

/**
 * Streams stems from a [StemSink] into running sums and Welch spectra, in constant memory per stem.
 * Reads only [stems] (indices into [StemLayout.NAMES]); SPECTRUM, LOWEND and ONSETS use the mono mid.
 * One writer at a time; [results] reads the state without consuming it.
 */
class StemAnalyzer(
    private val sampleRate: Int,
    stems: List<Int>,
    private val kinds: Set<AnalysisKind>,
) : StemSink {
    private val wantSpectrum = AnalysisKind.SPECTRUM in kinds || AnalysisKind.LOWEND in kinds
    private val wantOnsets = AnalysisKind.ONSETS in kinds

    private val specFft = if (wantSpectrum) RealFft(SPEC_N) else null
    private val onsetFft = if (wantOnsets) RealFft(ONSET_N) else null
    private val specWindow = hann(SPEC_N)
    private val onsetWindow = hann(ONSET_N)
    private val input = DoubleArray(SPEC_N)
    private val re = DoubleArray(SPEC_N / 2 + 1)
    private val im = DoubleArray(SPEC_N / 2 + 1)
    private val sorted = DoubleArray(FLUX_HISTORY)

    // One-sided power scale, so the bins of a frame sum to its mean square.
    private val specScale = 1.0 / (SPEC_N * specWindow.sumOf { it * it })
    private val onsetMagScale = 2.0 / onsetWindow.sum()
    private val binHz = sampleRate.toDouble() / SPEC_N
    // Bin k spans (k +- 0.5) * binHz; a band takes each bin's power by the share of that span inside it.
    // At 11.7 Hz bins the 20-40 Hz bands are coarse, but none reads empty.
    private val bandFirstBin = IntArray(BAND_EXPONENTS.size)
    private val bandWeights: Array<DoubleArray> = Array(BAND_EXPONENTS.size) { b ->
        val fc = 1000.0 * 2.0.pow(BAND_EXPONENTS[b] / 3.0)
        val lo = fc * 2.0.pow(-1.0 / 6)
        val hi = fc * 2.0.pow(1.0 / 6)
        val first = floor(lo / binHz + 0.5).toInt()
        val last = min(SPEC_N / 2, floor(hi / binHz + 0.5).toInt())
        bandFirstBin[b] = first
        DoubleArray(max(0, last - first + 1)) { overlap(first + it, lo, hi) }
    }
    private val envelopeFrames = max(1, sampleRate * ENVELOPE_MS / 1000)
    private val refractory = sampleRate * REFRACTORY_MS / 1000

    private val state = stems.map {
        require(it in 0 until StemLayout.STEMS) { "no stem $it" }
        StemState(it)
    }

    override fun write(frames: FloatArray, count: Int) {
        for (s in state) {
            var o = 2 * s.index
            for (i in 0 until count) {
                s.add(frames[o].toDouble(), frames[o + 1].toDouble())
                o += StemLayout.FLOATS_PER_FRAME
            }
        }
    }

    /** Call only after the last [write]: a short capture runs the FFT through the buffers [write] uses. */
    fun results(): List<StemAnalysis> = state.map { it.result() }

    private inner class StemState(val index: Int) {
        var frames = 0L
        var sumL = 0.0
        var sumR = 0.0
        var sumLL = 0.0
        var sumRR = 0.0
        var sumLR = 0.0
        var sumMid2 = 0.0
        var sumSide2 = 0.0
        var peak = 0.0

        val envelope = ArrayList<Double>()
        var envSum = 0.0
        var envCount = 0

        val specBuf = if (wantSpectrum) DoubleArray(SPEC_N) else EMPTY
        var specFill = 0
        val specPower = if (wantSpectrum) DoubleArray(SPEC_N / 2 + 1) else EMPTY
        var specFrames = 0

        val onsetBuf = if (wantOnsets) DoubleArray(ONSET_N) else EMPTY
        var onsetFill = 0
        var prevMag = if (wantOnsets) DoubleArray(ONSET_N / 2 + 1) else EMPTY
        var mag = if (wantOnsets) DoubleArray(ONSET_N / 2 + 1) else EMPTY
        var onsetFrame = 0L
        val flux = DoubleArray(FLUX_HISTORY)
        var fluxCount = 0
        val onsetAt = ArrayList<Long>()

        fun add(l: Double, r: Double) {
            frames++
            sumL += l
            sumR += r
            sumLL += l * l
            sumRR += r * r
            sumLR += l * r
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5
            sumMid2 += mid * mid
            sumSide2 += side * side
            peak = max(peak, max(abs(l), abs(r)))

            envSum += (l * l + r * r) * 0.5
            if (++envCount == envelopeFrames) {
                envelope.add(envSum / envCount)
                envSum = 0.0
                envCount = 0
            }
            if (wantSpectrum) {
                specBuf[specFill++] = mid
                if (specFill == SPEC_N) {
                    spectrumFrame(specBuf, specPower)
                    specFrames++
                    specBuf.copyInto(specBuf, 0, SPEC_HOP, SPEC_N)
                    specFill = SPEC_N - SPEC_HOP
                }
            }
            if (wantOnsets) {
                onsetBuf[onsetFill++] = mid
                if (onsetFill == ONSET_N) {
                    onsetFrame()
                    onsetBuf.copyInto(onsetBuf, 0, ONSET_HOP, ONSET_N)
                    onsetFill = ONSET_N - ONSET_HOP
                }
            }
        }

        /** Log-magnitude spectral flux against the previous frame; the first frame is only a reference. */
        private fun onsetFrame() {
            for (i in 0 until ONSET_N) input[i] = onsetBuf[i] * onsetWindow[i]
            onsetFft!!.forward(input, re, im)
            // Log compression evens out the bins, so a few loud low bins cannot swing the flux.
            for (k in 0..ONSET_N / 2) mag[k] = ln(1.0 + LOG_GAIN * sqrt(re[k] * re[k] + im[k] * im[k]) * onsetMagScale)
            if (onsetFrame > 0) {
                var f = 0.0
                for (k in 0..ONSET_N / 2) f += max(0.0, mag[k] - prevMag[k])
                f /= ONSET_N / 2 + 1
                val n = min(fluxCount, FLUX_HISTORY)
                val threshold = median(flux, n, sorted) * FLUX_RATIO + FLUX_FLOOR
                val at = onsetFrame * ONSET_HOP
                if (f > threshold && (onsetAt.isEmpty() || at - onsetAt.last() >= refractory)) onsetAt.add(at)
                flux[fluxCount++ % FLUX_HISTORY] = f
            }
            val t = prevMag
            prevMag = mag
            mag = t
            onsetFrame++
        }

        fun result(): StemAnalysis {
            val n = frames.toDouble()
            val meanSq = if (frames > 0) (sumLL + sumRR) / (2 * n) else 0.0
            val rmsDb = powerDb(meanSq)
            val peakDb = ampDb(peak)
            val power = if (wantSpectrum) averagedSpectrum() else EMPTY
            val total = power.sum()

            var a = StemAnalysis(stem = StemLayout.NAMES[index])
            if (AnalysisKind.LEVEL in kinds) {
                a = a.copy(rmsDb = r1(rmsDb), peakDb = r1(peakDb), crestDb = r1(peakDb - rmsDb))
            }
            if (AnalysisKind.SPECTRUM in kinds) {
                a = a.copy(
                    bandsHz = BANDS_HZ,
                    bandsDb = bandPowers(power).map { r1(powerDb(it)) },
                )
            }
            if (AnalysisKind.ENVELOPE in kinds) {
                val windows = if (envCount > 0) envelope + envSum / envCount else envelope
                a = a.copy(envelopeMs = ENVELOPE_MS, envelopeDb = windows.map { r1(powerDb(it)) })
            }
            if (AnalysisKind.ONSETS in kinds) {
                val intervals = DoubleArray(max(0, onsetAt.size - 1)) { (onsetAt[it + 1] - onsetAt[it]).toDouble() }
                a = a.copy(
                    onsets = onsetAt.size,
                    onsetsPerSecond = r1(if (frames > 0) onsetAt.size * sampleRate / n else 0.0),
                    medianIntervalMs = if (intervals.size > 0) {
                        r1(median(intervals, intervals.size, intervals) * 1000.0 / sampleRate)
                    } else {
                        null
                    },
                )
            }
            if (AnalysisKind.LOWEND in kinds) {
                var sub = 0.0
                var low = 0.0
                for (k in power.indices) {
                    sub += power[k] * overlap(k, SUB_LO_HZ, SUB_HZ)
                    low += power[k] * overlap(k, SUB_HZ, LOW_HZ)
                }
                a = a.copy(
                    subPct = r1(if (total > SILENT_POWER) 100.0 * sub / total else 0.0),
                    lowPct = r1(if (total > SILENT_POWER) 100.0 * low / total else 0.0),
                )
            }
            if (AnalysisKind.STEREO in kinds) {
                a = a.copy(correlation = r1(correlation()), sideToMidDb = r1(sideToMid()))
            }
            return a
        }

        /** The Welch average; a capture shorter than one frame is zero-padded into a single frame. */
        fun averagedSpectrum(): DoubleArray {
            val out = DoubleArray(SPEC_N / 2 + 1)
            if (specFrames > 0) {
                for (k in out.indices) out[k] = specPower[k] / specFrames
            } else if (specFill > 0) {
                spectrumFrame(specBuf.copyOf().also { it.fill(0.0, specFill) }, out)
            }
            return out
        }

        private fun correlation(): Double {
            if (frames == 0L) return 0.0
            val n = frames.toDouble()
            val varL = sumLL / n - (sumL / n) * (sumL / n)
            val varR = sumRR / n - (sumR / n) * (sumR / n)
            if (varL <= SILENT_POWER || varR <= SILENT_POWER) return 0.0
            val cov = sumLR / n - (sumL / n) * (sumR / n)
            return (cov / sqrt(varL * varR)).coerceIn(-1.0, 1.0)
        }

        private fun sideToMid(): Double = when {
            sumSide2 <= SILENT_POWER -> SILENT_DB
            sumMid2 <= SILENT_POWER -> -SILENT_DB
            else -> powerDb(sumSide2 / sumMid2).coerceIn(SILENT_DB, -SILENT_DB)
        }
    }

    /** The averaged one-sided power spectrum of the [i]th requested stem; for tests. */
    internal fun averagedPower(i: Int): DoubleArray = state[i].averagedSpectrum()

    /** Linear power per third-octave band from a one-sided power spectrum. */
    internal fun bandPowers(power: DoubleArray): DoubleArray = DoubleArray(bandWeights.size) { b ->
        val w = bandWeights[b]
        var sum = 0.0
        for (j in w.indices) sum += power[bandFirstBin[b] + j] * w[j]
        sum
    }

    /** The share of bin [k]'s span, (k +- 0.5) * binHz, inside [lo, hi). */
    private fun overlap(k: Int, lo: Double, hi: Double): Double =
        max(0.0, min(hi, (k + 0.5) * binHz) - max(lo, (k - 0.5) * binHz)) / binHz

    /** Adds the frame's one-sided, Hann-windowed power into [acc]. */
    private fun spectrumFrame(buf: DoubleArray, acc: DoubleArray) {
        for (i in 0 until SPEC_N) input[i] = buf[i] * specWindow[i]
        specFft!!.forward(input, re, im)
        val last = SPEC_N / 2
        for (k in 0..last) {
            val p = (re[k] * re[k] + im[k] * im[k]) * specScale
            acc[k] += if (k == 0 || k == last) p else 2 * p
        }
    }

    companion object {
        const val SPEC_N = 4096
        const val SPEC_HOP = 2048
        const val ONSET_N = 1024
        const val ONSET_HOP = 512
        const val ENVELOPE_MS = 250
        const val REFRACTORY_MS = 50
        private const val FLUX_HISTORY = 16
        private const val FLUX_RATIO = 1.5
        // Magnitudes scale so a full-scale sine peaks at 1, then ln(1 + 100 m); flux is the mean rise per bin.
        private const val LOG_GAIN = 100.0
        private const val FLUX_FLOOR = 0.02
        private const val SUB_LO_HZ = 20.0
        private const val SUB_HZ = 40.0
        private const val LOW_HZ = 120.0
        private const val SILENT_DB = BenchMeters.SILENT_DB
        private const val SILENT_POWER = 1e-30
        private val EMPTY = DoubleArray(0)

        // Third-octave exponents around 1 kHz: 20 Hz is -17, 20 kHz is 13.
        private val BAND_EXPONENTS = (-17..13).toList()
        val BANDS_HZ: List<Float> = listOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
            800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
        )

        /** Median of the first [n] values, sorted through [scratch] (which may be [values]); 0 when empty. */
        private fun median(values: DoubleArray, n: Int, scratch: DoubleArray): Double {
            if (n == 0) return 0.0
            values.copyInto(scratch, 0, 0, n)
            scratch.sort(0, n)
            return if (n % 2 == 1) scratch[n / 2] else (scratch[n / 2 - 1] + scratch[n / 2]) * 0.5
        }

        private fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

        private fun ampDb(a: Double) = max(SILENT_DB, BenchMeters.dbAmp(a))

        private fun powerDb(p: Double) = if (p > SILENT_POWER) max(SILENT_DB, BenchMeters.dbAmp(sqrt(p))) else SILENT_DB

        /** Rounds to 0.1, with no negative zero. */
        private fun r1(x: Double): Float = (round(x * 10) / 10 + 0.0).toFloat()
    }
}
