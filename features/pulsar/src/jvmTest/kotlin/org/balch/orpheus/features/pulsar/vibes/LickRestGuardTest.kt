package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.anonmalies.LickAnomaly
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.VibeProvider
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A negative degree is a real note below the root since rests became [Rest]. Every shipped
 * vibe's below-root steps must be deliberate, so a stray one (a rest written the old way)
 * fails here instead of sounding as a surprise note.
 */
class LickRestGuardTest {

    /** (vibe name, slot, step index) of below-root notes someone meant to write. */
    private val INTENDED_BELOW_ROOT: Set<Triple<String, String, Int>> = emptySet()

    private fun licksOf(provider: VibeProvider): List<Pair<String, Lick>> {
        val vibe = provider.vibe
        return buildList {
            vibe.lick?.let { add("lick" to it) }
            vibe.bassLine?.let { add("bassLine" to it) }
            vibe.lickRotation?.pool?.forEachIndexed { i, l -> add("pool[$i]" to l) }
            vibe.tracks.forEachIndexed { t, track ->
                track.soloBehavior?.melodicOverride?.lick?.let { add("track[$t].solo" to it) }
            }
            vibe.anomalies.filterIsInstance<LickAnomaly>().forEachIndexed { i, a -> add("anomaly[$i]" to a.lick) }
        }
    }

    @Test
    fun no_unlisted_below_root_notes() {
        val providers = VibeCatalogScan.allProviders(subpackages = true)
        val offenders = providers.flatMap { p ->
            licksOf(p).flatMap { (slot, lick) ->
                lick.steps.withIndex()
                    .filter { (i, s) -> !s.rest && s.scaleDegree < 0 && Triple(p.name.value, slot, i) !in INTENDED_BELOW_ROOT }
                    .map { (i, s) -> "${p.name.value} $slot step $i (degree ${s.scaleDegree})" }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "below-root notes that look like old-style rests; use Rest(beats), or list them in " +
                "INTENDED_BELOW_ROOT if deliberate:\n" + offenders.joinToString("\n"),
        )
    }
}
