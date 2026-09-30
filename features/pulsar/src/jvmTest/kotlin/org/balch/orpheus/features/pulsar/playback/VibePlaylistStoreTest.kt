package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.core.preferences.VibePlaylistPrefs
import org.balch.orpheus.features.pulsar.makeAppCoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class VibePlaylistStoreTest {
    private class MemoryRepo(var prefs: AppPreferences = AppPreferences()) : BaseAppPreferencesRepository() {
        override suspend fun load() = prefs
        override suspend fun save(preferences: AppPreferences) { prefs = preferences }
    }

    private fun TestScope.store(repo: MemoryRepo) =
        AppVibePlaylistStore(repo, makeAppCoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun loadsTheSavedPlaylist() = runTest {
        val saved = VibePlaylistPrefs(listOf("B", "A"))
        val store = store(MemoryRepo(AppPreferences(vibePlaylist = saved)))
        advanceUntilIdle()
        assertEquals(saved, store.prefsFlow.value)
    }

    @Test
    fun anUpdateShowsAtOnceAndThenPersists() = runTest {
        val repo = MemoryRepo()
        val store = store(repo)
        advanceUntilIdle()
        store.update { VibePlaylistPrefs(listOf("A")) }
        assertEquals(listOf("A"), store.prefsFlow.value?.order, "the flow must not wait for the disk")
        advanceUntilIdle()
        assertEquals(listOf("A"), repo.prefs.vibePlaylist?.order)
    }

    @Test
    fun anEditBeforeTheLoadWins() = runTest {
        val repo = MemoryRepo(AppPreferences(vibePlaylist = VibePlaylistPrefs(listOf("old"))))
        val store = store(repo)
        store.update { VibePlaylistPrefs(listOf("new")) }
        advanceUntilIdle()
        assertEquals(listOf("new"), store.prefsFlow.value?.order)
        assertEquals(listOf("new"), repo.prefs.vibePlaylist?.order)
    }

    @Test
    fun aBurstOfEditsPersistsTheLast() = runTest {
        val repo = MemoryRepo()
        val store = store(repo)
        advanceUntilIdle()
        listOf("A", "B", "C").forEach { name -> store.update { VibePlaylistPrefs(listOf(name)) } }
        advanceUntilIdle()
        assertEquals(listOf("C"), repo.prefs.vibePlaylist?.order)
    }

    @Test
    fun resetPersistsNull() = runTest {
        val repo = MemoryRepo(AppPreferences(vibePlaylist = VibePlaylistPrefs(listOf("A"))))
        val store = store(repo)
        advanceUntilIdle()
        store.update { null }
        advanceUntilIdle()
        assertNull(store.prefsFlow.value)
        assertNull(repo.prefs.vibePlaylist)
    }

    @Test
    fun inMemoryUpdatesInPlace() {
        val store = VibePlaylistStore.InMemory()
        store.update { VibePlaylistPrefs(listOf("A")) }
        assertEquals(listOf("A"), store.prefsFlow.value?.order)
    }
}
