package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.features.pulsar.FakePulsarFeature
import org.balch.orpheus.features.pulsar.StubTransitionPreferences
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PulsarSkipHandlerTest {

    /** Records calls into the runner so tests can assert on the resolved [TransitionSpec]. */
    private class RecordingRunner : PulsarTransitionRunner {
        val specs = mutableListOf<TransitionSpec>()
        override val activeStyle: StateFlow<TransitionStyle?> = MutableStateFlow(null)
        override suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit) {
            specs += spec
            applyNext()
        }
    }

    @Test
    fun `skip-next uses the user-configured transition spec, not the TAPE default`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val prefs = StubTransitionPreferences(initial = TransitionSpec(TransitionStyle.CROSSFADE))
        val runner = RecordingRunner()
        val handler = PulsarSkipHandler(
            pulsarFeatureProvider = { feature },
            transitionRunner = runner,
            transitionPreferences = prefs,
            scope = makeAppCoroutineScope(),
        )
        advanceUntilIdle()

        handler.onSkip(SkipDirection.NEXT)
        advanceUntilIdle()

        assertEquals(1, runner.specs.size)
        assertEquals(TransitionStyle.CROSSFADE, runner.specs.single().style)
        assertEquals("B", feature.vibeFlow.value.name)
    }

    @Test
    fun `skip-previous wraps and still uses the configured style`() = runTest {
        val vibes = listOf(mkMinimalVibe("A"), mkMinimalVibe("B"), mkMinimalVibe("C"))
        val feature = FakePulsarFeature(vibes, vibes[0])
        val prefs = StubTransitionPreferences(initial = TransitionSpec(TransitionStyle.FADE))
        val runner = RecordingRunner()
        val handler = PulsarSkipHandler(
            pulsarFeatureProvider = { feature },
            transitionRunner = runner,
            transitionPreferences = prefs,
            scope = makeAppCoroutineScope(),
        )
        advanceUntilIdle()

        handler.onSkip(SkipDirection.PREVIOUS)
        advanceUntilIdle()

        assertEquals(TransitionStyle.FADE, runner.specs.single().style)
        assertEquals("C", feature.vibeFlow.value.name)
    }
}
