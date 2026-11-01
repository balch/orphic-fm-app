package org.balch.orpheus.features.visualizations.viz

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.audio.SynthEngine
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test

/**
 * Renders the Orphograph from a synthetic mix fed as 40 band magnitudes at 60 frames a second: a
 * kick every half second, a bass line, a pad in the mids and hats up top. Asserts nothing.
 * Output: features/visualizations/build/orphograph-render/
 *
 * ./gradlew :features:visualizations:jvmTest --tests '*OrphographRenderHarness*' -PrenderHarness --rerun
 */
class OrphographRenderHarness {
    private val bands = 40

    private fun gauss(b: Int, centre: Double, width: Double) = exp(-(b - centre).pow(2) / (2 * width * width))

    /** Linear magnitudes per band at frame [f]: dB-shaped so the bars sit where music does. */
    private fun mix(f: Int): FloatArray {
        val t = f / 60.0
        val sinceKick = (t % 0.5)
        val sinceHat = ((t + 0.25) % 0.25)
        return FloatArray(bands) { b ->
            var db = -66.0
            db = maxOf(db, -6 - 30 * sinceKick / 0.5 - 4 * (b - 2.0).pow(2) / 4)
            db = maxOf(db, -14 + 6 * sin(2 * PI * t / 2) - 3 * (b - 8.0).pow(2) / 8)
            db = maxOf(db, -22 + 8 * gauss(b, 19.0 + 3 * sin(t), 3.0) - 0.4 * (b - 19.0).pow(2))
            db = maxOf(db, -24 - 40 * sinceHat - 0.3 * (b - 33.0).pow(2))
            10.0.pow(db / 20).toFloat()
        }
    }

    private fun engine(spectrum: MutableStateFlow<FloatArray>): SynthEngine =
        Proxy.newProxyInstance(SynthEngine::class.java.classLoader, arrayOf(SynthEngine::class.java)) { proxy, method, _ ->
            when (method.name) {
                "getSpectrumFlow" -> spectrum
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                "toString" -> "OrphographHarnessEngine"
                else -> error("unexpected ${method.name}")
            }
        } as SynthEngine

    private class Started : LifecycleOwner {
        override val lifecycle: Lifecycle =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    // collectAsStateWithLifecycle registers from the main dispatcher, which this module's tests lack.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun run(name: String, width: Int, height: Int, density: Float, frames: Int) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val spectrum = MutableStateFlow(FloatArray(0))
        val viz = OrphographViz(engine(spectrum))
        val scene = ImageComposeScene(width, height, Density(density)) {
            CompositionLocalProvider(LocalLifecycleOwner provides Started()) {
                viz.Content(Modifier)
            }
        }
        try {
            var t = 1_000_000_000L
            for (f in 0 until frames) {
                spectrum.value = mix(f)
                Snapshot.sendApplyNotifications()
                scene.render(t).close()
                t += 16_666_667L
            }
            val dir = File("build/orphograph-render").apply { mkdirs() }
            File(dir, "$name.png").writeBytes(scene.render(t).encodeToData()!!.bytes)
        } finally {
            scene.close()
            Dispatchers.resetMain()
        }
    }

    // 0.1 s after a kick, so the low bars are falling away from their peaks.
    @Test fun desktop() = run("desktop", 1200, 520, 1f, frames = 66)
    @Test fun phone() = run("phone", 780, 900, 2f, frames = 66)
    // The Fold2's cover screen (1080 x 2520) at half size: narrow columns melted into a wall there.
    @Test fun foldCover() = run("fold_cover", 540, 1260, 1.3f, frames = 66)
}
