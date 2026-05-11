package org.balch.orpheus.features.pulsar.tools

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protects the DX patch-quantization contract that bridges Kotlin authoring
 * (`OrpheusEngine.harmonics` ∈ [0,1]) to C++ SixOp patch indices.
 *
 * The contract (mirrors `plaits/dsp/engine2/six_op_engine.cc` +
 * `HysteresisQuantizer2`):
 *
 *     patchIndex = clamp(round(harmonics * 1.02f * 32), 0, 31)
 *
 * Vibes pin to bucket centerpoints so future authors get the patch they
 * intended without depending on lerp_macro state. The inverse map used by
 * vibe migrations is:
 *
 *     centerpoint(patch) = patch / (32f * 1.02f)
 *
 * If either constant (1.02f, 32) ever changes, every DX preset shifts; this
 * test guards the bidirectional identity at the bucket anchors actually used
 * by shipped vibes (`Spiral` = 18, `Etherial5a` = 19, mirrored in the C++
 * `test_pulsar_pinning.cpp` fixtures).
 */
class PinPatchCalculatorTest {

    private fun patchIndex(harmonics: Float): Int =
        (harmonics * 1.02f * 32f).roundToInt().coerceIn(0, 31)

    private fun centerpoint(patch: Int): Float = patch / (32f * 1.02f)

    @Test
    fun patchIndex_clamps_at_endpoints() {
        assertEquals(0, patchIndex(0.0f))
        assertEquals(0, patchIndex(-0.1f))
        // 1.0f * 1.02f * 32f = 32.64 → rounds to 33 → clamped to 31
        assertEquals(31, patchIndex(1.0f))
        assertEquals(31, patchIndex(1.5f))
    }

    @Test
    fun centerpoint_round_trips_for_every_patch_index() {
        for (patch in 0..31) {
            val harm = centerpoint(patch)
            val recovered = patchIndex(harm)
            assertEquals(
                patch, recovered,
                "centerpoint($patch) = $harm should re-quantize to $patch (got $recovered)",
            )
        }
    }

    @Test
    fun shipped_vibe_anchors_resolve_to_expected_patches() {
        // DogHouseVibe track 4 EDM = "Spiral"
        assertEquals(18, patchIndex(0.551f))
        // DogHouseVibe track 4 Space + TremoloTideVibe lead = "Etherial5a"
        assertEquals(19, patchIndex(0.582f))
        // ArmyStompVibe lead EDM = DX2 idx 5 "Clav E pno"
        assertEquals(5, patchIndex(0.153f))
        // ArmyStompVibe lead Space = DX3 idx 1 "Hammond"
        assertEquals(1, patchIndex(0.031f))
        // ArmyStompVibe mallet = DX2 idx 17 "Marimba"
        assertEquals(17, patchIndex(0.521f))
    }

    @Test
    fun centerpoints_are_minimal_distance_to_bucket_edges() {
        // A centerpoint must be at least half a bucket-width away from both
        // neighbours, otherwise tiny modulation could flip the rendered patch.
        // bucket width = 1 / (32 * 1.02) ≈ 0.0306
        val bucketWidth = 1f / (32f * 1.02f)
        val halfBucket = bucketWidth * 0.5f
        for (patch in 1..30) {
            val harm = centerpoint(patch)
            val distLow = harm - centerpoint(patch - 1)
            val distHigh = centerpoint(patch + 1) - harm
            assertTrue(
                distLow >= halfBucket * 0.99f && distHigh >= halfBucket * 0.99f,
                "patch $patch centerpoint $harm too close to neighbour " +
                    "(low Δ=$distLow, high Δ=$distHigh, half-bucket=$halfBucket)",
            )
        }
    }
}
