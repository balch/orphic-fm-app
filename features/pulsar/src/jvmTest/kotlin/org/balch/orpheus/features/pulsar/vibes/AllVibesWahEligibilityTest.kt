package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.models.WahEligibility
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whole-catalog authoring guard for the wah anomaly.
 *
 * The wah is a per-track insert on eligible LEAD tracks with **no whole-mix fallback**, so a
 * vibe that declares a [WahAnomaly] without a single eligible track is dead config: the
 * anomaly can never fire, and it reads to the player as "my wah never happens". That is only
 * worth catching if the check sees *every* vibe, hence [VibeCatalogScan].
 */
class AllVibesWahEligibilityTest {

    @Test
    fun everyVibeDeclaringTheWahHasAnEligibleLead() {
        val providers = VibeCatalogScan.allProviders()

        val declaring = providers.filter { p -> p.vibe.anomalies.any { it is WahAnomaly } }
        assertTrue(
            declaring.isNotEmpty(),
            "no shipped vibe declares a WahAnomaly, so this sweep passes vacuously. If the " +
                "anomaly was retired, delete this test with it.",
        )

        val noEligibleLead = declaring
            .filter { WahEligibility.eligibleTracks(it.vibe).isEmpty() }
            .map { it.name }
        assertTrue(
            noEligibleLead.isEmpty(),
            "$noEligibleLead declare a WahAnomaly but have no eligible lead track, so it can " +
                "never fire. A track qualifies only when its role is Melodic, its lickSource is " +
                "LEAD, and it is not track ${WahEligibility.BASS_TRACK_INDEX} (the bass). Give " +
                "the vibe an eligible lead or drop the anomaly.",
        )
    }
}
