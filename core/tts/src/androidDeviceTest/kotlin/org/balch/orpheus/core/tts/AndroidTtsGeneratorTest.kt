package org.balch.orpheus.core.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs against the device's real TTS engine. This is the only place the WAV those engines write
 * meets [WavDecoder] — the commonTest cases build their own bytes, so they cannot catch an engine
 * that writes, say, WAVE_FORMAT_EXTENSIBLE instead of plain PCM.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTtsGeneratorTest {

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val generator = AndroidTtsGenerator(
        InstrumentationRegistry.getInstrumentation().targetContext,
        TestDispatchers,
    )

    @Test
    fun reportsAvailableWhenAnEngineIsInstalled() {
        // Proves the manifest <queries> entry merged: without it this reads false on API 30+.
        assertTrue(generator.isAvailable, "no TTS engine visible; is the <queries> entry present?")
    }

    @Test
    fun generatesAudibleSamplesForAPhrase() = runBlocking {
        assumeTrue("no TTS engine on this device", generator.isAvailable)

        val result = assertNotNull(
            generator.generate("I'm all out of bubblegum", voice = null, speakingRate = null),
            "synthesizeToFile produced nothing the decoder could read",
        )

        assertTrue(result.sampleRate > 8000, "implausible sample rate ${result.sampleRate}")
        val seconds = result.samples.size / result.sampleRate.toFloat()
        assertTrue(seconds > 0.5f, "phrase was only ${seconds}s long")
        assertTrue(result.samples.any { abs(it) > 0.01f }, "clip decoded as silence")
    }

    @Test
    fun reportsAvailableVoices() = runBlocking {
        assumeTrue("no TTS engine on this device", generator.isAvailable)

        assertTrue(generator.listVoices().isNotEmpty(), "engine reported no usable voices")
    }

    @Test
    fun aRequestWithNoVoiceDoesNotInheritThePreviousVoice() = runBlocking {
        assumeTrue("no TTS engine on this device", generator.isAvailable)

        // One engine serves every vibe, so a vibe with no voice must not speak in the last one's.
        assertNotNull(generator.generate("one of these days", voice = null, speakingRate = null))
        val default = generator.activeVoiceName
        assertNotNull(generator.generate("one of these days", voice = "Daniel", speakingRate = null))
        assumeTrue("Daniel is this device's default voice", generator.activeVoiceName != default)

        assertNotNull(generator.generate("one of these days", voice = null, speakingRate = null))
        val after = generator.activeVoiceName
        assertTrue(after == default, "voice stuck on $after instead of returning to $default")
    }

    @Test
    fun aFasterRateProducesAShorterClip() = runBlocking {
        assumeTrue("no TTS engine on this device", generator.isAvailable)

        val slow = assertNotNull(generator.generate("one of these days", null, 120))
        val fast = assertNotNull(generator.generate("one of these days", null, 280))

        assertTrue(
            fast.samples.size < slow.samples.size,
            "rate had no effect: slow=${slow.samples.size} fast=${fast.samples.size}",
        )
    }
}
