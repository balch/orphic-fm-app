package org.balch.orpheus.core.audio.dsp

import java.io.File
import kotlin.test.Test

/**
 * Exports the DJ App wiring graph as an ODWG binary for the C++ test harness.
 *
 * Unlike [ExportOdwgTest] this always writes. `dj_graph.odwg` is **not** checked in
 * (`.gitignore` covers every `.odwg` under `liborpheus_dsp/test/data`, and this one was
 * never committed), yet `test_djapp_graph.cpp` loads it at runtime — so it is a local
 * build artifact that a fresh clone has to produce before the C++ `djapp` suite can run.
 * Writing it cannot dirty the working tree, so it is exempt from the verify-by-default
 * rule that applies to tracked fixtures.
 *
 * Run standalone with:
 * ```
 * ./gradlew :core:dsp-engine:exportOdwg
 * ```
 */
class ExportDjAppOdwgTest {
    @Test
    fun exportDjAppGraph() {
        val bytes = buildDjAppWiringGraph()
        val outFile = File("../../liborpheus_dsp/test/data/dj_graph.odwg")
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(bytes)
        println("Wrote ${bytes.size} bytes to ${outFile.absolutePath}")
    }
}
