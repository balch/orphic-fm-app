package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.assertTrue

class FaceMorphShaderTest {
    // A bad shader is otherwise only visible as a black screen at runtime.
    @Test fun `the shader compiles`() {
        RuntimeEffect.makeForShader(FaceMorphShaderSource.SKSL).close()
    }

    @Test fun `eyes turn before the mouth and the mouth before the forehead`() {
        val size = 128
        val px = buildFaceBiasMap(size).toPixelMap()
        fun at(u: Float, v: Float) = px[(u * size).toInt(), (v * size).toInt()].red
        val eye = at(FaceLandmarks.leftEye.x, FaceLandmarks.leftEye.y)
        val mouth = at(FaceLandmarks.mouth.x, FaceLandmarks.mouth.y)
        val forehead = at(0.5f, 0.12f)
        val corner = at(0.03f, 0.03f)
        assertTrue(eye > mouth && mouth > forehead && forehead >= corner, "eye=$eye mouth=$mouth forehead=$forehead corner=$corner")
        assertTrue(eye > 0.9f)
    }
}
