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
        val encoded = json.encodeToString(OrpheusEngineId.serializer(), OrpheusEngineId.DX)
        assertEquals("\"DX\"", encoded)
    }

    @Test
    fun `deserializes legacy persisted name`() {
        val decoded = json.decodeFromString(OrpheusEngineId.serializer(), "\"DX\"")
        assertEquals(OrpheusEngineId.DX, decoded)
    }

    @Test
    fun `round-trips all legacy-named engines`() {
        // Every entry in the old Pulsar Engine enum must round-trip via its old name.
        val legacyMap = mapOf(
            "VCF" to OrpheusEngineId.VCF,
            "PD"  to OrpheusEngineId.PD,
            "DX"  to OrpheusEngineId.DX,
            "DX2" to OrpheusEngineId.DX2,
            "DX3" to OrpheusEngineId.DX3,
            "TRN" to OrpheusEngineId.TRN,
            "ENS" to OrpheusEngineId.ENS,
            "NES" to OrpheusEngineId.NES,
            "VA"  to OrpheusEngineId.VA,
            "WSH" to OrpheusEngineId.WSH,
            "FM"  to OrpheusEngineId.FM,
            "GRN" to OrpheusEngineId.GRN,
            "ADD" to OrpheusEngineId.ADD,
            "WTB" to OrpheusEngineId.WTB,
            "CHD" to OrpheusEngineId.CHD,
            "SPK" to OrpheusEngineId.SPK,
            "SWM" to OrpheusEngineId.SWM,
            "NSE" to OrpheusEngineId.NSE,
            "PAR" to OrpheusEngineId.PAR,
            "STR" to OrpheusEngineId.STR,
            "MOD" to OrpheusEngineId.MOD,
            "BD"  to OrpheusEngineId.BD,
            "SD"  to OrpheusEngineId.SD,
            "HH"  to OrpheusEngineId.HH,
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
        assertEquals(OrpheusEngineId.BD, OrpheusEngineId.fromId(21))
        assertEquals(21, OrpheusEngineId.FM_DRUM.id)
        assertEquals(21, OrpheusEngineId.BD.id)
    }
}
