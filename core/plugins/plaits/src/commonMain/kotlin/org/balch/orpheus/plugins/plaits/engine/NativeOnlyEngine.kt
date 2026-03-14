package org.balch.orpheus.plugins.plaits.engine

import org.balch.orpheus.plugins.plaits.EngineParameters
import org.balch.orpheus.plugins.plaits.PlaitsEngine
import org.balch.orpheus.plugins.plaits.PlaitsEngineId

/**
 * Silent stub for C++-only engines. Produces no audio —
 * actual rendering happens in the native DSP bridge.
 * [alreadyEnveloped] = true keeps the Kotlin VCA open.
 */
class NativeOnlyEngine(
    override val id: PlaitsEngineId,
) : PlaitsEngine {
    override val displayName: String get() = id.displayName
    override val alreadyEnveloped: Boolean = true
    override val outGain: Float = 1.0f

    override fun init() {}
    override fun reset() {}

    override fun render(
        params: EngineParameters,
        out: FloatArray,
        aux: FloatArray?,
        size: Int,
    ): Boolean {
        for (i in 0 until size) {
            out[i] = 0f
            aux?.set(i, 0f)
        }
        return true
    }
}
