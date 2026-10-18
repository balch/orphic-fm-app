package org.balch.orpheus.features.record

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
import org.balch.orpheus.features.record.telemetry.VoiceSnapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TakeTelemetryJsonTest {

    private val snapshot = TakeSnapshot(
        bpm = 120f,
        pairs = listOf(
            PairSnapshot(
                pair = 0, engineId = 107, engineName = "VOW",
                harmonics = 0.2f, timbre = 0.43f, morph = 0.45f,
                modSource = "LFO", modSourceLevel = 0.1f,
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
}
