package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SongEndingPreferencesTest {

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
    // a real instance with a TestDispatchers — same pattern as PlaybackControllerTest
    // and SynthOrchestratorTest.
    private fun testScope() = AppCoroutineScope(TestDispatchers(UnconfinedTestDispatcher()))

    @Test
    fun `default enabled is true`() = runTest {
        val repo = FakePrefsRepo()
        val prefs = SongEndingPreferencesImpl(repo, testScope())
        advanceUntilIdle()
        assertTrue(prefs.enabledFlow.value)
    }

    @Test
    fun `setEnabled false persists and is observable`() = runTest {
        val repo = FakePrefsRepo()
        val prefs = SongEndingPreferencesImpl(repo, testScope())
        advanceUntilIdle()

        prefs.setEnabled(false)
        advanceUntilIdle()

        assertFalse(prefs.enabledFlow.value)
        assertFalse(repo.load().pulsarSongEndingEnabled)
    }

    @Test
    fun `loads existing value from repo on init`() = runTest {
        val repo = FakePrefsRepo(AppPreferences(pulsarSongEndingEnabled = true))
        val prefs = SongEndingPreferencesImpl(repo, testScope())
        advanceUntilIdle()
        assertTrue(prefs.enabledFlow.value)
    }
}
