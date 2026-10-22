package org.balch.orpheus.features.pulsar.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.Vibe

private fun vibeWith(
    sections: List<Section>,
    volEdm: Float = .8f,
    volSpace: Float = .6f,
    energy: Float = .5f,
): Vibe {
    val base = mkMinimalVibe("bench")
    val tracks = base.tracks.map {
        it.copy(
            engineEdm = it.engineEdm.copy(volume = volEdm),
            engineSpace = it.engineSpace.copy(volume = volSpace),
        )
    }
    return base.copy(tracks = tracks, energy = energy, arrangement = Arrangement(sections = sections))
}

private fun layerDb(t: Int, db: Float) = List(8) { if (it == t) db else 0f }

class BenchCaptureTest {
    @Test
    fun zeroLayersCaptureNothing() {
        assertTrue(capture(vibeWith(listOf(Section("a"), Section("b"))), BenchLayers()).isEmpty())
    }

    @Test
    fun vibeFaderScalesBothSlotsAndOverrides() {
        val v = vibeWith(
            listOf(
                Section("a", trackOverrides = mapOf(3 to TrackSectionOverride(volume = .5f))),
                Section("b"),
            ),
        )
        val rows = capture(v, BenchLayers(vibe = VibeLayer(levelDb = layerDb(3, -6.0206f))))
        assertEquals(3, rows.size)
        assertEquals(Scope.VIBE, rows[0].scope)
        assertEquals(CaptureField.VOLUME, rows[0].field)
        assertEquals(.4f, rows[0].new, 1e-3f)
        assertEquals(CaptureField.VOLUME_SPACE, rows[1].field)
        assertEquals(.3f, rows[1].new, 1e-3f)
        assertEquals(Scope.SECTION, rows[2].scope)
        assertEquals(0, rows[2].section)
        assertEquals("a", rows[2].sectionName)
        assertEquals(.25f, rows[2].new, 1e-3f)
    }

    @Test
    fun sectionFaderUsesHeardLevel() {
        val v = vibeWith(
            listOf(
                Section("a"),
                Section("b", trackOverrides = mapOf(2 to TrackSectionOverride(volume = .5f))),
            ),
        )
        val rows = capture(v, BenchLayers(sections = mapOf(1 to SectionLayer(levelDb = layerDb(2, 3f)))))
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].section)
        assertEquals(.5f, rows[0].old, 1e-6f)
        assertEquals(.70625f, rows[0].new, 1e-3f)
    }

    @Test
    fun clampFlagsWhenOverOne() {
        val v = vibeWith(listOf(Section("a"), Section("b")), volEdm = .9f, volSpace = .1f)
        val rows = capture(v, BenchLayers(vibe = VibeLayer(levelDb = layerDb(0, 3f))))
        val vol = rows.first { it.field == CaptureField.VOLUME }
        assertEquals(1f, vol.new, 1e-6f)
        assertEquals(2.08f, vol.clampedDb, 0.01f)
        assertEquals(0f, rows.first { it.field == CaptureField.VOLUME_SPACE }.clampedDb, 1e-6f)
    }

    @Test
    fun sectionMacroKeepsHeard() {
        val v = vibeWith(
            listOf(Section("a"), Section("b", macroOverrides = MacroOverrides(energy = 1.35f))),
            energy = .5f,
        )
        val rows = capture(v, BenchLayers(sections = mapOf(1 to SectionLayer(macrosHeard = mapOf(Macro.ENERGY to .8f)))))
        assertEquals(1, rows.size)
        assertEquals(CaptureField.MACRO, rows[0].field)
        assertEquals(Macro.ENERGY, rows[0].macro)
        assertEquals(1.35f, rows[0].old, 1e-6f)
        assertEquals(1.6f, rows[0].new, 1e-5f)
    }

    @Test
    fun sectionMacroScalesRamp() {
        val v = vibeWith(
            listOf(
                Section("a"),
                Section("b", macroOverrides = MacroOverrides(energy = 1.35f), rampTo = MacroOverrides(energy = 1.5f)),
            ),
            energy = .5f,
        )
        val rows = capture(v, BenchLayers(sections = mapOf(1 to SectionLayer(macrosHeard = mapOf(Macro.ENERGY to .8f)))))
        assertEquals(2, rows.size)
        assertEquals(CaptureField.MACRO_RAMP, rows[1].field)
        assertEquals(1.5f * 1.6f / 1.35f, rows[1].new, 1e-4f)
    }

    @Test
    fun vibeMacroWritesKnob() {
        val v = vibeWith(listOf(Section("a"), Section("b")), energy = .5f)
        val rows = capture(v, BenchLayers(vibe = VibeLayer(macros = mapOf(Macro.ENERGY to .7f))))
        assertEquals(1, rows.size)
        assertEquals(Scope.VIBE, rows[0].scope)
        assertEquals(.5f, rows[0].old, 1e-6f)
        assertEquals(.7f, rows[0].new, 1e-6f)
    }

    @Test
    fun wholeVibeSendKeepsSectionDifference() {
        val v = vibeWith(
            listOf(
                Section("verse", trackOverrides = mapOf(1 to TrackSectionOverride(reverbSend = .2f))),
                Section("chorus", trackOverrides = mapOf(1 to TrackSectionOverride(reverbSend = .4f))),
            ),
        )
        val layers = BenchLayers(vibe = VibeLayer(reverbRatio = List(8) { 1.5f }))
        assertEquals(.3f, layers.effective(v, 0, 1).reverbSend, 1e-5f)
        assertEquals(.6f, layers.effective(v, 1, 1).reverbSend, 1e-5f)
        val rows = capture(v, layers).filter { it.scope == Scope.SECTION }
        assertEquals(listOf(.3f, .6f), rows.map { it.new })
    }

    @Test
    fun sectionAbsoluteSendThenVibeRatio() {
        val v = vibeWith(listOf(Section("a"), Section("b")))
        val layers = BenchLayers(
            vibe = VibeLayer(delayRatio = List(8) { 2f }),
            sections = mapOf(1 to SectionLayer(delaySend = List(8) { if (it == 0) .25f else null })),
        )
        assertEquals(.5f, layers.overlay(v).delaySend(0, 1, .1f), 1e-6f)
        assertEquals(.2f, layers.overlay(v).delaySend(0, 0, .1f), 1e-6f)
    }

    @Test
    fun panIsVibeScopeOnly() {
        val v = vibeWith(listOf(Section("a"), Section("b")))
        val rows = capture(v, BenchLayers(vibe = VibeLayer(pan = List(8) { if (it == 4) -.5f else null })))
        assertEquals(1, rows.size)
        assertEquals(CaptureField.PAN, rows[0].field)
        assertEquals(Scope.VIBE, rows[0].scope)
        assertEquals(-.5f, rows[0].new, 1e-6f)
    }

    @Test
    fun noArrangementForcesVibeScope() {
        val one = vibeWith(listOf(Section("a")))
        assertFalse(one.sectionScopeAvailable())
        assertFalse(one.copy(arrangement = null).sectionScopeAvailable())
        assertTrue(vibeWith(listOf(Section("a"), Section("b"))).sectionScopeAvailable())
    }

    @Test
    fun macroPortValueDividesSectionMultiplier() {
        val v = vibeWith(listOf(Section("a"), Section("b", macroOverrides = MacroOverrides(energy = 1.35f))))
        val layers = BenchLayers(sections = mapOf(1 to SectionLayer(macrosHeard = mapOf(Macro.ENERGY to .8f))))
        assertEquals(.8f / 1.35f, layers.macroPortValue(v, 1, Macro.ENERGY), 1e-6f)
        assertEquals(.5f, layers.macroPortValue(v, 0, Macro.ENERGY), 1e-6f)
    }

    @Test
    fun auditionGainClamps() {
        val layers = BenchLayers(
            vibe = VibeLayer(levelDb = layerDb(0, 3f)),
            sections = mapOf(0 to SectionLayer(levelDb = layerDb(0, -6.0206f))),
        )
        assertEquals(.7063f, layers.auditionGain(0, 0), 1e-3f)
        assertEquals(1.4125f, layers.auditionGain(0, 5), 1e-3f)
        assertEquals(1.5f, BenchLayers(vibe = VibeLayer(levelDb = layerDb(0, 12f))).auditionGain(0, 5), 1e-6f)
    }

    @Test
    fun vibeSendRatioScalesSlotsSeparately() {
        val base = vibeWith(listOf(Section("a"), Section("b")))
        val v = base.copy(
            tracks = base.tracks.map {
                it.copy(
                    engineEdm = it.engineEdm.copy(reverbSend = .2f),
                    engineSpace = it.engineSpace.copy(reverbSend = .6f),
                )
            },
        )
        val rows = capture(v, BenchLayers(vibe = VibeLayer(reverbRatio = List(8) { if (it == 0) 1.5f else 1f })))
        assertEquals(2, rows.size)
        assertEquals(CaptureField.REVERB_SEND, rows[0].field)
        assertEquals(.3f, rows[0].new, 1e-5f)
        assertEquals(CaptureField.REVERB_SEND_SPACE, rows[1].field)
        assertEquals(.9f, rows[1].new, 1e-5f)
    }

    @Test
    fun sectionFaderPicksActiveSlot() {
        val v = vibeWith(listOf(Section("a"), Section("b")), volEdm = .8f, volSpace = .4f, energy = .3f)
        val rows = capture(v, BenchLayers(sections = mapOf(1 to SectionLayer(levelDb = layerDb(0, 3f)))))
        assertEquals(1, rows.size)
        assertEquals(.4f, rows[0].old, 1e-6f)
        assertEquals(.565f, rows[0].new, 1e-3f)
    }

    @Test
    fun captureMatchesHeardWhenLayersSumPastCap() {
        val v = vibeWith(
            listOf(
                Section("a"),
                Section("b", trackOverrides = mapOf(0 to TrackSectionOverride(volume = .5f))),
            ),
        )
        val layers = BenchLayers(
            vibe = VibeLayer(levelDb = layerDb(0, 3f)),
            sections = mapOf(1 to SectionLayer(levelDb = layerDb(0, 3f))),
        )
        val rows = capture(v, layers)
        val sec = rows.first { it.scope == Scope.SECTION }
        assertEquals(.5f * layers.auditionGain(0, 1), sec.new, 1e-5f)
        assertEquals(.75f, sec.new, 1e-5f)
        val vol = rows.first { it.scope == Scope.VIBE && it.field == CaptureField.VOLUME }
        assertEquals(1f, vol.new, 1e-6f)
        assertEquals(1.07f, vol.clampedDb, 0.01f)
    }

    @Test
    fun wholeVibeEnergyKnobMovesActiveSlot() {
        val v = vibeWith(
            listOf(Section("a"), Section("b", macroOverrides = MacroOverrides(energy = 1f))),
            volEdm = .8f, volSpace = .4f, energy = .3f,
        )
        val layers = BenchLayers(
            vibe = VibeLayer(macros = mapOf(Macro.ENERGY to .7f)),
            sections = mapOf(1 to SectionLayer(levelDb = layerDb(0, 6.0206f))),
        )
        val sec = capture(v, layers).first { it.scope == Scope.SECTION }
        assertEquals(.8f, sec.old, 1e-6f)
        assertEquals(1f, sec.new, 1e-6f)
    }
}
