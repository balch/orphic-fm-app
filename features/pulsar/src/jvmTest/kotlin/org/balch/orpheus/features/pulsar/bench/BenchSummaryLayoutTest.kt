package org.balch.orpheus.features.pulsar.bench

import java.io.File
import org.balch.orpheus.core.audio.dsp.BenchSummaryLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps BenchSummaryLayout in step with mix_bench.h, which the drain flattens by. */
class BenchSummaryLayoutTest {
    private val header = File("../../liborpheus_dsp/src/mix_bench.h")

    private fun text(): String {
        assertTrue(header.exists(), "missing C++ source ${header.absolutePath}")
        return header.readText()
    }

    private fun constant(name: String): Int {
        val m = Regex("""constexpr\s+int\s+[^;]*\b$name\s*=\s*(\d+)""").find(text())
        requireNotNull(m) { "could not find `constexpr int $name` in ${header.name}" }
        return m.groupValues[1].toInt()
    }

    /** Field names of `struct name { ... };` in declaration order. */
    private fun fields(struct: String): List<String> =
        text().substringAfter("struct $struct {").substringBefore("};")
            .lineSequence().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() && !it.contains("(") }
            .joinToString(" ").split(";").map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { decl -> decl.substringAfter(" ").split(",").map { it.trim().substringBefore("[") } }

    @Test
    fun summaryFloatsMatchesHeader() {
        assertTrue(text().contains("constexpr int kBenchSummaryFloats = ${BenchSummaryLayout.FLOATS};"))
        assertEquals(constant("kBenchSummaryFloats"), BenchSummaryLayout.FLOATS)
    }

    @Test
    fun channelOrderMatchesHeader() {
        assertEquals(constant("kBenchTracks"), BenchSummaryLayout.TRACKS)
        assertEquals(constant("kBenchStorm"), BenchSummaryLayout.STORM)
        assertEquals(constant("kBenchStreet"), BenchSummaryLayout.STREET)
        assertEquals(constant("kBenchSpeech"), BenchSummaryLayout.SPEECH)
        assertEquals(constant("kBenchDry"), BenchSummaryLayout.DRY)
        assertEquals(constant("kBenchOut"), BenchSummaryLayout.OUT)
        assertEquals(constant("kBenchChannels"), BenchSummaryLayout.CHANNELS)
    }

    @Test
    fun fieldOrderMatchesHeader() {
        assertEquals(BenchSummaryLayout.CHANNEL_FIELDS, fields("BenchChannel"))
        assertEquals(BenchSummaryLayout.HEADER_FIELDS + "ch", fields("BenchSummary"))
        assertEquals(
            BenchSummaryLayout.HEADER + BenchSummaryLayout.CHANNELS * BenchSummaryLayout.CHANNEL_FLOATS,
            BenchSummaryLayout.FLOATS,
        )
    }
}
