package org.balch.orpheus.features.pulsar.bench

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.balch.orpheus.core.audio.dsp.BenchSummaryLayout as L

/** One channel as the bench shows it. [wave] is the last 2 s as (min, max) pairs of (L+R)/2, oldest first. */
data class ChannelMeter(
    val name: String,
    val relLu: Double,
    val peakDb: Double,
    val pan: Double,
    val subPct: Double,
    val lowPct: Double,
    val wave: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ChannelMeter && name == other.name && relLu == other.relLu && peakDb == other.peakDb &&
            pan == other.pan && subPct == other.subPct && lowPct == other.lowPct && wave.contentEquals(other.wave)

    override fun hashCode(): Int = name.hashCode() * 31 + wave.contentHashCode()
}

/** Loudness is the OUT channel; everything but [lufsIntegrated] covers the short-term window. */
data class MeterSnapshot(
    val lufsShort: Double,
    val lufsIntegrated: Double,
    val preClipPeakDb: Double,
    val pastKneePct: Double,
    val correlation: Double,
    val channels: List<ChannelMeter>,
    val section: Int,
)

/**
 * Turns the engine's per-block summaries into meters, windowed like the vibe_measure harness:
 * 100 ms sub-blocks, 400 ms blocks at 75% overlap, a 3 s short-term window and BS.1770 gating.
 * The K-weighting happens in C++; this only sums. Not thread safe: one caller at a time.
 */
class BenchMeters(sampleRate: Float) {
    private val subFrames = (sampleRate / 10f).roundToInt()
    private val binFrames = max(1, (sampleRate / 100f).roundToInt())

    private val pending = DoubleArray(SLOT)
    private val ring = DoubleArray(SHORT_SUBS * SLOT)
    private var ringHead = 0
    private var ringCount = 0

    private var blocks = DoubleArray(1024)
    private var blockCount = 0
    private var integrated = SILENT_DB
    private var integratedDirty = false

    private val waveMin = Array(L.CHANNELS) { FloatArray(WAVE_BINS) }
    private val waveMax = Array(L.CHANNELS) { FloatArray(WAVE_BINS) }
    private val binMin = FloatArray(L.CHANNELS) { Float.POSITIVE_INFINITY }
    private val binMax = FloatArray(L.CHANNELS) { Float.NEGATIVE_INFINITY }
    private var binPending = 0
    private var waveHead = 0

    private var section = -1

    fun reset() {
        pending.fill(0.0)
        ring.fill(0.0)
        ringHead = 0
        ringCount = 0
        blockCount = 0
        integrated = SILENT_DB
        integratedDirty = false
        for (c in 0 until L.CHANNELS) { waveMin[c].fill(0f); waveMax[c].fill(0f) }
        binMin.fill(Float.POSITIVE_INFINITY)
        binMax.fill(Float.NEGATIVE_INFINITY)
        binPending = 0
        waveHead = 0
    }

    /** Adds [count] flattened summaries from the front of [summaries]. Allocates only when the block log grows. */
    fun add(summaries: FloatArray, count: Int) {
        for (i in 0 until count) {
            val s = i * L.FLOATS
            val frames = summaries[s + L.FRAMES].toInt()
            if (frames <= 0) continue
            section = summaries[s + L.SECTION].toInt()
            pending[SLOT_FRAMES] += frames
            pending[SLOT_PRE_PEAK] = max(pending[SLOT_PRE_PEAK], summaries[s + L.PRE_LIMIT_PEAK].toDouble())
            pending[SLOT_OVER_KNEE] += summaries[s + L.OVER_KNEE]
            pending[SLOT_SAMPLES] += summaries[s + L.SAMPLES]
            for (c in 0 until L.CHANNELS) {
                val ch = s + L.channel(c)
                val p = SLOT_CH + c * CH_SUMS
                for (f in 0 until PEAK) pending[p + f] += summaries[ch + f]
                pending[p + PEAK] = max(pending[p + PEAK], summaries[ch + L.PEAK].toDouble())
                binMin[c] = minOf(binMin[c], summaries[ch + L.WAVE_MIN])
                binMax[c] = maxOf(binMax[c], summaries[ch + L.WAVE_MAX])
            }
            binPending += frames
            if (binPending >= binFrames) closeBin()
            if (pending[SLOT_FRAMES] >= subFrames) closeSub()
        }
    }

    fun snapshot(): MeterSnapshot {
        if (integratedDirty) {
            integrated = gatedLufs()
            integratedDirty = false
        }
        var frames = 0.0
        var prePeak = 0.0
        var overKnee = 0.0
        var samples = 0.0
        val sums = DoubleArray(L.CHANNELS * CH_SUMS)
        for (k in 0 until ringCount) {
            val o = slot(ringHead - 1 - k)
            frames += ring[o + SLOT_FRAMES]
            prePeak = max(prePeak, ring[o + SLOT_PRE_PEAK])
            overKnee += ring[o + SLOT_OVER_KNEE]
            samples += ring[o + SLOT_SAMPLES]
            for (j in 0 until L.CHANNELS * CH_SUMS) {
                val v = ring[o + SLOT_CH + j]
                sums[j] = if (j % CH_SUMS == PEAK) max(sums[j], v) else sums[j] + v
            }
        }
        val dryK = sums[L.DRY * CH_SUMS + K]
        val out = L.OUT * CH_SUMS
        val outL = sums[out + EL]
        val outR = sums[out + ER]
        return MeterSnapshot(
            lufsShort = if (frames > 0) lufs(sums[out + K] / frames) else SILENT_DB,
            lufsIntegrated = integrated,
            preClipPeakDb = dbAmp(prePeak),
            pastKneePct = if (samples > 0) 100.0 * overKnee / samples else 0.0,
            correlation = if (outL > 1e-30 && outR > 1e-30) sums[out + ELR] / sqrt(outL * outR) else 0.0,
            channels = List(L.CHANNELS) { c ->
                val o = c * CH_SUMS
                val e = sums[o + EL] + sums[o + ER]
                val k = sums[o + K]
                ChannelMeter(
                    name = CHANNEL_NAMES[c],
                    relLu = if (k > 1e-30 && dryK > 1e-30) 10.0 * log10(k / dryK) else SILENT_DB,
                    peakDb = dbAmp(sums[o + PEAK]),
                    pan = if (e > 1e-30) (sums[o + ER] - sums[o + EL]) / e else 0.0,
                    subPct = if (e > 1e-30) 100.0 * sums[o + SUB] / e else 0.0,
                    lowPct = if (e > 1e-30) 100.0 * sums[o + LOW] / e else 0.0,
                    wave = wave(c),
                )
            },
            section = section,
        )
    }

    private fun slot(i: Int) = ((i % SHORT_SUBS + SHORT_SUBS) % SHORT_SUBS) * SLOT

    private fun closeSub() {
        pending.copyInto(ring, slot(ringHead))
        pending.fill(0.0)
        ringHead = (ringHead + 1) % SHORT_SUBS
        if (ringCount < SHORT_SUBS) ringCount++
        if (ringCount < BLOCK_SUBS) return
        // A 400 ms block from the newest four sub-blocks, stepped every 100 ms.
        var k = 0.0
        var frames = 0.0
        for (j in 1..BLOCK_SUBS) {
            val o = slot(ringHead - j)
            k += ring[o + SLOT_CH + L.OUT * CH_SUMS + K]
            frames += ring[o + SLOT_FRAMES]
        }
        if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
        blocks[blockCount++] = k / frames
        integratedDirty = true
    }

    private fun closeBin() {
        for (c in 0 until L.CHANNELS) {
            waveMin[c][waveHead] = binMin[c].takeIf { it.isFinite() } ?: 0f
            waveMax[c][waveHead] = binMax[c].takeIf { it.isFinite() } ?: 0f
        }
        binMin.fill(Float.POSITIVE_INFINITY)
        binMax.fill(Float.NEGATIVE_INFINITY)
        binPending = 0
        waveHead = (waveHead + 1) % WAVE_BINS
    }

    private fun wave(c: Int): FloatArray {
        val w = FloatArray(WAVE_BINS * 2)
        for (j in 0 until WAVE_BINS) {
            val i = (waveHead + j) % WAVE_BINS
            w[2 * j] = waveMin[c][i]
            w[2 * j + 1] = waveMax[c][i]
        }
        return w
    }

    // mix_meter.h integrated_lufs: absolute gate, then relative to the mean of what passed.
    private fun gatedLufs(): Double {
        var sum = 0.0
        var n = 0
        for (i in 0 until blockCount) if (lufs(blocks[i]) > ABS_GATE) { sum += blocks[i]; n++ }
        if (n == 0) return SILENT_DB
        val gate = lufs(sum / n) + REL_GATE
        sum = 0.0
        n = 0
        for (i in 0 until blockCount) {
            val l = lufs(blocks[i])
            if (l > ABS_GATE && l > gate) { sum += blocks[i]; n++ }
        }
        return if (n > 0) lufs(sum / n) else SILENT_DB
    }

    companion object {
        /** mix_meter.h constants. */
        const val SILENT_DB = -120.0
        const val ABS_GATE = -70.0
        const val REL_GATE = -10.0
        const val K_OFFSET = -0.691

        val CHANNEL_NAMES = listOf(
            "KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXT", "FX", "STORM", "STREET", "SPEECH", "DRY", "OUT",
        )

        private const val SHORT_SUBS = 30
        private const val BLOCK_SUBS = 4
        private const val WAVE_BINS = 200

        // Channel sums kept per sub-block, in summary order up to PEAK.
        private const val K = L.K_ENERGY
        private const val EL = L.E_L
        private const val ER = L.E_R
        private const val ELR = L.E_LR
        private const val SUB = L.E_SUB
        private const val LOW = L.E_LOW
        private const val PEAK = L.PEAK
        private const val CH_SUMS = PEAK + 1

        private const val SLOT_FRAMES = 0
        private const val SLOT_PRE_PEAK = 1
        private const val SLOT_OVER_KNEE = 2
        private const val SLOT_SAMPLES = 3
        private const val SLOT_CH = 4
        private const val SLOT = SLOT_CH + L.CHANNELS * CH_SUMS

        fun lufs(meanPower: Double): Double = if (meanPower > 1e-30) K_OFFSET + 10.0 * log10(meanPower) else SILENT_DB
        fun dbAmp(a: Double): Double = if (abs(a) > 1e-15) 20.0 * log10(abs(a)) else SILENT_DB
    }
}
