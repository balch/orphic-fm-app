package org.balch.orpheus.features.visualizations.viz.ants

import org.balch.orpheus.features.visualizations.viz.shader.MtHoodLavaSksl
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaSksl
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test

class PodsShaderTest {
    // A bad shader is otherwise only visible as a viz that vanishes at runtime. This checks the SkSL
    // only: the AGSL compile is still checked only on a device.
    @Test fun `every metaball shader compiles`() {
        val sources = mapOf(
            "pods" to PodsSksl.SOURCE,
            "pods layer" to PodsSksl.LAYER_SOURCE,
            "orphogram" to PlasmaSksl.ORPHOGRAM,
            "orphograph" to PlasmaSksl.ORPHOGRAPH,
            "mt hood lava" to MtHoodLavaSksl.SOURCE,
        )
        for ((name, source) in sources) {
            try {
                RuntimeEffect.makeForShader(source).close()
            } catch (e: Exception) {
                throw AssertionError("the $name shader fails to compile: ${e.message}", e)
            }
        }
    }
}
