package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TransitionPreferencesTest {

    private class FakePrefsRepo(initial: AppPreferences = AppPreferences()) : AppPreferencesRepository {
        @Volatile var current = initial
        override suspend fun load(): AppPreferences = current
        override suspend fun save(preferences: AppPreferences) { current = preferences }
        override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
            current = transform(current)
        }
    }

    private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
        override val main get() = d
        override val io get() = d
        override val default get() = d
        override val unconfined get() = d
    }

    // AppCoroutineScope is a final class (not an interface), so we construct
    // a real instance with a TestDispatchers — same pattern as SongEndingPreferencesTest.
    private fun testScope() = AppCoroutineScope(TestDispatchers(UnconfinedTestDispatcher()))

    @Test
    fun `flow initial value matches AppPreferences default`() = runTest {
        val repo = FakePrefsRepo()
        val prefs = TransitionPreferencesImpl(repo, testScope())
        advanceUntilIdle()
        assertEquals(TransitionStyle.TAPE, prefs.defaultFlow.value.style)
        assertEquals(1000, prefs.defaultFlow.value.handoffMs)
    }

    @Test
    fun `setDefault updates flow and persists`() = runTest {
        val repo = FakePrefsRepo()
        val prefs = TransitionPreferencesImpl(repo, testScope())
        advanceUntilIdle()

        val newSpec = TransitionSpec(style = TransitionStyle.TAPE, handoffMs = 250)
        prefs.setDefault(newSpec)
        advanceUntilIdle()

        assertEquals(newSpec, prefs.defaultFlow.value)
        assertEquals(newSpec, repo.load().pulsarTransitionDefault)
    }

    @Test
    fun `loads existing value from repo on init`() = runTest {
        val customSpec = TransitionSpec(style = TransitionStyle.TAPE, handoffMs = 500)
        val repo = FakePrefsRepo(AppPreferences(pulsarTransitionDefault = customSpec))
        val prefs = TransitionPreferencesImpl(repo, testScope())
        advanceUntilIdle()
        assertEquals(customSpec, prefs.defaultFlow.value)
    }
}
