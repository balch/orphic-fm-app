package org.balch.orpheus.features.record

import kotlinx.datetime.LocalDateTime
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.duoModSourceLegacyKey
import org.balch.orpheus.core.audio.patch.duoPatchKey
import org.balch.orpheus.core.audio.patch.legacyDuoPatch
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.features.record.telemetry.snapshotOf
import org.balch.orpheus.features.record.telemetry.soundingMidiNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val dxEngineId = OrpheusEngineId.entries.first { it.forcePinHarmonics }.id

class TakeSnapshotMapperTest {

    private fun assertNear(expected: Float, actual: Float, tol: Float = 0.001f) =
        assertTrue(kotlin.math.abs(expected - actual) <= tol, "expected $expected got $actual")

    private fun key(s: VoiceSymbol) = s.controlId.key

    @Test
    fun soundingMidiNoteMatchesEngineFormula() {
        assertNear(65.16f, soundingMidiNote(1, 0.71f, 0.92f))
        assertNear(67.56f, soundingMidiNote(2, 0.72f, 0.5f))
        assertNear(45f, soundingMidiNote(6, 0f, 0.5f))
    }

    @Test
    fun takeIdFormat() {
        assertEquals(
            "take-2026-10-17-132431",
            takeIdFor(LocalDateTime(2026, 10, 17, 13, 24, 31)),
        )
    }

    @Test
    fun snapshotReadsPresetPorts() {
        val preset = SynthPreset(
            bpm = 98f,
            portValues = mapOf(
                key(VoiceSymbol.duoEngine(0)) to PortValue.IntValue(107),
                key(VoiceSymbol.duoHarmonics(0)) to PortValue.FloatValue(0.2f),
                key(VoiceSymbol.duoMorph(0)) to PortValue.FloatValue(0.45f),
                key(VoiceSymbol.duoSharpness(0)) to PortValue.FloatValue(0.43f),
                duoModSourceLegacyKey(0) to PortValue.IntValue(2),
                key(VoiceSymbol.tune(0)) to PortValue.FloatValue(0.71f),
                key(VoiceSymbol.tune(1)) to PortValue.FloatValue(0.72f),
                key(VoiceSymbol.quadPitch(0)) to PortValue.FloatValue(0.92f),
                key(VoiceSymbol.quadTriggerSource(1)) to PortValue.IntValue(3),
            ),
        )
        val snap = snapshotOf(preset)
        assertEquals(98f, snap.bpm)
        assertEquals(6, snap.pairs.size)
        assertEquals(3, snap.quads.size)
        val p0 = snap.pairs[0]
        assertEquals(107, p0.engineId)
        assertEquals("VOW", p0.engineName)
        assertEquals(legacyDuoPatch(LegacyModSource.LFO, onDx = false, duoIndex = 0).cables, p0.cables)
        assertNear(0.2f, p0.harmonics)
        assertNear(0.43f, p0.timbre)
        assertNear(0.45f, p0.morph)
        assertEquals(2, p0.voices.size)
        assertNear(33f + 34.56f - 12f + 10.08f, p0.voices[1].midiNote)
        assertEquals(3, snap.quads[1].triggerSource)
    }

    @Test
    fun snapshotAcceptsFloatStoredInts() {
        val preset = SynthPreset(
            portValues = mapOf(
                key(VoiceSymbol.duoEngine(0)) to PortValue.FloatValue(107f),
                duoModSourceLegacyKey(0) to PortValue.FloatValue(2f),
            ),
        )
        val p0 = snapshotOf(preset).pairs[0]
        assertEquals(107, p0.engineId)
        assertEquals(legacyDuoPatch(LegacyModSource.LFO, onDx = false, duoIndex = 0).cables, p0.cables)
    }

    @Test
    fun emptyPresetFallsBackToPluginDefaults() {
        val snap = snapshotOf(SynthPreset())
        assertEquals(6, snap.pairs.size)
        snap.pairs.forEachIndexed { i, p ->
            assertEquals(i, p.pair)
            assertEquals(-1, p.engineId)
            assertEquals("OSC", p.engineName)
            assertEquals(legacyDuoPatch(LegacyModSource.VOICE_FM, onDx = false, duoIndex = i).cables, p.cables)
            assertEquals(2, p.voices.size)
            assertEquals(2 * i, p.voices[0].voice)
            assertEquals(2 * i + 1, p.voices[1].voice)
            assertNear(0.5f, p.voices[0].tune)
        }
        assertNear(0.5f, snap.quads[0].pitch)
        assertNear(0.3f, snap.effects.delayTime1)
        assertNear(0.5f, snap.effects.delayFeedback)
        assertNear(0f, snap.effects.reverbAmount)
    }

    @Test
    fun legacyPresetBecomesCables() {
        val preset = SynthPreset(
            portValues = mapOf(
                key(VoiceSymbol.duoEngine(0)) to PortValue.IntValue(107),
                duoModSourceLegacyKey(0) to PortValue.IntValue(2),
            ),
        )
        val cables = snapshotOf(preset).pairs[0].cables
        assertEquals(5, cables.size)
        assertTrue(cables.any { it.source == PatchSource.LFO_AB && it.dest == PatchDest.TIMBRE })
        assertTrue(cables.none { it.parked })
    }

    @Test
    fun legacyLfoOnDxParksHarmonics() {
        val preset = SynthPreset(
            portValues = mapOf(
                key(VoiceSymbol.duoEngine(0)) to PortValue.IntValue(dxEngineId),
                duoModSourceLegacyKey(0) to PortValue.IntValue(2),
            ),
        )
        val parked = snapshotOf(preset).pairs[0].cables.filter { it.parked }
        assertEquals(listOf(PatchDest.HARMONICS), parked.map { it.dest })
    }

    @Test
    fun patchPresetBecomesCables() {
        val patch = DuoPatch().connect(PatchSource.WAVES_2, PatchDest.MORPH, amount = -0.4f)
        val preset = SynthPreset(
            portValues = mapOf(
                duoPatchKey(1) to PortValue.StringValue(patch.encode()),
                duoModSourceLegacyKey(1) to PortValue.IntValue(2),
            ),
        )
        assertEquals(patch.cables, snapshotOf(preset).pairs[1].cables)
    }
}
