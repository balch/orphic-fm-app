package org.balch.orpheus.features.visualizations.viz.spectrogram

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Renders the Orphogram from ten seconds of synthetic audio (two sine sweeps, a decaying
 * inharmonic bell struck three times, a low bass note) fed at 60 frames a second. Asserts nothing.
 * Output: features/visualizations/build/spectrogram-render/
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*SpectrogramRenderHarness*' -PrenderHarness --rerun
 */
class OrphogramRenderHarness {
    private val sampleRate = 48_000
    private val seconds = 10.0
    private val fps = 60

    private val bellPartials = listOf(
        Triple(1.0, 0.20, 0.9), Triple(1.48, 0.16, 0.6), Triple(2.24, 0.08, 0.35),
        Triple(3.03, 0.05, 0.25), Triple(4.11, 0.03, 0.15), Triple(5.6, 0.02, 0.1),
    )

    /** A 20 ms ramp in and out of [start]..[end], so notes don't click. */
    private fun fade(t: Double, start: Double, end: Double) = minOf(1.0, (t - start) / 0.02, (end - t) / 0.02).coerceAtLeast(0.0)

    private val audio: FloatArray = run {
        val n = (seconds * sampleRate).toInt()
        val rnd = Random(7)
        var phase1 = 0.0
        var phase2 = 0.0
        FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            var s = 0.0
            // Rising sweep, 80 Hz to 3 kHz over 0.5..4.5 s.
            if (t in 0.5..4.5) {
                phase1 += 2 * PI * 80.0 * (3000.0 / 80.0).pow((t - 0.5) / 4.0) / sampleRate
                s += 0.25 * fade(t, 0.5, 4.5) * sin(phase1)
            }
            // Falling sweep with two harmonics, 6 kHz to 200 Hz over 3..7 s.
            if (t in 3.0..7.0) {
                phase2 += 2 * PI * 6000.0 * (200.0 / 6000.0).pow((t - 3.0) / 4.0) / sampleRate
                s += fade(t, 3.0, 7.0) * (0.15 * sin(phase2) + 0.06 * sin(2 * phase2) + 0.03 * sin(3 * phase2))
            }
            // An inharmonic bell, struck at 1, 5.5 and 8 s.
            for (strike in doubleArrayOf(1.0, 5.5, 8.0)) {
                val dt = t - strike
                if (dt < 0) continue
                for ((ratio, amp, tau) in bellPartials) {
                    s += amp * (dt / 0.004).coerceAtMost(1.0) * exp(-dt / tau) * sin(2 * PI * 540.0 * ratio * dt)
                }
            }
            // A bass note at 65 Hz over 7..9.5 s.
            if (t in 7.0..9.5) s += 0.3 * fade(t, 7.0, 9.5) * sin(2 * PI * 65.0 * t) * exp(-(t - 7.0) / 2.0)
            (s + 1e-4 * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
    }

    @Test fun fft8192Interpolated() = render("fft8192_interpolated") { end -> rowsFromBins(end, 8192) }

    @Test fun fft2048PeakBands() = render("fft2048_peak_bands") { end -> rowsFromPeakBands(end) }

    private fun render(name: String, analyze: (Int) -> FloatArray) {
        val frames = (seconds * fps).toInt()
        val columns = Array(frames) { f -> analyze(((f + 1) * sampleRate / fps).coerceAtMost(audio.size)) }
        var current: FloatArray? = null
        val scene = ImageComposeScene(1200, 520, Density(1f)) {
            OrphogramCanvas(Modifier.fillMaxSize(), { seconds.toFloat() }, { 70f }) { current }
        }
        try {
            var t = 1_000_000_000L
            for (f in 0 until frames) {
                current = columns[f]
                scene.render(t).close()
                t += 1_000_000_000L / fps
            }
            val dir = File("build/spectrogram-render").apply { mkdirs() }
            File(dir, "$name.png").writeBytes(scene.render(t).encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    /** dB per bin of a Hann-windowed FFT of the [size] samples ending at [end]. */
    private fun binDb(end: Int, size: Int): DoubleArray {
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (i in 0 until size) {
            val idx = end - size + i
            val s = if (idx >= 0) audio[idx].toDouble() else 0.0
            re[i] = s * 0.5 * (1 - cos(2 * PI * i / (size - 1)))
        }
        fft(re, im)
        return DoubleArray(size / 2) { k -> 20 * log10(sqrt(re[k] * re[k] + im[k] * im[k]) * 2 / size + 1e-9) }
    }

    /** dB interpolated between bins at each row's frequency, as the numpy renderer does. */
    private fun rowsFromBins(end: Int, size: Int): FloatArray {
        val db = binDb(end, size)
        val binHz = sampleRate.toDouble() / size
        return FloatArray(OrphogramScale.ROWS) { r ->
            val k = OrphogramScale.rowHz(r) / binHz
            val k0 = floor(k).toInt()
            (db[k0] + (db[k0 + 1] - db[k0]) * (k - k0)).toFloat()
        }
    }

    /** The engine's current Spectrograph analysis at 256 bands (2048 FFT, peak per band, 30 Hz..16 kHz), onto the rows. */
    private fun rowsFromPeakBands(end: Int): FloatArray {
        val size = 2048
        val bands = 256
        val db = binDb(end, size)
        val binHz = sampleRate.toDouble() / size
        val ratio = 16000.0 / 30.0
        val bandDb = DoubleArray(bands) { b ->
            val kLo = maxOf(1, floor(30.0 * ratio.pow(b.toDouble() / bands) / binHz).toInt())
            val kHi = maxOf(kLo, minOf(size / 2 - 1, kotlin.math.ceil(30.0 * ratio.pow((b + 1.0) / bands) / binHz).toInt()))
            (kLo..kHi).maxOf { db[it] }
        }
        return FloatArray(OrphogramScale.ROWS) { r ->
            val pos = bands * ln(OrphogramScale.rowHz(r) / 30.0) / ln(ratio) - 0.5
            val b0 = floor(pos).toInt().coerceIn(0, bands - 2)
            val t = (pos - b0).coerceIn(0.0, 1.0)
            (bandDb[b0] + (bandDb[b0 + 1] - bandDb[b0]) * t).toFloat()
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k)
                    val wi = sin(ang * k)
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
    }
}
