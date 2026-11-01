package org.balch.orpheus.features.cowbell.bell3d

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.ShaderBrush

/** The liquid layer through AGSL `RuntimeShader`, API 33+ only; older devices keep the mesh. */
internal actual class LiquidRenderer actual constructor() {
    private var shader: RuntimeShader? = null
    private val polyData = FloatArray(LIQUID_POLY_MAX * 2)
    private val dropsData = FloatArray(LIQUID_DROP_COUNT * 4)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = RuntimeShader(LIQUID_SKSL)
            } catch (e: Exception) {
                // AGSL compilation failed: isSupported() goes false.
            }
        }
    }

    actual fun isSupported(): Boolean = shader != null

    actual fun brush(unit: Float, timeSeconds: Float, frame: LiquidFrame): ShaderBrush? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val s = shader ?: return null
        if (frame.polyCount < 3 || unit <= 0f) return null
        for (i in polyData.indices) polyData[i] = frame.poly[i] / unit
        for (i in dropsData.indices) dropsData[i] = frame.drops[i] / unit

        s.setFloatUniform("t", timeSeconds)
        s.setIntUniform("np", minOf(frame.polyCount, LIQUID_POLY_MAX))
        s.setFloatUniform("poly", polyData)
        s.setFloatUniform("drops", dropsData)
        s.setFloatUniform("k", LIQUID_K)
        s.setFloatUniform("bevel", LIQUID_BEVEL)
        s.setFloatUniform("inflate", frame.inflate / unit)
        s.setFloatUniform("unit", unit)
        return ShaderBrush(s)
    }

    actual fun dispose() {
        shader = null
    }
}
