package org.balch.orpheus.features.ai.composer

import org.balch.orpheus.features.ai.composer.EngineMirror.FX_GATE_EPSILON
import org.balch.orpheus.features.ai.composer.EngineMirror.FX_HIGH_COMPLEXITY
import org.balch.orpheus.features.ai.composer.EngineMirror.FX_HIGH_COMPLEXITY_SLOPE
import org.balch.orpheus.features.ai.composer.EngineMirror.FX_HIGH_ENERGY
import org.balch.orpheus.features.ai.composer.EngineMirror.FX_HIGH_ENERGY_SLOPE
import org.balch.orpheus.features.ai.composer.EngineMirror.FX_LOW_EDGE
import org.balch.orpheus.features.ai.composer.EngineMirror.FX_LOW_SLOPE
import org.balch.orpheus.features.ai.composer.EngineMirror.NOTCH_FLOOR_FROM
import org.balch.orpheus.features.ai.composer.EngineMirror.NOTCH_FLOOR_TO
import org.balch.orpheus.features.ai.composer.EngineMirror.NOTCH_FULL_BELOW
import org.balch.orpheus.features.ai.composer.EngineMirror.NOTCH_FULL_FROM
import org.balch.orpheus.features.ai.composer.EngineMirror.NOTCH_GAIN
import org.balch.orpheus.core.audio.OrpheusEngineId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds [EngineMirror] to the C++ it mirrors, parsed by relative path (Gradle's test working
 * dir is the module). Both files are jvmTest inputs in features/ai/build.gradle.kts.
 */
class EngineMirrorParityTest {

    private val modRanges = File("../../liborpheus_dsp/src/pulsar_mod_ranges.h")
    private val pulsar = File("../../liborpheus_dsp/src/orpheus_unit_pulsar.cpp")

    private fun text(f: File): String {
        assertTrue(f.exists(), "missing C++ source ${f.absolutePath}")
        return f.readText()
    }

    /** The body of the function whose signature starts [signature], up to its first closing brace at column 0. */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "could not find `$signature`" }
        val end = source.indexOf("\n}", start)
        require(end > start) { "could not find the end of `$signature`" }
        return source.substring(start + signature.length, end)
    }

    private fun floats(body: String): List<Float> = Regex("""(\d+\.\d+)f""").findAll(body).map { it.groupValues[1].toFloat() }.toList()

    private fun squash(s: String) = s.replace(Regex("""\s+"""), " ")

    // pulsar_mod_ranges.h: inline float texture_energy_curve(float energy)
    @Test
    fun textureCurveMatchesTheCpp() {
        val literals = floats(body(text(modRanges), "inline float texture_energy_curve(float energy)"))
        val expected = listOf(
            NOTCH_FULL_BELOW, 1f,
            NOTCH_FLOOR_FROM, 1f, NOTCH_FULL_BELOW, 1f - NOTCH_GAIN, NOTCH_FLOOR_FROM - NOTCH_FULL_BELOW,
            NOTCH_FLOOR_TO, NOTCH_GAIN,
            NOTCH_FULL_FROM, NOTCH_GAIN, NOTCH_FLOOR_TO, 1f - NOTCH_GAIN, NOTCH_FULL_FROM - NOTCH_FLOOR_TO,
            1f,
        )
        require(literals.size == expected.size) { "texture_energy_curve has ${literals.size} float literals, want ${expected.size}: $literals" }
        literals.zip(expected).forEachIndexed { i, (cpp, kt) -> assertEquals(cpp, kt, 1e-6f, "texture_energy_curve literal $i") }
    }

    // orpheus_unit_pulsar.cpp: static float compute_fx_probability(float energy, float complexity)
    @Test
    fun fxProbabilityMatchesTheCpp() {
        val literals = floats(body(text(pulsar), "static float compute_fx_probability(float energy, float complexity)"))
        val expected = listOf(
            0f, 1f, FX_LOW_EDGE, FX_LOW_SLOPE,
            0f, FX_HIGH_COMPLEXITY, FX_HIGH_COMPLEXITY_SLOPE,
            0f, FX_HIGH_ENERGY, FX_HIGH_ENERGY_SLOPE,
        )
        require(literals.size == expected.size) { "compute_fx_probability has ${literals.size} float literals, want ${expected.size}: $literals" }
        literals.zip(expected).forEachIndexed { i, (cpp, kt) -> assertEquals(cpp, kt, 1e-6f, "compute_fx_probability literal $i") }
    }

    // The role rules notchGain and fxGateOpen mirror, in the render loop's own words.
    @Test
    fun liftAndGateRulesMatchTheCpp() {
        val src = squash(text(pulsar))
        listOf(
            // texture_line is read only on a Melodic role.
            "ts.texture_line = ts.role == TrackRole::MELODIC && engine->pulsar_track_texture_line[t].load(std::memory_order_relaxed) != 0;",
            // The notch covers tracks 5-7; Percussive takes the bare curve.
            "if (t >= 5) { float curve = texture_energy_curve(energy); if (ts.role != TrackRole::PERCUSSIVE) {",
            // A lick line without texture_line, and any Chordal track, are lifted to full.
            "const bool lick_line = ts.role == TrackRole::MELODIC && static_cast<LickMode>(engine->pulsar_track_lick_mode[t].load( std::memory_order_relaxed)) != LickMode::NONE;",
            "if ((lick_line && !ts.texture_line) || ts.role == TrackRole::CHORDAL) lift = 1.0f;",
            // Track 7 drops a note while the FX probability is shut, unless soloing or a texture line.
            "if (t == 7) { float fx_prob = compute_fx_probability(energy, complexity);",
            "if (fx_prob <= ${FX_GATE_EPSILON}f && lift <= 0.001f && !ts.texture_line) {",
        ).forEach { assertTrue(it in src, "orpheus_unit_pulsar.cpp no longer says:\n$it\nUpdate EngineMirror to match the render loop.") }
    }

    // pulsar_mod_ranges.h: kEngineModRanges[24], fields 9-11 are the harmonics/timbre/morph floors.
    @Test
    fun playabilityFloorsMatchTheCpp() {
        val table = text(modRanges).substringAfter("kEngineModRanges[24] = {").substringBefore("};")
        val rows = Regex("""\{([^{}]*)\}""").findAll(table).map { m -> m.groupValues[1].split(',').map { it.trim().removeSuffix("f") } }.toList()
        require(rows.size == 24) { "kEngineModRanges has ${rows.size} rows, want 24" }
        rows.forEachIndexed { id, fields ->
            require(fields.size == 13) { "kEngineModRanges row $id has ${fields.size} fields, want 13: $fields" }
            val engine = OrpheusEngineId.entries.firstOrNull { it.id == id } ?: error("no OrpheusEngineId has C++ index $id")
            val cpp = EngineMirror.Floors(fields[9].toFloat(), fields[10].toFloat(), fields[11].toFloat())
            assertEquals(cpp, EngineMirror.floors(engine), "$engine (row $id) playability floors")
        }
    }

    @Test
    fun mirrorReadsAsTheEngineDoes() {
        assertEquals(NOTCH_GAIN, EngineMirror.textureEnergyCurve(0.5f))
        assertEquals(1f, EngineMirror.textureEnergyCurve(0.3f))
        assertEquals(1f, EngineMirror.textureEnergyCurve(0.7f))
        assertEquals(0f, EngineMirror.fxProbability(0.5f, 0.3f))
        assertEquals(0f, EngineMirror.fxProbability(0.7f, 0.3f))
        assertTrue(EngineMirror.fxProbability(0.3f, 0.3f) > FX_GATE_EPSILON)
        assertTrue(EngineMirror.fxProbability(0.7f, 0.8f) > FX_GATE_EPSILON)
    }
}
