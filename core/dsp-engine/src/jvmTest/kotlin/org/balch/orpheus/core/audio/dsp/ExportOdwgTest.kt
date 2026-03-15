package org.balch.orpheus.core.audio.dsp

import java.io.File
import kotlin.test.Test

/**
 * Exports the production wiring graph as an ODWG binary file
 * for use by the C++ test harness. Run with:
 *   ./gradlew :core:dsp-engine:jvmTest --tests "*ExportOdwgTest*"
 */
class ExportOdwgTest {
    @Test
    fun exportDefaultGraph() {
        val bytes = buildDefaultWiringGraph()
        val outDir = File("../../liborpheus_dsp/test/data")
        outDir.mkdirs()
        val outFile = File(outDir, "default_graph.odwg")
        outFile.writeBytes(bytes)
        println("Wrote ${bytes.size} bytes to ${outFile.absolutePath}")
        println("Graph contains ${bytes.size / 2} uint16 words")
    }
}
