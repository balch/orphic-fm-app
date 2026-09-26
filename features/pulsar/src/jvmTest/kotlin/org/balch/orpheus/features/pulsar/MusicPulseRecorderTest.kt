package org.balch.orpheus.features.pulsar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class MusicPulseRecorderTest {
    private val viz = MutableStateFlow(PulsarVizData())
    private val engine = object : SongEndingStubSynthEngine() {
        override val pulsarVizFlow = viz
    }
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = makeAppCoroutineScope(dispatcher)
    private val session = PulsarSession(engine, scope, FixturesDispatchers(dispatcher))
    // A slow machine must not open a gap between samples: time moves only when a test moves it.
    private val time = TestTimeSource()
    private val recorder = MusicPulseRecorder(engine, session, scope, FixturesDispatchers(dispatcher), time)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun play(steps: IntRange, level: Float) = steps.forEach { step ->
        time += 20.milliseconds
        viz.value = PulsarVizData(playheads = IntArray(8) { step % 16 }, trackLevels = FloatArray(8) { if (it == 0) level else 0f })
    }

    // Steps 0..3 close no beat on their own; a stall in the injected clock ends the pending one.
    @Test
    fun aStallInTheClockClosesThePendingBeat() {
        session.updateProgress(PlaybackProgress(positionMs = 30_000L, durationMs = 200_000L))
        play(0..2, 0.5f)
        assertEquals(0, recorder.songStory.value.beats.size)
        time += 5.seconds
        play(3..3, 0.5f)
        assertEquals(1, recorder.songStory.value.beats.size)
    }

    @Test
    fun beatsAppendToTheStoryAsTheyClose() {
        recorder.holdPulse()
        session.updateProgress(PlaybackProgress(positionMs = 30_000L, durationMs = 200_000L))
        play(0..8, 0.5f)
        val story = recorder.songStory.value
        assertEquals(2, story.beats.size)
        assertEquals(30_000L, story.elapsedMs)
        assertTrue(story.beats.all { it.positionMs >= 30_000L }, "beats not stamped with the song position: ${story.beats.map { it.positionMs }}")
        assertEquals(0.5f, recorder.pulse.value.trackLevels[0])
    }

    // TV hardware draws no live wave and holds no pulse: the story still records, the pulse is never built.
    @Test
    fun withNoHolderThePulseIsSkippedButTheStoryRecords() {
        session.updateProgress(PlaybackProgress(positionMs = 30_000L, durationMs = 200_000L))
        play(0..8, 0.5f)
        assertEquals(2, recorder.songStory.value.beats.size)
        assertEquals(MusicPulse.SILENT, recorder.pulse.value)
        val hold = recorder.holdPulse()
        play(9..9, 0.5f)
        assertEquals(0.5f, recorder.pulse.value.trackLevels[0])
        hold.dispose()
        hold.dispose()
        play(10..10, 0.7f)
        assertEquals(0.5f, recorder.pulse.value.trackLevels[0], "a released hold kept the pulse live")
        recorder.holdPulse()
        play(11..11, 0.7f)
        assertEquals(0.7f, recorder.pulse.value.trackLevels[0], "a second dispose of one handle released another holder")
    }

    // A collector reads the pulse as a holder does: live while it collects, skipped once it stops.
    @Test
    fun aCollectorKeepsThePulseLive() {
        session.updateProgress(PlaybackProgress(positionMs = 30_000L, durationMs = 200_000L))
        val seen = mutableListOf<MusicPulse>()
        val collector = scope.launch { recorder.pulse.collect { seen += it } }
        play(0..0, 0.5f)
        assertEquals(0.5f, recorder.pulse.value.trackLevels[0], "a collected pulse went stale")
        assertEquals(0.5f, seen.last().trackLevels[0], "the collector never saw the new pulse")
        collector.cancel()
        play(1..1, 0.7f)
        assertEquals(0.5f, recorder.pulse.value.trackLevels[0], "a cancelled collector kept the pulse live")
    }

    // With no seek bar there is nowhere to put a beat.
    @Test
    fun withoutProgressNothingIsRecorded() {
        play(0..8, 0.5f)
        assertEquals(0, recorder.songStory.value.beats.size)
    }

    @Test
    fun aNewSongStartsAnEmptyStory() {
        session.updateProgress(PlaybackProgress(positionMs = 60_000L, durationMs = 200_000L))
        play(0..12, 0.5f)
        session.updateVibe(mkMinimalVibe("Next"))
        assertEquals(0, recorder.songStory.value.beats.size)
        session.updateProgress(PlaybackProgress(positionMs = 0L, durationMs = 180_000L))
        play(13..21, 0.2f)
        val story = recorder.songStory.value
        assertEquals(2, story.beats.size)
        assertEquals(musicLoudness(FloatArray(8) { if (it == 0) 0.2f else 0f }), story.beats.first().level, 1e-6f)
        assertTrue(story.beats.all { it.positionMs < 60_000L }, "the old song's position leaked into the new story")
    }

    @Test
    fun theStoryStopsAtItsCap() {
        session.updateProgress(PlaybackProgress(positionMs = 0L, durationMs = 200_000L))
        repeat(SongStory.MAX_BEATS / 4 + 2) { play(0..15, 0.3f) }
        assertEquals(SongStory.MAX_BEATS, recorder.songStory.value.beats.size)
    }
}
