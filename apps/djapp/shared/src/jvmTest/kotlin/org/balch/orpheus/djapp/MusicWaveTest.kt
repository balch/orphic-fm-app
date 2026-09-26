package org.balch.orpheus.djapp

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.StoryBeat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicWaveTest {
    private val red = Color(1f, 0f, 0f)
    private val blue = Color(0f, 0f, 1f)
    private val green = Color(0f, 1f, 0f)
    private val fallback = Color(0f, 1f, 1f)
    private val colors = listOf(red, blue, green)

    private fun assertColor(expected: Color, actual: Color, tolerance: Float = 1e-3f) {
        assertEquals(expected.red, actual.red, tolerance, "red of $actual")
        assertEquals(expected.green, actual.green, tolerance, "green of $actual")
        assertEquals(expected.blue, actual.blue, tolerance, "blue of $actual")
    }

    @Test
    fun silenceFallsBack() = assertColor(fallback, blendTrackColors(FloatArray(3), colors, fallback))

    @Test
    fun aLoneTrackWearsItsOwnColour() = assertColor(blue, blendTrackColors(floatArrayOf(0f, 0.4f, 0f), colors, fallback))

    @Test
    fun aDominantTrackLeads() {
        val blend = blendTrackColors(floatArrayOf(0.9f, 0.1f, 0.1f), colors, fallback)
        assertTrue(blend.red > 0.95f, "the loud red track should lead: $blend")
    }

    @Test
    fun equalLevelsAverage() =
        assertColor(Color(0.5f, 0f, 0.5f), blendTrackColors(floatArrayOf(0.6f, 0.6f, 0f), colors, fallback))

    private fun beat(level: Float, atMs: Long, forMs: Long = 1_000L, energy: FloatArray = floatArrayOf(level, 0f, 0f)) =
        StoryBeat(level, energy, positionMs = atMs, durationMs = forMs)

    /** Back-to-back one-second beats from 0, laid out against their own end. */
    private fun story(vararg levels: Float) =
        SongStory(levels.mapIndexed { i, level -> beat(level, i * 1_000L) }, elapsedMs = levels.size * 1_000L)

    /** The band's own fills, into fresh arrays of [buckets]. */
    private fun levelsOf(story: SongStory, buckets: Int, elapsedMs: Long = story.elapsedMs): List<Float> =
        FloatArray(buckets).also { storyLevelsInto(it, buckets, story, elapsedMs) }.toList()

    private fun coloursOf(story: SongStory, buckets: Int, elapsedMs: Long = story.elapsedMs): List<Color> {
        val packed = LongArray(buckets)
        storyColorsInto(packed, FloatArray(buckets * colors.size), buckets, story, colors, fallback, elapsedMs)
        return packed.map { Color(it.toULong()) }
    }

    @Test
    fun moreBeatsThanBucketsKeepTheLoudestOfEach() {
        assertEquals(listOf(0.4f, 0.9f, 0.5f), levelsOf(story(0.1f, 0.4f, 0.3f, 0.9f, 0.2f, 0.5f), buckets = 3))
    }

    // A beat wider than a bucket fills every bucket it covers, so a long song never combs.
    @Test
    fun aBeatFillsEveryBucketItCovers() {
        assertEquals(List(4) { 0.1f } + List(4) { 0.4f } + List(4) { 0.3f }, levelsOf(story(0.1f, 0.4f, 0.3f), buckets = 12))
    }

    @Test
    fun beatsLandWhereTheyPlayed() {
        val story = SongStory(listOf(beat(0.5f, atMs = 0L), beat(0.8f, atMs = 5_000L)), elapsedMs = 10_000L)
        assertEquals(listOf(0.5f, 0f, 0f, 0f, 0f, 0.8f, 0f, 0f, 0f, 0f), levelsOf(story, buckets = 10))
    }

    // An unwatched stretch (the app in the background) records nothing, and lies flat where it was.
    @Test
    fun anUnrecordedStretchLiesFlatInItsPlace() {
        val before = (0 until 6).map { beat(0.6f, atMs = it * 500L, forMs = 500L) }
        val after = (0 until 4).map { beat(0.6f, atMs = 8_000L + it * 500L, forMs = 500L) }
        val levels = levelsOf(SongStory(before + after, elapsedMs = 10_000L), buckets = 20)
        assertEquals(List(6) { 0.6f } + List(10) { 0f } + List(4) { 0.6f }, levels)
    }

    // The song position ticks a loop-cycle at a time; beats of the cycle in flight wait for it.
    @Test
    fun beatsPastThePlayheadWaitForIt() {
        val story = SongStory(listOf(beat(0.5f, atMs = 9_500L, forMs = 500L)), elapsedMs = 9_000L)
        assertTrue(levelsOf(story, buckets = 9).all { it == 0f })
        assertTrue(levelsOf(SongStory(listOf(beat(0.5f, atMs = 0L)), elapsedMs = 0L), buckets = 4).all { it == 0f })
    }

    // The smoothed playhead runs past the story's last position tick: the cycle in flight shows up to it, in place.
    @Test
    fun aLaterPlayheadShowsTheCycleInFlightWhereItPlayed() {
        val story = SongStory(listOf(beat(0.5f, atMs = 0L), beat(0.8f, atMs = 9_500L, forMs = 500L)), elapsedMs = 9_000L)
        assertEquals(listOf(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.8f), levelsOf(story, buckets = 10, elapsedMs = 10_000L))
        assertColor(red, coloursOf(story, buckets = 10, elapsedMs = 10_000L)[9])
    }

    @Test
    fun bucketColoursBlendTheirBeatsEnergy() {
        val beats = SongStory(
            listOf(
                beat(0.5f, 0L, energy = floatArrayOf(1f, 0f, 0f)), beat(0.5f, 1_000L, energy = floatArrayOf(1f, 0f, 0f)),
                beat(0.5f, 2_000L, energy = floatArrayOf(0f, 1f, 0f)), beat(0.5f, 3_000L, energy = FloatArray(3)),
            ),
            elapsedMs = 6_000L,
        )
        val bucketColors = coloursOf(beats, buckets = 3)
        assertColor(red, bucketColors[0])
        assertColor(blue, bucketColors[1])
        // Nothing was recorded in the last bucket.
        assertColor(fallback, bucketColors[2])
    }

    @Test
    fun zipIsDarkThroughTheRest() {
        (ZipPassMillis until ZipPassMillis + ZipRestMillis step 50).forEach { t ->
            (0..10).forEach { assertEquals(0f, zipIntensity(it / 10f, t.toLong()), "lit at ${it / 10f}, ${t}ms") }
        }
    }

    @Test
    fun zipPeaksAtItsBandCentre() {
        val t = ZipPassMillis / 2L
        val centre = zipCentre(t)
        assertEquals(1f, zipIntensity(centre, t), 1e-4f)
        listOf(-0.1f, -0.05f, 0.05f, 0.1f).forEach { d ->
            assertTrue(zipIntensity(centre + d, t) < zipIntensity(centre, t), "${centre + d} outshone the centre")
        }
    }

    @Test
    fun zipIsDarkOutsideItsBand() {
        val t = ZipPassMillis / 2L
        val centre = zipCentre(t)
        assertEquals(0f, zipIntensity(centre + 0.3f, t))
        assertEquals(0f, zipIntensity(centre - 0.3f, t))
    }

    @Test
    fun zipBandTravelsFromStartToPlayhead() {
        val centres = (0..ZipPassMillis step 40).map { zipCentre(it.toLong()) }
        assertEquals(0f, centres.first(), 1e-6f)
        assertEquals(1f, centres.last(), 1e-6f)
        centres.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "the band turned back: $a -> $b") }
        assertEquals(zipCentre(300L), zipCentre(300L + ZipPassMillis + ZipRestMillis), 1e-6f)
    }

    @Test
    fun loudnessRisesFastAndFallsSlowly() {
        val up = smoothLevel(current = 0f, target = 1f, dtMs = 30f)
        val down = smoothLevel(current = 1f, target = 0f, dtMs = 30f)
        assertTrue(up > 0.6f, "a 30 ms attack should be mostly there: $up")
        assertTrue(down > 0.85f, "a 250 ms release should barely move in 30 ms: $down")
        assertEquals(0.5f, smoothLevel(current = 0.5f, target = 0.5f, dtMs = 16f))
        assertTrue(smoothLevel(current = 0f, target = 1f, dtMs = 10_000f) <= 1f)
    }

    private fun wrappedGap(a: Float, b: Float) = abs((a - b + 0.5f).mod(1f) - 0.5f)

    @Test
    fun withoutATempoTheWaveTravelsAWavelengthEveryPeriod() {
        var travel = 0f
        repeat(30) { travel = followBeat(travel, dtMs = 20f, beat = Float.NaN, msPerBeat = 0f) }
        assertEquals(0.5f, travel, 1e-4f)
    }

    @Test
    fun theTravelLocksOntoTheBeat() {
        var travel = 0.6f
        var beat = 0f
        repeat(60) {
            beat = (beat + 16f / 500f).mod(1f)
            travel = followBeat(travel, dtMs = 16f, beat = beat, msPerBeat = 500f)
        }
        assertTrue(wrappedGap(beat, travel) < 0.01f, "travel $travel never caught the beat $beat")
    }

    // A resume or a tempo lock pulls the carrier over; it must not snap.
    @Test
    fun theTravelNeverJumpsOntoTheBeat() {
        val after = followBeat(0.2f, dtMs = 16f, beat = 0.7f, msPerBeat = 500f)
        assertTrue(after - 0.2f in 0f..0.15f, "one frame moved the travel from 0.2 to $after")
    }

    @Test
    fun theBeatCarriesOnAtMostAStepPastItsLastPulse() {
        assertEquals(0.3f, extrapolatedBeatPhase(beatPhase = 0.3f, msSincePulse = 0f, msPerBeat = 500f), 1e-6f)
        assertEquals(0.4f, extrapolatedBeatPhase(beatPhase = 0.3f, msSincePulse = 50f, msPerBeat = 500f), 1e-6f)
        assertEquals(0.55f, extrapolatedBeatPhase(beatPhase = 0.3f, msSincePulse = 5_000f, msPerBeat = 500f), 1e-6f)
    }
}
