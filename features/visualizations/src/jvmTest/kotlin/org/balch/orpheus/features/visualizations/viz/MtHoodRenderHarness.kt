package org.balch.orpheus.features.visualizations.viz

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.SynthEngine
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test

/**
 * Renders Mt Hood after ten seconds of a made-up beat (91 BPM, swelling every four bars, with kick,
 * snare and hats in the spectrum) and a slow LFO, so the lava has tongues running, pooling and
 * throws on the beat. Asserts nothing.
 * Output: features/visualizations/build/mt-hood-render/
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*MtHoodRenderHarness*' -PrenderHarness --rerun
 */
class MtHoodRenderHarness {
    private val beat = 60.0 / 91

    private fun energy(t: Double): Float {
        val swell = 0.45 + 0.55 * sin(PI * ((t / (16 * beat)) % 1)).pow(2)
        val kick = exp(-((t % beat) / 0.18))
        return (swell * (0.55 + 0.45 * kick)).coerceAtMost(1.0).toFloat() * 0.6f
    }

    private class Feed {
        val voices = MutableStateFlow(FloatArray(8))
        val lfo = MutableStateFlow(0f)
        val master = MutableStateFlow(0f)
        val spectrum = MutableStateFlow(FloatArray(40))
    }

    private fun engine(feed: Feed): SynthEngine =
        Proxy.newProxyInstance(SynthEngine::class.java.classLoader, arrayOf(SynthEngine::class.java)) { proxy, method, _ ->
            when (method.name) {
                "getVoiceLevelsFlow" -> feed.voices
                "getLfoOutputFlow" -> feed.lfo
                "getMasterLevelFlow" -> feed.master
                "getSpectrumFlow" -> feed.spectrum
                "setSpectrumEnabled" -> null
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                "toString" -> "MtHoodHarnessEngine"
                else -> error("unexpected ${method.name}")
            }
        } as SynthEngine

    private fun run(name: String, width: Int, height: Int, density: Float) {
        val feed = Feed()
        val viz = MtHoodViz(engine(feed))
        viz.onActivate()
        val scene = ImageComposeScene(width, height, Density(density)) { viz.Content(Modifier) }
        try {
            // Frames 0.1 s apart, the longest step the viz takes: raster renders the shader on the CPU,
            // and ten seconds at 60 a second took minutes.
            var nanos = 1_000_000_000L
            for (f in 0 until 100) {
                val t = f / 10.0
                feed.master.value = energy(t)
                feed.lfo.value = sin(t * 0.7).toFloat()
                // A kick in the bass bands on the beat, a snare in the mids on the off-beat, hats above.
                val kick = exp(-((t % beat) / 0.12)).toFloat()
                val snare = exp(-(((t + beat / 2) % beat) / 0.1)).toFloat()
                val hats = exp(-(((t + beat / 4) % (beat / 2)) / 0.05)).toFloat()
                feed.spectrum.value = FloatArray(40) { b ->
                    when {
                        b < 8 -> 0.15f + 0.75f * kick
                        b < 22 -> 0.12f + 0.6f * snare
                        else -> 0.08f + 0.5f * hats
                    }
                }
                // The playing pair moves every four beats, so the lava's voice colours change.
                feed.voices.value = FloatArray(8) { v -> if (v / 2 == ((t / (4 * beat)).toInt() % 4)) 0.8f else 0.1f }
                scene.render(nanos).close()
                nanos += 100_000_000L
            }
            val dir = File("build/mt-hood-render").apply { mkdirs() }
            File(dir, "$name.png").writeBytes(scene.render(nanos).encodeToData()!!.bytes)
        } finally {
            scene.close()
            viz.onDeactivate()
        }
    }

    @Test fun desktop() = run("desktop", 1200, 620, 1f)
    @Test fun phone() = run("phone", 780, 1400, 2f)
    // The Fold2's cover screen (1080 x 2520) at half size: tall and narrow, where height-sized lava ran thick.
    @Test fun foldCover() = run("fold_cover", 540, 1260, 1.3f)
}
