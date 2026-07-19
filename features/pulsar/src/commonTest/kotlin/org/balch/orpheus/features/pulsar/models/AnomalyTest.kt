package org.balch.orpheus.features.pulsar.models

import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Serialization + validation contract for the sealed [org.balch.orpheus.features.pulsar.anonmalies.Anomaly] hierarchy on [Vibe.anomalies].
 * The C++ atomic wire format is untouched by this migration; these tests pin the Kotlin schema.
 */
class AnomalyTest {

    // A known-good 8-track vibe to layer anomalies onto. DogHouse has no lick/rotation of its own.
    private val base = DogHouseVibe().vibe
    private val smallLick = Lick(listOf(LickStep(0, 0.5f)), loopLength = 8)

    // encodeDefaults so every field survives the round-trip; the default class discriminator is "type".
    private val json = Json { encodeDefaults = true }
    // Mirrors vibeApplyJson's ignoreUnknownKeys: archived/legacy vibes must still decode. A lookalike,
    // not the real decoder (features/ai depends on features/pulsar, so vibeApplyJson can't be imported
    // here); VibeSchemaToolTest in features/ai pins the production decoder's discriminator.
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun polymorphic_round_trip_preserves_both_anomalies() {
        val vibe = base.copy(
            lick = smallLick,          // a lick source so the LickAnomaly is legal
            lickRotation = null,
            anomalies = listOf(
                VoidAnomaly(
                    probability = 0.2f,
                    floorLevel = 0.1f,
                    rampDownBars = 2.5f,
                    floorBarsMin = 0.5f,
                    floorBarsMax = 3.0f,
                    rampUpBars = 0.75f,
                    ghostIntensity = 0.8f,
                ),
                LickAnomaly(lick = smallLick, chance = 0.3f),
            ),
        )
        val encoded = json.encodeToString(Vibe.serializer(), vibe)
        // Both subtypes carry their @SerialName discriminator under the "type" key.
        assertTrue(encoded.contains("\"type\":\"void\""), "missing void discriminator: $encoded")
        assertTrue(encoded.contains("\"type\":\"lick\""), "missing lick discriminator: $encoded")

        val decoded = json.decodeFromString(Vibe.serializer(), encoded)
        assertEquals(vibe.anomalies, decoded.anomalies)
        assertEquals(vibe, decoded)
    }

    @Test
    fun absent_anomalies_key_decodes_to_empty_list() {
        // encodeDefaults=false omits the default emptyList; the decode must restore it.
        val omitting = Json { ignoreUnknownKeys = true }
        val encoded = omitting.encodeToString(Vibe.serializer(), base)
        assertFalse(encoded.contains("anomalies"), "empty anomalies should be omitted: $encoded")
        val decoded = omitting.decodeFromString(Vibe.serializer(), encoded)
        assertTrue(decoded.anomalies.isEmpty())
    }

    @Test
    fun legacy_voidAnomaly_key_is_dropped_not_fatal() {
        // A vibe archived under the OLD schema carried a top-level "voidAnomaly" object. With
        // ignoreUnknownKeys (as the apply path uses) it must decode, silently dropping that key.
        val encoded = lenient.encodeToString(Vibe.serializer(), base)
        val legacy = encoded.replaceFirst("{", "{\"voidAnomaly\":{\"probability\":0.1},")
        val decoded = lenient.decodeFromString(Vibe.serializer(), legacy)
        assertTrue(decoded.anomalies.isEmpty(), "legacy voidAnomaly must not populate anomalies")
    }

    @Test
    fun rejects_anomalies_without_an_arrangement() {
        // base (DogHouse) ships with a real arrangement; null it out here so a declared anomaly
        // would flash the manual-trigger tint but the engine would never arm it (it only arms
        // at section boundaries).
        val exception = assertFailsWith<IllegalArgumentException> {
            base.copy(arrangement = null, anomalies = listOf(VoidAnomaly()))
        }
        assertEquals(
            "anomalies require an arrangement — the Anomaly Engine arms at section boundaries",
            exception.message,
        )
    }

    @Test
    fun anomalies_with_an_arrangement_construct() {
        // base already carries a real arrangement (DogHouse); layering an anomaly onto it must
        // still construct cleanly.
        val vibe = base.copy(anomalies = listOf(VoidAnomaly()))
        assertEquals(1, vibe.anomalies.size)
    }

    @Test
    fun rejects_duplicate_void_anomaly() {
        assertFailsWith<IllegalArgumentException> {
            base.copy(anomalies = listOf(VoidAnomaly(), VoidAnomaly()))
        }
    }

    @Test
    fun rejects_duplicate_wah_anomaly() {
        assertFailsWith<IllegalArgumentException> {
            base.copy(anomalies = listOf(WahAnomaly(), WahAnomaly()))
        }
    }

    @Test
    fun wah_anomaly_round_trips_with_its_discriminator() {
        val vibe = base.copy(anomalies = listOf(WahAnomaly(probability = 0.1f)))
        val encoded = json.encodeToString(Vibe.serializer(), vibe)
        assertTrue(encoded.contains("\"type\":\"wah\""), "missing wah discriminator: $encoded")
        val decoded = json.decodeFromString(Vibe.serializer(), encoded)
        assertEquals(vibe.anomalies, decoded.anomalies)
    }

    @Test
    fun rejects_duplicate_lick_anomaly() {
        assertFailsWith<IllegalArgumentException> {
            base.copy(
                lick = smallLick,
                anomalies = listOf(LickAnomaly(lick = smallLick), LickAnomaly(lick = smallLick)),
            )
        }
    }

    @Test
    fun rejects_lick_anomaly_without_a_lick_source() {
        assertFailsWith<IllegalArgumentException> {
            base.copy(lick = null, lickRotation = null, anomalies = listOf(LickAnomaly(lick = smallLick)))
        }
    }

    @Test
    fun rejects_bank_capacity_overflow() {
        // rotation pool of 4 + 1 lick anomaly = 5 slots > MAX_LICK_POOL (4).
        assertFailsWith<IllegalArgumentException> {
            base.copy(
                lickRotation = LickRotation(pool = listOf(smallLick, smallLick, smallLick, smallLick)),
                anomalies = listOf(LickAnomaly(lick = smallLick)),
            )
        }
    }

    @Test
    fun lick_anomaly_over_a_single_lick_is_valid() {
        // A LickAnomaly with only vibe.lick (no rotation) is allowed: C++ treats it as a rotation-of-1.
        val vibe = base.copy(lick = smallLick, lickRotation = null, anomalies = listOf(
            LickAnomaly(
                lick = smallLick
            )
        ))
        assertNotNull(vibe.anomalies.filterIsInstance<LickAnomaly>().singleOrNull())
    }
}
