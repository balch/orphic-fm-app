package org.balch.orpheus.features.cowbell.bell3d

import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** The liquid layer through skiko's `RuntimeEffect`, shared by JVM, WASM and iOS. */
internal actual class LiquidRenderer actual constructor() {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private val polyData = FloatArray(LIQUID_POLY_MAX * 2)
    private val dropsData = FloatArray(LIQUID_DROP_COUNT * 4)

    init {
        try {
            effect = RuntimeEffect.makeForShader(LIQUID_SKSL)
            builder = effect?.let { RuntimeShaderBuilder(it) }
        } catch (e: Exception) {
            // Compilation failed: isSupported() goes false and the caller keeps the mesh.
        }
    }

    actual fun isSupported(): Boolean = builder != null

    actual fun brush(unit: Float, timeSeconds: Float, frame: LiquidFrame): ShaderBrush? {
        val b = builder ?: return null
        if (frame.polyCount < 3 || unit <= 0f) return null
        for (i in polyData.indices) polyData[i] = frame.poly[i] / unit
        for (i in dropsData.indices) dropsData[i] = frame.drops[i] / unit

        b.uniform("t", timeSeconds)
        b.uniform("np", minOf(frame.polyCount, LIQUID_POLY_MAX))
        b.uniform("poly", polyData)
        b.uniform("drops", dropsData)
        b.uniform("k", LIQUID_K)
        b.uniform("bevel", LIQUID_BEVEL)
        b.uniform("inflate", frame.inflate / unit)
        b.uniform("unit", unit)
        return ShaderBrush(b.makeShader().asComposeShader())
    }

    actual fun dispose() {
        builder?.close()
        builder = null
        effect?.close()
        effect = null
    }
}
