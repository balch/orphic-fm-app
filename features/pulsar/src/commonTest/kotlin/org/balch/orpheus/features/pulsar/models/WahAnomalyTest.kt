package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.vibes.OdysseusLoreVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WahAnomalyTest {
    private val json = Json { encodeDefaults = true }

    // The eligibility mirror lives in WahEligibility so this file and the whole-catalog
    // sweep in AllVibesWahEligibilityTest (jvmTest) can't drift apart.
    private val bassTrackIndex = WahEligibility.BASS_TRACK_INDEX

    private fun wahEligible(index: Int, role: TrackRole): Boolean =
        WahEligibility.isEligible(index, role)

    private fun eligibleWahTracks(vibe: Vibe): Set<Int> = WahEligibility.eligibleTracks(vibe)

    @Test
    fun roundTripsThroughJson() {
        val w = WahAnomaly(
            probability = 0.2f, durationBarsMin = 1.5f, durationBarsMax = 6f,
            voice = WahParams(
                rateDivision = 4f, depth = 0.8f, resonanceQ = 5f,
                centerHz = 600f, sweepOctaves = 2f, wet = 0.7f,
            ),
        )
        val decoded = json.decodeFromString(
            WahAnomaly.serializer(),
            json.encodeToString(WahAnomaly.serializer(), w)
        )
        assertEquals(w, decoded)
    }

    @Test
    fun rejectsOutOfRangeProbability() {
        assertFailsWith<IllegalArgumentException> { WahAnomaly(probability = 1.5f) }
    }

    @Test
    fun rejectsInvertedDurationRange() {
        assertFailsWith<IllegalArgumentException> {
            WahAnomaly(durationBarsMin = 4f, durationBarsMax = 2f)
        }
    }

    @Test
    fun defaultsAreShipValues() {
        val w = WahAnomaly()
        assertEquals(0.03f, w.probability)
        assertEquals(2f, w.durationBarsMin)
        assertEquals(4f, w.durationBarsMax)
        assertEquals(WahParams(), w.voice)
    }

    @Test
    fun eligibilityIsMelodicLeadTracksOnly() {
        // The anomaly filters lead channels, never the whole mix. All four exclusions matter:
        // drums and chords must stay dry, the bass line must stay dry whether it is excluded by
        // its lickSource or by sitting on the bass track index.
        assertTrue(wahEligible(4, TrackRole.Melodic()), "a plain melodic lead is eligible")
        assertTrue(
            wahEligible(4, TrackRole.Melodic(lickMode = LickMode.Fill, wahLick = true)),
            "a lead with a standing wah is still eligible; the anomaly takes that filter over",
        )
        assertFalse(wahEligible(0, TrackRole.Percussive), "drums must never wah")
        assertFalse(wahEligible(5, TrackRole.Chordal()), "chordal tracks must never wah")
        assertFalse(
            wahEligible(4, TrackRole.Melodic(lickSource = LickSource.BASS)),
            "a melodic track rendering the bass line channel must never wah",
        )
        assertFalse(
            wahEligible(bassTrackIndex, TrackRole.Melodic()),
            "the bass track index is excluded even when its role looks like a lead",
        )
    }

    // NOTE the "every wah-declaring vibe has an eligible lead" guard is NOT here. It used to
    // name three vibes by hand, which meant it enforced nothing about the fourth: a new vibe
    // declaring the anomaly with no eligible lead would ship silently, which is the one case
    // the guard exists to catch. It now lives in AllVibesWahEligibilityTest (jvmTest), which
    // discovers every VibeProvider on the classpath instead of trusting a list.

    @Test
    fun odysseusLoreIsTheShippedTakeoverCase() {
        // The takeover path (anomaly displaces a track's standing wah instead of stacking a
        // second bandpass) has exactly one shipped vibe exercising it. If this breaks, that path
        // loses its ear test.
        val vibe = OdysseusLoreVibe().vibe
        assertNotNull(vibe.lickWah, "the takeover path needs a standing lickWah voice to take over")

        val eligible = eligibleWahTracks(vibe)
        assertEquals(
            // 4 = wah lead, 6 = granular swirl, 7 = harmony guitar. The bass (3) is excluded
            // by the bass-channel clause even though it now carries its own standing wah.
            setOf(4, 6, 7), eligible,
            "these are the tracks Odysseus Lore's wah anomaly actually filters. If you changed a " +
                "track's role or lickSource, update this expectation and re-ear-test.",
        )

        val takeover = eligible.filter { (vibe.tracks[it].role as TrackRole.Melodic).wahLick }
        assertEquals(
            listOf(4), takeover,
            "track 4 is the wah lead: eligible AND carrying the standing insert, so the anomaly " +
                "takes its filter over rather than adding one",
        )
    }

    @Test
    fun fieldsConstantMatchesSerializableArity() {
        // WahParams.FIELDS is the stride PulsarViewModel writes the per-track lick-wah bank
        // with, and C++ load_vibe reads it back at 1 + t * kLickWahFields. Add a seventh field
        // to WahParams and forget the constant and nothing fails: Kotlin keeps writing stride
        // 6 while carrying 7 values, so every track past 0 gets voiced from its neighbour's
        // floats. Anchor the constant to the serializable arity so the omission is a red test.
        // The C++ half is pinned by the sizeof(orpheus::WahParams) static_assert at the top of
        // orpheus_unit_pulsar.cpp.
        val descriptor = WahParams.serializer().descriptor
        assertEquals(
            descriptor.elementsCount, WahParams.FIELDS,
            "WahParams gained or lost a field without updating FIELDS. Update FIELDS, " +
                "OrpheusEngine::kLickWahFields, the PulsarViewModel marshal and the " +
                "load_vibe unpack together, or the bank silently scrambles.",
        )
        // Order is as load-bearing as the count: the bank is positional on both sides.
        assertEquals(
            listOf("rateDivision", "depth", "resonanceQ", "centerHz", "sweepOctaves", "wet"),
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) },
            "the lick-wah bank is positional. Reordering WahParams reorders what C++ reads.",
        )
    }

    @Test
    fun voiceDefaultsMirrorCppWahParams() {
        // These MUST match orpheus::WahParams (orpheus_wah_core.h) and the wah_data
        // marshal defaults in PulsarViewModel — the bank order is a hard contract.
        val v = WahParams()
        assertEquals(8f, v.rateDivision)
        assertEquals(1f, v.depth)
        assertEquals(3f, v.resonanceQ)
        assertEquals(800f, v.centerHz)
        assertEquals(1.3f, v.sweepOctaves)
        assertEquals(1f, v.wet)
    }
}
