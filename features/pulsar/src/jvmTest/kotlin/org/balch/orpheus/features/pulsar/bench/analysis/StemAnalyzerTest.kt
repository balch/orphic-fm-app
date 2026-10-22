package org.balch.orpheus.features.pulsar.bench.analysis

import org.balch.orpheus.core.audio.dsp.StemLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StemAnalyzerTest {

    @Test
    fun levelOfFullScaleSine() {
        val a = analyze(sine(1000.0), setOf(AnalysisKind.LEVEL))
        assertEquals(-3.0f, a.rmsDb!!, 0.1f)
        assertEquals(0.0f, a.peakDb!!, 0.1f)
        assertEquals(3.0f, a.crestDb!!, 0.1f)
        assertNull(a.bandsDb)
        assertNull(a.onsets)
        assertEquals("track3", a.stem)
    }

    @Test
    fun spectrumSinePeaksInItsBand() {
        val a = analyze(sine(1000.0), setOf(AnalysisKind.SPECTRUM))
        val hz = a.bandsHz!!
        val db = a.bandsDb!!
        assertEquals(31, hz.size)
        assertEquals(31, db.size)
        val k = hz.indexOf(1000f)
        assertEquals(k, db.indices.maxBy { db[it] })
        for (i in db.indices) {
            if (abs(i - k) >= 2) assertTrue(db[i] <= db[k] - 30f, "band ${hz[i]} at ${db[i]} vs ${db[k]}")
        }
    }

    @Test
    fun fullScaleSineReadsItsPowerInItsBand() {
        val a = analyze(sine(1000.0), setOf(AnalysisKind.SPECTRUM))
        assertEquals(-3.0f, a.bandsDb!![a.bandsHz!!.indexOf(1000f)], 0.2f)
    }

    @Test
    fun everyBandSeesPinkNoiseAndItIsRoughlyFlat() {
        val a = analyze(pink(Random(3)), setOf(AnalysisKind.SPECTRUM))
        val hz = a.bandsHz!!
        val db = a.bandsDb!!
        for (i in db.indices) assertTrue(db[i] > -120f, "band ${hz[i]} reads ${db[i]}")
        val mid = hz.indices.filter { hz[it] in 100f..10000f }.map { db[it] }
        val median = mid.sorted()[mid.size / 2]
        for (v in mid) assertTrue(abs(v - median) <= 4f, "band $v vs median $median in $mid")
    }

    @Test
    fun bandPowerSumsToTotal() {
        val analyzer = StemAnalyzer(SR, listOf(STEM), setOf(AnalysisKind.SPECTRUM))
        val l = noise(Random(13))
        feed(analyzer, mapOf(STEM to (l to l)), intArrayOf(700, 1300))
        val power = analyzer.averagedPower(0)
        val bands = analyzer.bandPowers(power).sum()
        // In-range bin power: each bin spans k*df +- df/2, clipped to the outer band edges.
        val df = SR.toDouble() / StemAnalyzer.SPEC_N
        val lo = 1000.0 * 2.0.pow(-17.0 / 3 - 1.0 / 6)
        val hi = 1000.0 * 2.0.pow(13.0 / 3 + 1.0 / 6)
        val inRange = power.indices.sumOf { k ->
            power[k] * max(0.0, min(hi, (k + 0.5) * df) - max(lo, (k - 0.5) * df)) / df
        }
        assertTrue(abs(bands - inRange) / inRange < 1e-6, "bands $bands vs in-range $inRange")
    }

    @Test
    fun lowEndPartition() {
        val sub = analyze(sine(30.0), setOf(AnalysisKind.LOWEND))
        // Sub starts at 20 Hz, and the Hann main lobe of 30 Hz at 11.7 Hz bins reaches below it.
        assertTrue(sub.subPct!! > 80f, "30 Hz sub ${sub.subPct}")
        val low = analyze(sine(80.0), setOf(AnalysisKind.LOWEND))
        assertTrue(low.lowPct!! > 90f, "80 Hz low ${low.lowPct}")
        assertTrue(low.subPct!! < 5f, "80 Hz sub ${low.subPct}")
        val hi = analyze(sine(1000.0), setOf(AnalysisKind.LOWEND))
        assertTrue(hi.subPct!! < 1f && hi.lowPct!! < 1f, "1 kHz ${hi.subPct} ${hi.lowPct}")
        for (a in listOf(sub, low, hi)) assertTrue(a.subPct!! + a.lowPct!! <= 100.05f)
    }

    @Test
    fun dcOffsetIsNotSub() {
        val a = analyze(FloatArray(N) { 0.5f }, setOf(AnalysisKind.LOWEND))
        assertTrue(a.subPct!! < 1f && a.lowPct!! < 1f, "dc sub ${a.subPct} low ${a.lowPct}")
    }

    @Test
    fun onsetsCountClicks() {
        val a = analyze(clicks(Random(5)), setOf(AnalysisKind.ONSETS))
        assertTrue(a.onsets!! in 7..9, "onsets ${a.onsets}")
        assertEquals(250f, a.medianIntervalMs!!, 15f)
        assertEquals(a.onsets!! / 2f, a.onsetsPerSecond!!, 0.05f)
    }

    @Test
    fun onsetsCountSoftClicks() {
        val a = analyze(clicks(Random(5), amp = 0.08), setOf(AnalysisKind.ONSETS))
        assertTrue(a.onsets!! in 7..9, "onsets ${a.onsets}")
        assertEquals(250f, a.medianIntervalMs!!, 15f)
    }

    @Test
    fun brownNoiseHasFewOnsets() {
        for (seed in 1..3) {
            val a = analyze(brown(Random(seed)), setOf(AnalysisKind.ONSETS))
            assertTrue(a.onsetsPerSecond!! < 0.5f, "seed $seed: ${a.onsets} onsets, ${a.onsetsPerSecond}/s")
        }
    }

    @Test
    fun tremoloPadHasNoOnsets() {
        val a = analyze(tremoloPad(), setOf(AnalysisKind.ONSETS))
        assertTrue(a.onsets!! <= 3, "onsets ${a.onsets}")
    }

    @Test
    fun stereoCorrelation() {
        val rnd = Random(9)
        val l = noise(rnd)
        val same = analyze(l, setOf(AnalysisKind.STEREO), r = l)
        assertEquals(1.0f, same.correlation!!, 0.01f)
        assertEquals(-120f, same.sideToMidDb!!)
        val flipped = analyze(l, setOf(AnalysisKind.STEREO), r = FloatArray(l.size) { -l[it] })
        assertEquals(-1.0f, flipped.correlation!!, 0.01f)
        val free = analyze(l, setOf(AnalysisKind.STEREO), r = noise(rnd))
        assertTrue(abs(free.correlation!!) < 0.1f, "independent ${free.correlation}")
        assertEquals(0f, free.sideToMidDb!!, 0.5f)
    }

    @Test
    fun silenceFloors() {
        val a = analyze(FloatArray(N), AnalysisKind.entries.toSet())
        assertEquals(-120f, a.rmsDb)
        assertEquals(-120f, a.peakDb)
        assertEquals(0, a.onsets)
        val floats = listOfNotNull(
            a.rmsDb, a.peakDb, a.crestDb, a.onsetsPerSecond, a.medianIntervalMs, a.subPct, a.lowPct,
            a.correlation, a.sideToMidDb,
        ) + a.bandsDb!! + a.envelopeDb!!
        assertFalse(floats.any { it.isNaN() || it.isInfinite() }, "$a")
        assertTrue(a.bandsDb!!.all { it == -120f })
        assertTrue(a.envelopeDb!!.all { it == -120f })
    }

    @Test
    fun envelopeWindows() {
        // Full scale for the first second, silent for the second.
        val s = sine(1000.0)
        for (i in N / 2 until N) s[i] = 0f
        val a = analyze(s, setOf(AnalysisKind.ENVELOPE))
        assertEquals(250, a.envelopeMs)
        val env = a.envelopeDb!!
        assertEquals(8, env.size)
        for (i in 0 until 4) assertEquals(-3.0f, env[i], 0.1f)
        for (i in 4 until 8) assertEquals(-120f, env[i])
    }

    @Test
    fun chunkingInvariant() {
        val rnd = Random(21)
        val p = pink(rnd)
        val c = clicks(rnd)
        val bass = sine(110.0)
        val t3l = FloatArray(N) { 0.5f * bass[it] + p[it] }
        val t3r = FloatArray(N) { 0.3f * p[it] + c[it] }
        val out = FloatArray(N) { c[it] + 0.2f * p[it] }
        val signals = mapOf(3 to (t3l to t3r), 12 to (out to out))
        val kinds = AnalysisKind.entries.toSet()

        val whole = StemAnalyzer(SR, listOf(3, 12), kinds).also { feed(it, signals, intArrayOf(N)) }.results()
        val odd = StemAnalyzer(SR, listOf(3, 12), kinds).also {
            feed(it, signals, intArrayOf(700, 1300, 1, 4096, 333))
        }.results()
        assertEquals(whole, odd)
        assertEquals(listOf("track3", "out"), whole.map { it.stem })

        // A partial last envelope window too: one frame short of 2 s.
        val short = signals.mapValues { (_, v) -> v.first.copyOf(N - 1) to v.second.copyOf(N - 1) }
        val a = StemAnalyzer(SR, listOf(3, 12), kinds).also { feed(it, short, intArrayOf(N - 1)) }.results()
        val b = StemAnalyzer(SR, listOf(3, 12), kinds).also { feed(it, short, intArrayOf(999, 17)) }.results()
        assertEquals(a, b)
        assertEquals(8, a[0].envelopeDb!!.size)
        // results() reads without consuming.
        assertEquals(a, StemAnalyzer(SR, listOf(3, 12), kinds).also {
            feed(it, short, intArrayOf(999, 17))
            it.results()
        }.results())
    }

    // ---- signals and feeding ----

    private fun analyze(l: FloatArray, kinds: Set<AnalysisKind>, r: FloatArray = l): StemAnalysis =
        StemAnalyzer(SR, listOf(STEM), kinds)
            .also { feed(it, mapOf(STEM to (l to r)), intArrayOf(700, 1300)) }
            .results()
            .single()

    /** Feeds [signals] per stem through one reused buffer in a repeating [chunks] pattern; other stems get loud noise. */
    private fun feed(analyzer: StemAnalyzer, signals: Map<Int, Pair<FloatArray, FloatArray>>, chunks: IntArray) {
        val total = signals.values.first().first.size
        val buf = FloatArray(chunks.max() * StemLayout.FLOATS_PER_FRAME)
        val junk = Random(99)
        var done = 0
        var c = 0
        while (done < total) {
            val n = minOf(chunks[c++ % chunks.size], total - done)
            for (i in 0 until n) {
                for (s in 0 until StemLayout.STEMS) {
                    val o = i * StemLayout.FLOATS_PER_FRAME + 2 * s
                    val sig = signals[s]
                    buf[o] = sig?.first?.get(done + i) ?: (junk.nextFloat() * 2f - 1f)
                    buf[o + 1] = sig?.second?.get(done + i) ?: (junk.nextFloat() * 2f - 1f)
                }
            }
            analyzer.write(buf, n)
            done += n
        }
    }

    private fun sine(hz: Double) = FloatArray(N) { sin(2 * PI * hz * it / SR).toFloat() }

    private fun noise(rnd: Random) = FloatArray(N) { rnd.nextFloat() * 2f - 1f }

    /** Paul Kellet's refined pink filter over seeded white noise. */
    private fun pink(rnd: Random): FloatArray {
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        return FloatArray(N) {
            val w = rnd.nextDouble(-1.0, 1.0)
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            (p * 0.11).toFloat()
        }
    }

    /** Seeded brown noise (a random walk), high-passed at 20 Hz, at -20 dB RMS. */
    private fun brown(rnd: Random, frames: Int = LONG): FloatArray {
        val a = exp(-2 * PI * 20.0 / SR)
        var walk = 0.0
        var prev = 0.0
        var y = 0.0
        val out = DoubleArray(frames) {
            walk += rnd.nextDouble(-1.0, 1.0)
            y = a * (y + walk - prev)
            prev = walk
            y
        }
        val gain = 0.1 / sqrt(out.sumOf { it * it } / frames)
        return FloatArray(frames) { (out[it] * gain).toFloat() }
    }

    /** A band-limited saw triad on A3 with a 4 Hz tremolo at 10% depth, from one 60-harmonic wavetable. */
    private fun tremoloPad(frames: Int = LONG): FloatArray {
        val size = 4096
        val table = DoubleArray(size) { i -> (1..60).sumOf { k -> sin(2 * PI * k * i / size) / k } * 2 / PI }
        val notes = doubleArrayOf(220.0, 220.0 * 2.0.pow(4 / 12.0), 220.0 * 2.0.pow(7 / 12.0))
        return FloatArray(frames) { i ->
            var v = 0.0
            for (hz in notes) {
                val ph = (hz * i / SR % 1.0) * size
                val j = ph.toInt()
                v += table[j] + (table[(j + 1) % size] - table[j]) * (ph - j)
            }
            (0.5 / 3 * v * (1 + 0.1 * sin(2 * PI * 4.0 * i / SR))).toFloat()
        }
    }

    /** Decaying 5 ms noise bursts every 250 ms, starting at 100 ms: eight in 2 s. */
    private fun clicks(rnd: Random, amp: Double = 0.8): FloatArray {
        val out = FloatArray(N)
        val len = SR * 5 / 1000
        var start = SR / 10
        while (start + len <= N) {
            for (i in 0 until len) out[start + i] = (amp * exp(-5.0 * i / len) * rnd.nextDouble(-1.0, 1.0)).toFloat()
            start += SR / 4
        }
        return out
    }

    private companion object {
        const val SR = 48000
        const val N = 2 * SR
        const val STEM = 3
        const val LONG = 15 * SR
    }
}
