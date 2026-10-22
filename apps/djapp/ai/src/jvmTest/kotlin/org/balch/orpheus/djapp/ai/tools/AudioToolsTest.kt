package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.bench.StemCaptureOutcome
import org.balch.orpheus.features.pulsar.bench.StemCaptureService
import org.balch.orpheus.features.pulsar.bench.StemFile
import org.balch.orpheus.features.pulsar.bench.UnavailableStemCaptureService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AudioToolsTest {
    private val pulsar = playingPulsar("Night Drive")

    /** Records what the capture tool asked for. */
    private class FakeCaptureService : StemCaptureService {
        val calls = mutableListOf<Pair<Float, List<Int>>>()
        override suspend fun capture(seconds: Float, stems: List<Int>, progress: suspend (Float) -> Unit): StemCaptureOutcome {
            calls += seconds to stems
            progress(0.5f)
            return StemCaptureOutcome(true, "recorded", "/tmp/x", listOf(StemFile("bus", "bus", "/tmp/x/bus.wav", -12f, -3f)), 480, 2)
        }
    }

    private suspend fun TestScope.analyzeTool(): Tool<AudioAnalyzeArgs, AudioAnalyzeResult> {
        val recorder = stemRecorder(pulsar)
        return AudioAnalyzeTool { recorder }.tool
    }

    @Test
    fun analyzeReturnsRequestedKinds() = runTest {
        val r = analyzeTool().execute(AudioAnalyzeArgs(seconds = 0.5f, stems = listOf("track0", "out"), kinds = listOf("level", "spectrum")))
        assertTrue(r.ok, r.message)
        assertEquals(listOf("track0", "out"), r.stems.map { it.stem })
        for (s in r.stems) {
            assertNotNull(s.rmsDb)
            assertNotNull(s.peakDb)
            assertEquals(31, s.bandsDb?.size)
            assertNull(s.onsets)
            assertNull(s.correlation)
        }
        assertEquals("Night Drive", r.vibe)
        assertEquals("verse", r.section)
        assertEquals(0.5f, r.seconds)
        assertEquals(0L, r.droppedFrames)
        assertEquals(List(8) { OrpheusEngineId.VA.displayName }, r.trackLabels)
    }

    @Test
    fun analyzeDefaultsToEveryStemAndKind() = runTest {
        val r = analyzeTool().execute(AudioAnalyzeArgs(seconds = 0.5f))
        assertTrue(r.ok, r.message)
        assertEquals(13, r.stems.size)
        assertNotNull(r.stems.last().correlation)
        assertNotNull(r.stems.last().onsets)
    }

    @Test
    fun analyzeRejectsUnknownStem() = runTest {
        val r = analyzeTool().execute(AudioAnalyzeArgs(stems = listOf("kick")))
        assertFalse(r.ok)
        assertTrue("kick" in r.message && "track0" in r.message, r.message)
    }

    @Test
    fun analyzeRejectsUnknownKind() = runTest {
        val r = analyzeTool().execute(AudioAnalyzeArgs(kinds = listOf("loudness")))
        assertFalse(r.ok)
        assertTrue("loudness" in r.message && "spectrum" in r.message, r.message)
    }

    @Test
    fun analyzeRejectsTooLong() = runTest {
        val r = analyzeTool().execute(AudioAnalyzeArgs(seconds = 20f))
        assertFalse(r.ok)
        assertTrue("15" in r.message, r.message)
        val short = analyzeTool().execute(AudioAnalyzeArgs(seconds = 0.2f))
        assertFalse(short.ok)
        assertTrue("0.5" in short.message, short.message)
    }

    @Test
    fun analyzeRefusesWhenStopped() = runTest {
        pulsar.stateFlow.value = pulsar.stateFlow.value.copy(playing = false)
        val r = analyzeTool().execute(AudioAnalyzeArgs(seconds = 0.5f))
        assertFalse(r.ok)
        assertTrue("play" in r.message, r.message)
        assertTrue(r.stems.isEmpty())
    }

    @Test
    fun captureMapsStemNamesAndOutcome() = runTest {
        val service = FakeCaptureService()
        val r = AudioCaptureTool(service).tool.execute(AudioCaptureArgs(seconds = 2f, stems = listOf("out", "track3", "out")))
        assertEquals(listOf(2f to listOf(12, 3)), service.calls)
        assertTrue(r.ok)
        assertEquals("/tmp/x", r.dir)
        assertEquals(listOf("bus"), r.files.map { it.stem })
        assertEquals(480, r.frames)
        assertEquals(2L, r.droppedFrames)
        AudioCaptureTool(service).tool.execute(AudioCaptureArgs())
        assertEquals(10f to (0 until 13).toList(), service.calls.last())
    }

    @Test
    fun captureReportsProgress() = runTest {
        val reports = mutableListOf<Triple<Double, Double, String?>>()
        withContext(ToolProgress { d, t, m -> reports += Triple(d, t, m) }) {
            AudioCaptureTool(FakeCaptureService()).tool.execute(AudioCaptureArgs(seconds = 2f))
        }
        assertEquals(listOf<Triple<Double, Double, String?>>(Triple(0.5, 1.0, "recording")), reports)
    }

    @Test
    fun captureRejectsTooLong() = runTest {
        val service = FakeCaptureService()
        val r = AudioCaptureTool(service).tool.execute(AudioCaptureArgs(seconds = 31f))
        assertFalse(r.ok)
        assertTrue("30" in r.message, r.message)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun unavailableOnOtherPlatforms() = runTest {
        val r = AudioCaptureTool(UnavailableStemCaptureService()).tool.execute(AudioCaptureArgs(seconds = 1f))
        assertFalse(r.ok)
        assertTrue("isn't available" in r.message, r.message)
    }

    @Test
    fun toolNames() {
        assertEquals("audio_analyze", AudioAnalyzeTool { error("unused") }.tool.descriptor.name)
        assertEquals("audio_capture", AudioCaptureTool(UnavailableStemCaptureService()).tool.descriptor.name)
    }
}
