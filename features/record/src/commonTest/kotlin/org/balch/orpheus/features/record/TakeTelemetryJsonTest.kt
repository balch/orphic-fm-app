package org.balch.orpheus.features.record

import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.PatchCable
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.legacyDuoPatch
import org.balch.orpheus.features.record.telemetry.Control
import org.balch.orpheus.features.record.telemetry.EffectsSnapshot
import org.balch.orpheus.features.record.telemetry.GateOff
import org.balch.orpheus.features.record.telemetry.GateOn
import org.balch.orpheus.features.record.telemetry.MidiNoteOff
import org.balch.orpheus.features.record.telemetry.MidiNoteOn
import org.balch.orpheus.features.record.telemetry.PairSnapshot
import org.balch.orpheus.features.record.telemetry.QuadSnapshot
import org.balch.orpheus.features.record.telemetry.TakeSnapshot
import org.balch.orpheus.features.record.telemetry.TakeTelemetry
import org.balch.orpheus.features.record.telemetry.TelemetryJson
import org.balch.orpheus.features.record.telemetry.decodeTelemetry
import org.balch.orpheus.features.record.telemetry.VoiceSnapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TakeTelemetryJsonTest {

    private val snapshot = TakeSnapshot(
        bpm = 120f,
        pairs = listOf(
            PairSnapshot(
                pair = 0, engineId = 107, engineName = "VOW",
                harmonics = 0.2f, timbre = 0.43f, morph = 0.45f,
                cables = listOf(PatchCable(PatchSource.LFO_AB, PatchDest.TIMBRE, amount = 0.5f, parked = true)),
                modSourceLevel = 0.1f,
                voices = listOf(VoiceSnapshot(0, 0.5f, 45f, 0.3f)),
            ),
        ),
        quads = listOf(QuadSnapshot(0, 0.5f, 0, 1, 0f)),
        effects = EffectsSnapshot(0.3f, 0.3f, 0.5f, 0f, 0f, 0f, 0f),
    )

    private val telemetry = TakeTelemetry(
        takeId = "take-2026-10-17-132431",
        sampleRate = 48000,
        durationSec = 12.5f,
        droppedFrames = 0,
        start = snapshot,
        end = snapshot,
        events = listOf(
            GateOn(0.1f, 3, 60f),
            GateOff(0.5f, 3),
            Control(0.6f, "org.balch.orpheus.plugins.delay:mix", 0.4f),
            MidiNoteOn(0.7f, 64, 100, 2),
            MidiNoteOff(0.9f, 64, null),
        ),
    )

    @Test
    fun encodesTypeDiscriminators() {
        val json = TelemetryJson.encodeToString(TakeTelemetry.serializer(), telemetry)
        listOf("gateOn", "gateOff", "control", "midiNoteOn", "midiNoteOff").forEach {
            assertContains(json, "\"type\": \"$it\"")
        }
        assertContains(json, "\"version\": 1")
        assertContains(json, "\"app\": \"orpheus-desktop\"")
    }

    @Test
    fun roundTripsEqual() {
        val json = TelemetryJson.encodeToString(TakeTelemetry.serializer(), telemetry)
        assertEquals(telemetry, TelemetryJson.decodeFromString(TakeTelemetry.serializer(), json))
    }

    private fun oldTake(modSource: String, engineId: Int = 107) = """
        {"takeId":"t","sampleRate":48000,"durationSec":1.0,"droppedFrames":0,
         "start":{"bpm":120.0,"pairs":[{"pair":0,"engineId":$engineId,"engineName":"VOW","harmonics":0.2,
           "timbre":0.4,"morph":0.4,"modSource":"$modSource","modSourceLevel":0.1,"voices":[]}],
           "quads":[],"effects":{"delayTime1":0.3,"delayTime2":0.3,"delayFeedback":0.5,"delayMix":0.0,
           "reverbAmount":0.0,"distortionDrive":0.0,"vibratoDepth":0.0}},
         "end":{"bpm":120.0,"pairs":[],"quads":[],"effects":{"delayTime1":0.3,"delayTime2":0.3,
           "delayFeedback":0.5,"delayMix":0.0,"reverbAmount":0.0,"distortionDrive":0.0,"vibratoDepth":0.0}},
         "events":[]}
    """.trimIndent()

    @Test
    fun oldTakeWithModSourceStillDecodes() {
        val pair = decodeTelemetry(oldTake("LFO")).start.pairs[0]
        assertEquals(legacyDuoPatch(LegacyModSource.LFO, false, 0).cables, pair.cables)
        assertEquals(0.1f, pair.modSourceLevel)
        assertNull(pair.modSource)
    }

    @Test
    fun oldFmTakeBecomesFmCables() {
        val pair = decodeTelemetry(oldTake("FM")).start.pairs[0]
        assertEquals(legacyDuoPatch(LegacyModSource.VOICE_FM, false, 0).cables, pair.cables)
    }

    @Test
    fun oldUnknownTakeHasNoCables() {
        assertEquals(emptyList(), decodeTelemetry(oldTake("UNKNOWN")).start.pairs[0].cables)
    }

    @Test
    fun upgradedTakeNeverWritesModSource() {
        val json = TelemetryJson.encodeToString(TakeTelemetry.serializer(), decodeTelemetry(oldTake("LFO")))
        assertFalse(json.contains("modSource\""))
    }
}
