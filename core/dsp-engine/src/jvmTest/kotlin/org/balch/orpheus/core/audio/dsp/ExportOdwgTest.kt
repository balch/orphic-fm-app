package org.balch.orpheus.core.audio.dsp

import java.io.File
import kotlin.test.Test

/**
 * Keeps the checked-in production wiring graph (`default_graph.odwg`) in sync with
 * [buildDefaultWiringGraph], which the C++ test harness loads.
 *
 * By default this only **verifies** — it never writes to the working tree. Regenerate
 * deliberately after changing the graph:
 * ```
 * ./gradlew :core:dsp-engine:exportOdwg
 * ```
 */
class ExportOdwgTest {
    @Test
    fun defaultGraphFixtureMatchesWiringGraph() {
        GeneratedFixture.sync(
            file = File("../../liborpheus_dsp/test/data/default_graph.odwg"),
            generated = buildDefaultWiringGraph(),
            regenerateHint = "./gradlew :core:dsp-engine:exportOdwg",
        )
    }
}
