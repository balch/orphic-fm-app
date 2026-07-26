package org.balch.orpheus.features.pulsar.models

/**
 * Kotlin mirror of the C++ wah-anomaly eligibility predicate `wah_anomaly_track_eligible`
 * (orpheus_unit_pulsar.h): the anomaly is a per-track insert that runs only on a melodic
 * track reading the LEAD channel that is not the bass.
 *
 * This is a **contract pin, not production logic**. The engine computes the mask itself
 * from the `track_role_*` / `track_lick_source_*` banks PulsarViewModel marshals, and
 * Kotlin deliberately does not validate eligibility at construction time: a vibe with no
 * eligible lead is legal, it just never fires the wah. Shared by the unit-level assertions
 * in `WahAnomalyTest` and the whole-catalog sweep in `AllVibesWahEligibilityTest` so the
 * mirror exists exactly once.
 */
internal object WahEligibility {

    /** Track 3 is the bass channel on every vibe. Mirrors C++ `kBassTrack`. */
    const val BASS_TRACK_INDEX = 3

    fun isEligible(index: Int, role: TrackRole): Boolean =
        index != BASS_TRACK_INDEX &&
            role is TrackRole.Melodic &&
            role.lickSource == LickSource.LEAD

    fun eligibleTracks(vibe: Vibe): Set<Int> =
        vibe.tracks.indices.filter { isEligible(it, vibe.tracks[it].role) }.toSet()
}
