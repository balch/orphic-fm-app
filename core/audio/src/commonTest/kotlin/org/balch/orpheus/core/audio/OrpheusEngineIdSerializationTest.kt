package org.balch.orpheus.core.audio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OrpheusEngineIdSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serializes using legacy short name`() {
        // Wire format compatibility: old Pulsar Engine.DX serialized as "DX".
        // OrpheusEngineId.SIX_OP_FM must serialize the same way.
        val encoded = json.encodeToString(OrpheusEngineId.serializer(), OrpheusEngineId.SIX_OP_FM)
        assertEquals("\"DX\"", encoded)
    }

    @Test
    fun `deserializes legacy persisted name`() {
        val decoded = json.decodeFromString(OrpheusEngineId.serializer(), "\"DX\"")
        assertEquals(OrpheusEngineId.SIX_OP_FM, decoded)
    }

    @Test
    fun `round-trips all legacy-named engines`() {
        // Every entry in the old Pulsar Engine enum must round-trip via its old name.
        val legacyMap = mapOf(
            "VCF" to OrpheusEngineId.VIRTUAL_ANALOG_VCF,
            "PD"  to OrpheusEngineId.PHASE_DISTORTION,
            "DX"  to OrpheusEngineId.SIX_OP_FM,
            "DX2" to OrpheusEngineId.SIX_OP_FM_2,
            "DX3" to OrpheusEngineId.SIX_OP_FM_3,
            "TRN" to OrpheusEngineId.WAVE_TERRAIN,
            "ENS" to OrpheusEngineId.STRING_MACHINE,
            "NES" to OrpheusEngineId.CHIPTUNE,
            "VA"  to OrpheusEngineId.VIRTUAL_ANALOG,
            "WSH" to OrpheusEngineId.WAVESHAPING,
            "FM"  to OrpheusEngineId.FM,
            "GRN" to OrpheusEngineId.GRAIN,
            "ADD" to OrpheusEngineId.ADDITIVE,
            "WTB" to OrpheusEngineId.WAVETABLE,
            "CHD" to OrpheusEngineId.CHORD,
            "SPK" to OrpheusEngineId.SPEECH,
            "SWM" to OrpheusEngineId.SWARM,
            "NSE" to OrpheusEngineId.NOISE,
            "PAR" to OrpheusEngineId.PARTICLE,
            "STR" to OrpheusEngineId.STRING,
            "MOD" to OrpheusEngineId.MODAL,
            "BD"  to OrpheusEngineId.ANALOG_BASS_DRUM,
            "SD"  to OrpheusEngineId.ANALOG_SNARE_DRUM,
            "HH"  to OrpheusEngineId.METALLIC_HI_HAT,
        )
        for ((wireName, expected) in legacyMap) {
            val decoded = json.decodeFromString(OrpheusEngineId.serializer(), "\"$wireName\"")
            assertEquals(expected, decoded, "wire name '$wireName' should decode to $expected")
            val reEncoded = json.encodeToString(OrpheusEngineId.serializer(), decoded)
            assertEquals("\"$wireName\"", reEncoded, "$expected should re-encode to \"$wireName\"")
        }
    }

    @Test
    fun `FM_DRUM round-trips by SerialName even though id collides with ANALOG_BASS_DRUM`() {
        // FM_DRUM and ANALOG_BASS_DRUM share id=21 — fromId(21) returns ANALOG_BASS_DRUM.
        // But @SerialName-based serialization preserves identity in both directions.
        val encoded = json.encodeToString(OrpheusEngineId.serializer(), OrpheusEngineId.FM_DRUM)
        assertEquals("\"FM_DRUM\"", encoded)

        val decoded = json.decodeFromString(OrpheusEngineId.serializer(), "\"FM_DRUM\"")
        assertEquals(OrpheusEngineId.FM_DRUM, decoded)

        // Sanity: id-based lookup collapses both to ANALOG_BASS_DRUM (documented behavior).
        assertEquals(OrpheusEngineId.ANALOG_BASS_DRUM, OrpheusEngineId.fromId(21))
        assertEquals(21, OrpheusEngineId.FM_DRUM.id)
        assertEquals(21, OrpheusEngineId.ANALOG_BASS_DRUM.id)
    }
}
