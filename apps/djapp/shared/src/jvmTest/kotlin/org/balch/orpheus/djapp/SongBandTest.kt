package org.balch.orpheus.djapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.pulsar.PulsarTrackColors
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.StoryBeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The band's bar geometry and bucketing, off any scene. ./gradlew :apps:djapp:shared:jvmTest --tests '*SongBandTest*' */
class SongBandTest {
    // The band inside a 1280dp and a 960dp window, less the bar's 20dp sides, at density 1.
    private val wide = Density(1f).songBandLayout(1240f)
    private val narrow = Density(1f).songBandLayout(920f)

    @Test
    fun theBarsFillTheBandAtAFiveDpPitch() {
        assertEquals(248, wide.count)
        assertEquals(184, narrow.count)
        assertEquals(5f, wide.slot, 1e-4f)
        assertEquals(920f / 184, narrow.slot, 1e-4f)
    }

    @Test
    fun eachBarSitsCentredInItsStretchOfTheSong() {
        assertEquals(2.5f, wide.centerX(0), 1e-4f)
        assertEquals(1237.5f, wide.centerX(wide.count - 1), 1e-4f)
        // Bar k's centre is (k + ½) / count along the song.
        assertEquals(wide.width * 100.5f / wide.count, wide.centerX(100), 1e-3f)
    }

    @Test
    fun thePlayheadReachesABarAtItsCentre() {
        assertEquals(0, wide.reached(0f))
        assertEquals(0, wide.reached(2.4f))
        assertEquals(1, wide.reached(2.5f))
        assertEquals(124, wide.reached(620f))
        assertEquals(wide.count, wide.reached(1240f))
        assertEquals(wide.count, wide.reached(5_000f))
    }

    @Test
    fun aBarRisesWithItsLoudnessAndNoFurther() {
        assertEquals(0f, wide.height(0f))
        assertEquals(24f, wide.height(1f), 1e-4f)
        assertEquals(24f, wide.height(1.4f), 1e-4f)
        assertEquals(0f, wide.height(-0.2f))
        assertTrue(wide.height(0.9f) > 2.5f * wide.height(0.3f), "a drop does not stand well above a breakdown")
        // The tallest bar's round cap stays inside the 36dp band, and the playhead dot under the baseline does too.
        assertTrue(wide.baseline - wide.height(1f) - wide.barWidth / 2 >= 0f)
        assertTrue(wide.baseline + PlayheadRadius.value <= SongBandHeight.value)
    }

    // A static band redraws when the playhead reaches another bar, and not in between.
    @Test
    fun theReachedBarMovesOnlyAtABarsCentre() {
        assertEquals(-1, wide.reachedAt(null))
        assertEquals(124, wide.reachedAt(620f / 1240f))
        assertEquals(124, wide.reachedAt(622.4f / 1240f))
        assertEquals(125, wide.reachedAt(622.6f / 1240f))
    }

    // 60 half-second beats, the first 20 quiet in texture pink, the last 40 loud in kick red.
    private val story = SongStory(
        List(60) { i ->
            val drop = i >= 20
            StoryBeat(if (drop) 0.9f else 0.3f, FloatArray(8).also { it[if (drop) 0 else 6] = 1f }, i * 500L, 500L)
        },
        elapsedMs = 30_000L,
    )

    /** The band's shape with the playhead at [playX], from a cache of its own: a cache's next shape reuses its arrays. */
    private fun shapeAt(story: SongStory, playX: Float, elapsedMs: Long) = SongBandCache().shapeFor(story, wide, playX, elapsedMs)

    @Test
    fun aBreakdownReadsLowAndADropTall() {
        // 30 s at the band's half-way point: 124 bars, the first third quiet.
        val shape = shapeAt(story, 620f, 30_000L)
        assertEquals(124, shape.bars)
        assertEquals(0.3f, shape.levels[10])
        assertEquals(0.9f, shape.levels[100])
        assertEquals(PulsarTrackColors[6], shape.color(10, Color.Cyan))
        assertEquals(PulsarTrackColors[0], shape.color(100, Color.Cyan))
    }

    @Test
    fun aBarKeepsItsStretchOfSongAsThePlayheadMoves() {
        // The same song at 50 ms per px: bar 30 covers 7.5-7.75 s whether the playhead is at 400 or 600 px.
        val early = shapeAt(story, 403f, 20_150L)
        val late = shapeAt(story, 600f, 30_000L)
        for (k in 0 until early.bars) assertEquals(early.levels[k], late.levels[k], "bar $k moved with the playhead")
    }

    // The accent is not built into the shape: an unheard stretch takes whichever the draw is wearing.
    @Test
    fun anUnheardStretchIsADimmedDot() {
        val gap = SongStory(story.beats.filter { it.positionMs < 5_000L }, elapsedMs = 30_000L)
        val shape = shapeAt(gap, 600f, 30_000L)
        assertEquals(0f, shape.levels[100])
        assertFalse(shape.heard(100))
        assertEquals(Color.Cyan.copy(alpha = UnheardAlpha), shape.color(100, Color.Cyan))
        assertEquals(Color.Magenta.copy(alpha = UnheardAlpha), shape.color(100, Color.Magenta))
    }

    @Test
    fun theShapeIsRebuiltOnlyWhenABeatLandsOrThePlayheadPassesABar() {
        val cache = SongBandCache()
        val first = cache.shapeFor(story, wide, 601f, 30_050L)
        assertSame(first, cache.shapeFor(story, wide, 602.4f, 30_120L), "rebuilt inside one bar")
        assertNotSame(first, cache.shapeFor(story, wide, 602.6f, 30_130L), "never rebuilt past a bar's centre")
        val grown = SongStory(story.beats + StoryBeat(1f, FloatArray(8), 30_000L, 500L), elapsedMs = 30_500L)
        val current = cache.shapeFor(story, wide, 602.6f, 30_130L)
        assertNotSame(current, cache.shapeFor(grown, wide, 602.6f, 30_130L), "a new beat never showed")
    }

    // A loop-cycle's progress tick copies the story for its elapsed time alone: the beats, and the bars, stand.
    @Test
    fun aProgressTickAloneNeverRebuildsTheShape() {
        val cache = SongBandCache()
        val first = cache.shapeFor(story, wide, 601f, 30_050L)
        assertSame(first, cache.shapeFor(story.copy(elapsedMs = 30_100L), wide, 602f, 30_100L))
    }

    @Test
    fun nothingReachedIsNoBars() {
        assertEquals(0, shapeAt(story, 0f, 0L).bars)
        assertEquals(0, shapeAt(story, 1f, 50L).bars)
    }
}
