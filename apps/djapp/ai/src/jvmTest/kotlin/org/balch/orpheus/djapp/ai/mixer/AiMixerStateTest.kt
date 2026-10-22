package org.balch.orpheus.djapp.ai.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SectionLayer
import org.balch.orpheus.features.pulsar.bench.VibeLayer

class AiMixerStateTest {
    private val v = vibe("Mixer")

    private fun state(
        layers: BenchLayers = BenchLayers(),
        vibe: org.balch.orpheus.features.pulsar.models.Vibe = v,
        section: Int = 0,
        scope: Scope = Scope.VIBE,
        muted: Set<Int> = emptySet(),
        soloed: Set<Int> = emptySet(),
        knobs: Map<Macro, Float> = emptyMap(),
    ) = mixerState(vibe, section, layers, scope, muted, soloed, null, knobs)

    @Test
    fun tracksMirrorEffective() {
        val layers = BenchLayers(vibe = VibeLayer(levelDb = List(8) { if (it == 3) -4f else 0f }))
        val s = state(layers, muted = setOf(1))
        assertEquals(-4f, s.tracks[3].levelDb)
        assertEquals("BASS", s.tracks[3].name)
        assertTrue(s.tracks[1].muted && s.tracks[1].silenced)
    }

    @Test
    fun soloSilencesOthers() {
        val s = state(soloed = setOf(2))
        assertTrue(s.tracks.all { it.silenced == (it.index != 2) })
    }

    @Test
    fun vibeScopeMacroShowsKnob() {
        val file = Macro.ENERGY.let { v.energy }
        val s = state(BenchLayers(vibe = VibeLayer(macros = mapOf(Macro.ENERGY to 0.6f))))
        val m = s.macros.first { it.macro == Macro.ENERGY }
        assertEquals(0.6f, m.value)
        assertEquals(file, m.resetValue)
        assertTrue(m.changed)
    }

    @Test
    fun sectionScopeMacroShowsHeard() {
        val layers = BenchLayers(sections = mapOf(1 to SectionLayer(macrosHeard = mapOf(Macro.MOOD to 0.3f))))
        val s = state(layers, section = 1, scope = Scope.SECTION)
        assertTrue(s.sectionScope)
        val m = s.macros.first { it.macro == Macro.MOOD }
        assertEquals(0.3f, m.value)
        assertTrue(m.changed)
        assertEquals("heard here", m.note)
    }

    @Test
    fun sectionScopeUnavailableShowsVibe() {
        val one = v.copy(arrangement = v.arrangement!!.copy(sections = v.arrangement!!.sections.take(1)))
        val s = state(vibe = one, scope = Scope.SECTION, knobs = mapOf(Macro.SPACE to 0.7f))
        assertFalse(s.sectionScopeAvailable)
        assertFalse(s.sectionScope)
        assertEquals(0.7f, s.macros.first { it.macro == Macro.SPACE }.value)
    }

    @Test
    fun changeLinesMatchBench() {
        val row = CaptureRow(Scope.VIBE, null, null, 3, CaptureField.VOLUME, null, 0.5f, 0.6f)
        assertEquals(
            listOf(
                ChangeLine(ChangeKind.HEADER, "// vibe · BASS"),
                ChangeLine(ChangeKind.OLD, "- volume (edm) = 0.500"),
                ChangeLine(ChangeKind.NEW, "+ volume (edm) = 0.600  (+1.6 dB)"),
            ),
            changeLines(listOf(row)),
        )
    }
}
