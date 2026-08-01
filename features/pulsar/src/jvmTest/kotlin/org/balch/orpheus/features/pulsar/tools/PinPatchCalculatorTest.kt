package org.balch.orpheus.features.pulsar.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Protects the DX patch-quantization contract that bridges Kotlin authoring
 * (`OrpheusEngine.harmonics` ∈ [0,1]) to C++ SixOp patch indices.
 *
 * [PatchQuantizer] below is a faithful port of the real path, not an
 * approximation:
 *
 *  - `six_op_engine.cc:76`  — `patch_index_quantizer_.Init(32, 0.005f, false)`
 *  - `six_op_engine.cc:109` — `Process(parameters.harmonics * 1.02f)`
 *  - `stmlib/dsp/hysteresis_quantizer.h:101` — `HysteresisQuantizer2::Process`
 *
 * Because `symmetric = false`, the quantizer sets `scale_ = 32` and
 * `offset_ = -0.5`, then truncates:
 *
 *     q = int(harmonics * 1.02 * 32 - 0.5 + sign * 0.005 + 0.5)
 *       = int(harmonics * 32.64 ± 0.005)
 *
 * So the map is a **floor**, not a round: bucket N spans
 * `harmonics ∈ [N / 32.64, (N + 1) / 32.64)` and its centre — the value with
 * the largest margin to either edge — is:
 *
 *     center(N) = (N + 0.5) / (32 * 1.02)
 *
 * The `+ 0.5` is load-bearing. `N / (32 * 1.02)` is the bucket's *lower edge*,
 * where `value` lands exactly on `N - 0.5`; the hysteresis then decides the
 * result, and it resolves to `N - 1` for every prior patch below `N`. That
 * uncentred form round-trips 1 of 32 indices; the centred form round-trips
 * 32 of 32 from any prior state. [lowerEdge_does_not_round_trip_which_is_why_centres_are_centred]
 * pins that difference so the old formula cannot be reintroduced.
 */
class PinPatchCalculatorTest {

    /**
     * Port of `stmlib::HysteresisQuantizer2` as `SixOpEngine` configures and
     * drives it. Stateful on purpose: the hysteresis branch reads the
     * previously quantized patch, so a correct authoring value must survive
     * *any* prior state — the engine reuses one quantizer for the whole
     * session, and a track's prior patch is whatever the last vibe left.
     */
    private class PatchQuantizer {
        private var quantizedValue = 0

        /** Mirrors the engine call site: `Process(harmonics * 1.02f)`. */
        fun process(harmonics: Float): Int {
            // value *= scale_ (32); value += offset_ (-0.5); base is 0.
            val value = harmonics * 1.02f * 32f - 0.5f
            val hysteresisSign = if (value > quantizedValue.toFloat()) -1.0f else 1.0f
            // static_cast<int> truncates toward zero, as does Float.toInt().
            val q = (value + hysteresisSign * 0.005f + 0.5f).toInt().coerceIn(0, 31)
            quantizedValue = q
            return q
        }

        /** Drive internal state to [patch] so a test can control the hysteresis branch. */
        fun seedTo(patch: Int) {
            val seeded = process(center(patch))
            check(seeded == patch) { "seed failed: wanted $patch, quantizer settled on $seeded" }
        }
    }

    private fun patchIndex(harmonics: Float): Int = PatchQuantizer().process(harmonics)

    companion object {
        /** The documented authoring formula. Must match every other site that states it. */
        fun center(patch: Int): Float = (patch + 0.5f) / (32f * 1.02f)

        /** The pre-fix formula, kept only so a test can prove it is wrong. */
        fun lowerEdge(patch: Int): Float = patch / (32f * 1.02f)
    }

    @Test
    fun patchIndex_clamps_at_endpoints() {
        assertEquals(0, patchIndex(0.0f))
        assertEquals(0, patchIndex(-0.1f))
        // 1.0f * 32.64f - 0.5f + 0.5f = 32.64 → truncates to 32 → clamped to 31
        assertEquals(31, patchIndex(1.0f))
        assertEquals(31, patchIndex(1.5f))
    }

    @Test
    fun center_round_trips_for_every_patch_index() {
        for (patch in 0..31) {
            val harm = center(patch)
            val recovered = patchIndex(harm)
            assertEquals(
                patch, recovered,
                "center($patch) = $harm should quantize to $patch (got $recovered)",
            )
        }
    }

    /**
     * The load-bearing case. A fresh quantizer starts at 0, but the engine's is
     * long-lived: whatever patch the track played last biases the hysteresis. A
     * correct authoring value must land on its patch from *every* prior state.
     */
    @Test
    fun center_round_trips_from_any_previously_loaded_patch() {
        for (patch in 0..31) {
            for (previous in 0..31) {
                val quantizer = PatchQuantizer()
                quantizer.seedTo(previous)
                val recovered = quantizer.process(center(patch))
                assertEquals(
                    patch, recovered,
                    "center($patch) = ${center(patch)} should quantize to $patch " +
                        "after previously loading patch $previous (got $recovered)",
                )
            }
        }
    }

    /**
     * Regression guard for the bug this test file was rewritten to catch: the
     * uncentred `N / (32 * 1.02)` sits exactly on bucket N's lower edge, so the
     * hysteresis resolves it down to `N - 1` whenever the previous patch was
     * lower — which is the common case, including a freshly initialised engine.
     */
    @Test
    fun lowerEdge_does_not_round_trip_which_is_why_centres_are_centred() {
        val roundTripped = (0..31).count { patchIndex(lowerEdge(it)) == it }
        assertEquals(
            1, roundTripped,
            "the uncentred formula should only ever land on patch 0; if this changed, " +
                "the quantizer model or its constants moved",
        )
        // Spot-check the shape of the failure: it is a consistent off-by-one.
        for (patch in 1..31) {
            assertEquals(
                patch - 1, patchIndex(lowerEdge(patch)),
                "lowerEdge($patch) should collapse into the patch below",
            )
            assertNotEquals(lowerEdge(patch), center(patch))
        }
    }

    /**
     * Shipped vibes were tuned by ear, so their harmonics values are correct by
     * definition — they load the patch their author liked. These assertions pin
     * *which* patch that actually is, which is not always the index the vibe's
     * comment claims. Values here must never be "fixed"; only the expectations
     * may move, and only if the engine's quantizer changes.
     */
    @Test
    fun shipped_vibe_anchors_resolve_to_expected_patches() {
        // DogHouseVibe track 4 EDM / SwampSwaggerVibe (comments say idx 18)
        assertEquals(17, patchIndex(0.551f))
        // ArmyStompVibe lead EDM (comment says idx 5)
        assertEquals(4, patchIndex(0.153f))
        // ArmyStompVibe lead Space
        assertEquals(1, patchIndex(0.031f))
        // ArmyStompVibe mallet
        assertEquals(17, patchIndex(0.521f))
    }

    /**
     * BellTollsVibe and DailyDriftVibe both pin `0.582f`, which sits within
     * 0.0002 of bucket 19's lower edge (0.58211). It is the one shipped anchor
     * whose result depends on the previously loaded patch, so it is pinned
     * separately rather than folded into the stable set above.
     */
    @Test
    fun anchor_0582_is_boundary_unstable_across_prior_state() {
        val fromBelow = PatchQuantizer().also { it.seedTo(0) }.process(0.582f)
        val fromAbove = PatchQuantizer().also { it.seedTo(31) }.process(0.582f)
        assertEquals(18, fromBelow, "0.582f resolves down when the prior patch was lower")
        assertEquals(19, fromAbove, "0.582f resolves up when the prior patch was higher")
    }

    @Test
    fun centers_are_half_a_bucket_from_both_edges() {
        val bucketWidth = 1f / (32f * 1.02f)
        val halfBucket = bucketWidth * 0.5f
        for (patch in 0..31) {
            val harm = center(patch)
            val distLow = harm - lowerEdge(patch)
            val distHigh = lowerEdge(patch + 1) - harm
            assertTrue(
                distLow >= halfBucket * 0.99f && distHigh >= halfBucket * 0.99f,
                "patch $patch centre $harm is not mid-bucket " +
                    "(low Δ=$distLow, high Δ=$distHigh, half-bucket=$halfBucket)",
            )
        }
    }
}
