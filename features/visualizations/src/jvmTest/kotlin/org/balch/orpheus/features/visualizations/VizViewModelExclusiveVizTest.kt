package org.balch.orpheus.features.visualizations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.preferences.BaseAppPreferencesRepository
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.Visualization
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// ─── Fakes ───────────────────────────────────────────────────────────────────

/**
 * A [Visualization] stub with a configurable [exclusiveToSong], logging activate/deactivate
 * to a shared [log] when provided.
 */
private class FakeViz(
    override val id: String,
    override val name: String = id,
    override val exclusiveToSong: String? = null,
    private val log: MutableList<String>? = null,
) : Visualization {
    override val color = Color.Magenta
    override val knob1Label = "K1"
    override val knob2Label = "K2"
    override val liquidEffects = VisualizationLiquidEffects.Default
    override fun setKnob1(value: Float) {}
    override fun setKnob2(value: Float) {}
    override fun onActivate() { log?.add("activate:$id") }
    override fun onDeactivate() { log?.add("deactivate:$id") }
    @Composable override fun Content(modifier: Modifier) {}
}

/**
 * A [FakePrefsRepo]-alike whose [load] suspends until [release] is called, so a test can put the
 * startup prefs-load coroutine on hold and drive its ordering against the songFlow collector
 * deterministically instead of relying on dispatcher happenstance.
 */
private class ControllableFakePrefsRepo(
    prefs: AppPreferences = AppPreferences(randomVizMode = false),
) : BaseAppPreferencesRepository() {
    private val ready = CompletableDeferred<Unit>()
    var stored = prefs
        private set
    fun release() { ready.complete(Unit) }
    override suspend fun load(): AppPreferences {
        ready.await()
        return stored
    }
    override suspend fun save(preferences: AppPreferences) { stored = preferences }
}

/**
 * Tests for [VizViewModel]'s song-exclusive lock: a visualization whose [Visualization.exclusiveToSong]
 * matches the currently selected song (see [FakeMetadataProducer], defined in
 * VizViewModelDispatcherTest.kt) is forced in and the dropdown/random controls freeze until the
 * song changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VizViewModelExclusiveVizTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val defaultDispatcher = UnconfinedTestDispatcher()

    private val dispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcher
        override val io: CoroutineDispatcher = defaultDispatcher
        override val default: CoroutineDispatcher = defaultDispatcher
        override val unconfined: CoroutineDispatcher = defaultDispatcher
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(
        vararg extraViz: Visualization,
        metadata: FakeMetadataProducer = FakeMetadataProducer(),
        prefsRepo: AppPreferencesRepository = FakePrefsRepo(),
    ): VizViewModel = VizViewModel(
        visualizations = setOf(FakeViz(id = "off"), *extraViz),
        appPreferencesRepository = prefsRepo,
        synthController = SynthController(),
        dispatcherProvider = dispatcherProvider,
        scope = FeatureCoroutineScope(),
        metadataProducer = metadata,
    )

    @Test
    fun `exclusive viz is absent and never randomly picked while the song does not match`() =
        runTest(mainDispatcher) {
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A")
            val other = FakeViz(id = "other")
            val metadata = FakeMetadataProducer(initialSong = "Some Other Song")
            val vm = makeVm(exclusive, other, metadata = metadata)
            advanceUntilIdle()

            assertTrue(vm.stateFlow.value.visualizations.none { it.id == exclusive.id })

            repeat(200) {
                vm.actions.onSelectRandomViz()
                advanceUntilIdle()
                assertNotEquals(exclusive.id, vm.stateFlow.value.selectedViz.id)
            }
        }

    @Test
    fun `song change to the exclusive title locks and activates it`() = runTest(mainDispatcher) {
        val log = mutableListOf<String>()
        val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
        val other = FakeViz(id = "other", log = log)
        val metadata = FakeMetadataProducer()
        val vm = makeVm(exclusive, other, metadata = metadata)
        advanceUntilIdle()
        vm.selectVisualization(other, save = false)
        advanceUntilIdle()
        log.clear()

        metadata.song.value = "Song A"
        advanceUntilIdle()

        val state = vm.stateFlow.value
        assertTrue(state.isVizLocked)
        assertEquals(exclusive.id, state.selectedViz.id)
        assertTrue(state.visualizations.any { it.id == exclusive.id })
        assertEquals(listOf("deactivate:other", "activate:excl"), log)
    }

    @Test
    fun `while locked user actions leave the selection unchanged`() = runTest(mainDispatcher) {
        val log = mutableListOf<String>()
        val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
        val other = FakeViz(id = "other", log = log)
        val third = FakeViz(id = "third", log = log)
        val metadata = FakeMetadataProducer()
        val vm = makeVm(exclusive, other, third, metadata = metadata)
        advanceUntilIdle()
        metadata.song.value = "Song A"
        advanceUntilIdle()
        log.clear()

        vm.actions.onSelectViz(other)
        advanceUntilIdle()
        vm.actions.onSelectRandomViz()
        advanceUntilIdle()
        vm.actions.onSetRandomMode(true)
        advanceUntilIdle()

        val state = vm.stateFlow.value
        assertEquals(exclusive.id, state.selectedViz.id)
        assertTrue(state.isVizLocked)
        assertTrue(log.isEmpty(), "no lifecycle calls expected while locked: $log")
        assertTrue(state.isRandomVizMode, "setRandomMode(true) should still record the preference")
    }

    @Test
    fun `song change away from the exclusive title unlocks and restores the previous viz`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
            val other = FakeViz(id = "other", log = log)
            val metadata = FakeMetadataProducer()
            val vm = makeVm(exclusive, other, metadata = metadata)
            advanceUntilIdle()
            vm.selectVisualization(other, save = false)
            advanceUntilIdle()
            metadata.song.value = "Song A"
            advanceUntilIdle()
            log.clear()

            metadata.song.value = "Song B"
            advanceUntilIdle()

            val state = vm.stateFlow.value
            assertFalse(state.isVizLocked)
            assertEquals(other.id, state.selectedViz.id)
            assertTrue(state.visualizations.none { it.id == exclusive.id })
            assertEquals(1, log.count { it == "deactivate:excl" })
        }

    @Test
    fun `release path picks a random viz when random mode is on`() = runTest(mainDispatcher) {
        val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A")
        val other = FakeViz(id = "other")
        val metadata = FakeMetadataProducer()
        val vm = makeVm(exclusive, other, metadata = metadata)
        advanceUntilIdle()
        vm.actions.onSetRandomMode(true)
        advanceUntilIdle()
        metadata.song.value = "Song A"
        advanceUntilIdle()

        metadata.song.value = "Song B"
        advanceUntilIdle()

        val state = vm.stateFlow.value
        assertFalse(state.isVizLocked)
        // "other" is the only non-exclusive, non-off candidate, so the pick is deterministic.
        assertEquals(other.id, state.selectedViz.id)
    }

    @Test
    fun `race - random pick queued before the song change still locks on the exclusive viz`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
            val other = FakeViz(id = "other", log = log)
            val metadata = FakeMetadataProducer()
            val vm = makeVm(exclusive, other, metadata = metadata)
            advanceUntilIdle()
            vm.actions.onSetRandomMode(true)
            advanceUntilIdle() // random-mode pick lands on "other" (only non-exclusive candidate)
            log.clear()

            launch { vm.actions.onSelectRandomViz() }
            launch { metadata.song.value = "Song A" }
            advanceUntilIdle()

            val locked = vm.stateFlow.value
            assertTrue(locked.isVizLocked)
            assertEquals(exclusive.id, locked.selectedViz.id)

            // vizBeforeLock must have captured a non-exclusive viz.
            metadata.song.value = "Song B"
            advanceUntilIdle()
            assertEquals(other.id, vm.stateFlow.value.selectedViz.id)
        }

    @Test
    fun `race - song change queued before the random pick still locks on the exclusive viz`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
            val other = FakeViz(id = "other", log = log)
            val metadata = FakeMetadataProducer()
            val vm = makeVm(exclusive, other, metadata = metadata)
            advanceUntilIdle()
            vm.selectVisualization(other, save = false)
            advanceUntilIdle()
            vm.actions.onSetRandomMode(true)
            advanceUntilIdle()
            log.clear()

            launch { metadata.song.value = "Song A" }
            launch { vm.actions.onSelectRandomViz() }
            advanceUntilIdle()

            val locked = vm.stateFlow.value
            assertTrue(locked.isVizLocked)
            assertEquals(exclusive.id, locked.selectedViz.id)

            metadata.song.value = "Song B"
            advanceUntilIdle()
            assertEquals(other.id, vm.stateFlow.value.selectedViz.id)
        }

    @Test
    fun `stored lastVizId naming the exclusive viz is ignored at startup`() = runTest(mainDispatcher) {
        val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A")
        val prefsRepo = FakePrefsRepo(AppPreferences(lastVizId = "excl", randomVizMode = false))
        val vm = makeVm(exclusive, prefsRepo = prefsRepo)
        advanceUntilIdle()

        val state = vm.stateFlow.value
        assertNotEquals(exclusive.id, state.selectedViz.id)
        assertEquals("off", state.selectedViz.id)
    }

    @Test
    fun `repository never receives lastVizId equal to the exclusive viz id`() = runTest(mainDispatcher) {
        val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A")
        val prefsRepo = FakePrefsRepo()
        val metadata = FakeMetadataProducer()
        val vm = makeVm(exclusive, metadata = metadata)
        advanceUntilIdle()
        metadata.song.value = "Song A"
        advanceUntilIdle()
        // Locked selection always saves with save = false, but assert the invariant directly
        // too in case a future caller passes save = true for a song-exclusive viz.
        vm.selectVisualization(exclusive, save = true)
        advanceUntilIdle()

        assertNotEquals(exclusive.id, prefsRepo.stored.lastVizId)
    }

    @Test
    fun `re-emitting the same song title does not re-run lifecycle calls`() = runTest(mainDispatcher) {
        val log = mutableListOf<String>()
        val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
        val other = FakeViz(id = "other", log = log)
        val metadata = FakeMetadataProducer()
        val vm = makeVm(exclusive, other, metadata = metadata)
        advanceUntilIdle()
        vm.selectVisualization(other, save = false)
        advanceUntilIdle()
        log.clear()

        metadata.song.value = "Song A"
        advanceUntilIdle()
        assertEquals(listOf("deactivate:other", "activate:excl"), log)

        // Re-emitting the same title must not repeat the activate/deactivate pair, whether
        // it's StateFlow's own equal-value drop or the view model's "already locked" guard
        // that prevents it.
        metadata.song.value = "Song A"
        advanceUntilIdle()
        assertEquals(listOf("deactivate:other", "activate:excl"), log)
    }

    // ─── Fix round 1: startup race between the lock and preference loading ─────────────────────

    @Test
    fun `startup lock engages before prefs load - stored viz becomes the restore target`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
            val vizB = FakeViz(id = "viz_b", log = log)
            val metadata = FakeMetadataProducer(initialSong = "Song A")
            val prefsRepo = ControllableFakePrefsRepo(AppPreferences(lastVizId = "viz_b", randomVizMode = false))
            val vm = makeVm(exclusive, vizB, metadata = metadata, prefsRepo = prefsRepo)

            // The songFlow collector (main) runs first; the prefs load (default) is still parked.
            advanceUntilIdle()
            val lockedBeforePrefs = vm.stateFlow.value
            assertTrue(lockedBeforePrefs.isVizLocked)
            assertEquals(exclusive.id, lockedBeforePrefs.selectedViz.id)
            assertTrue(log.none { it.startsWith("activate:viz_b") })

            // Now let prefs resolve; viz_b must become the restore target, not get selected now.
            prefsRepo.release()
            advanceUntilIdle()

            val stillLocked = vm.stateFlow.value
            assertTrue(stillLocked.isVizLocked)
            assertEquals(exclusive.id, stillLocked.selectedViz.id)
            assertTrue(log.none { it.startsWith("activate:viz_b") }, "viz_b must never activate while locked")
            log.clear()

            // Release the lock: viz_b (not Off) comes back, activated exactly once.
            metadata.song.value = "Song B"
            advanceUntilIdle()

            val released = vm.stateFlow.value
            assertFalse(released.isVizLocked)
            assertEquals(vizB.id, released.selectedViz.id)
            assertEquals(listOf("deactivate:excl", "activate:viz_b"), log)
            assertNotEquals(exclusive.id, prefsRepo.stored.lastVizId)
        }

    @Test
    fun `startup prefs load before the lock - stored viz selected then correctly locked`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
            val vizB = FakeViz(id = "viz_b", log = log)
            // Starts on a non-matching song so the lock can't engage until we say so.
            val metadata = FakeMetadataProducer(initialSong = "")
            val prefsRepo = FakePrefsRepo(AppPreferences(lastVizId = "viz_b", randomVizMode = false))
            val vm = makeVm(exclusive, vizB, metadata = metadata, prefsRepo = prefsRepo)
            advanceUntilIdle()

            assertFalse(vm.stateFlow.value.isVizLocked)
            assertEquals(vizB.id, vm.stateFlow.value.selectedViz.id)
            log.clear()

            metadata.song.value = "Song A"
            advanceUntilIdle()

            val locked = vm.stateFlow.value
            assertTrue(locked.isVizLocked)
            assertEquals(exclusive.id, locked.selectedViz.id)
            assertEquals(listOf("deactivate:viz_b", "activate:excl"), log)
            log.clear()

            metadata.song.value = "Song B"
            advanceUntilIdle()

            assertFalse(vm.stateFlow.value.isVizLocked)
            assertEquals(vizB.id, vm.stateFlow.value.selectedViz.id)
            assertEquals(listOf("deactivate:excl", "activate:viz_b"), log)
        }

    @Test
    fun `startup locked before randomVizMode prefs resolve - no pick while locked, random pick on release`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
            val other = FakeViz(id = "other", log = log)
            val metadata = FakeMetadataProducer(initialSong = "Song A")
            val prefsRepo = ControllableFakePrefsRepo(AppPreferences(randomVizMode = true))
            val vm = makeVm(exclusive, other, metadata = metadata, prefsRepo = prefsRepo)
            advanceUntilIdle()

            assertTrue(vm.stateFlow.value.isVizLocked)
            assertEquals(exclusive.id, vm.stateFlow.value.selectedViz.id)

            prefsRepo.release()
            advanceUntilIdle()

            // Still locked; random mode recorded, but no pick happened while locked.
            val stillLocked = vm.stateFlow.value
            assertTrue(stillLocked.isVizLocked)
            assertEquals(exclusive.id, stillLocked.selectedViz.id)
            assertTrue(stillLocked.isRandomVizMode)
            assertTrue(log.none { it.startsWith("activate:other") })

            metadata.song.value = "Song B"
            advanceUntilIdle()

            val released = vm.stateFlow.value
            assertFalse(released.isVizLocked)
            // "other" is the only non-exclusive, non-off candidate, so the random pick is
            // deterministic here.
            assertEquals(other.id, released.selectedViz.id)
        }

    // ─── 1c: locked song change straight to a DIFFERENT exclusive viz ──────────────────────────

    @Test
    fun `locked song change to a different exclusive viz hops without releasing the lock`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusiveA = FakeViz(id = "exclA", exclusiveToSong = "Song A", log = log)
            val exclusiveB = FakeViz(id = "exclB", exclusiveToSong = "Song B", log = log)
            val other = FakeViz(id = "other", log = log)
            val metadata = FakeMetadataProducer()
            val vm = makeVm(exclusiveA, exclusiveB, other, metadata = metadata)
            advanceUntilIdle()
            vm.selectVisualization(other, save = false)
            advanceUntilIdle()
            metadata.song.value = "Song A"
            advanceUntilIdle()
            log.clear()

            // A -> B directly: never passes through an unlocked song in between.
            metadata.song.value = "Song B"
            advanceUntilIdle()

            val hopped = vm.stateFlow.value
            assertTrue(hopped.isVizLocked)
            assertEquals(exclusiveB.id, hopped.selectedViz.id)
            assertTrue(hopped.visualizations.none { it.id == exclusiveA.id })
            assertTrue(hopped.visualizations.any { it.id == exclusiveB.id })
            assertEquals(listOf("deactivate:exclA", "activate:exclB"), log)
            log.clear()

            // B -> none: releases the lock and restores the viz from BEFORE the original A lock.
            metadata.song.value = "Song C"
            advanceUntilIdle()

            val released = vm.stateFlow.value
            assertFalse(released.isVizLocked)
            assertEquals(other.id, released.selectedViz.id)
            assertEquals(listOf("deactivate:exclB", "activate:other"), log)
        }

    // ─── 1d: performSelectVisualization refuses a stale exclusive viz whatever the caller ───────

    @Test
    fun `selecting a stale exclusive viz after its song has changed is refused`() =
        runTest(mainDispatcher) {
            val log = mutableListOf<String>()
            val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A", log = log)
            val other = FakeViz(id = "other", log = log)
            val metadata = FakeMetadataProducer()
            val vm = makeVm(exclusive, other, metadata = metadata)
            advanceUntilIdle()
            vm.selectVisualization(other, save = false)
            advanceUntilIdle()
            metadata.song.value = "Song A"
            advanceUntilIdle()
            metadata.song.value = "Song B" // unlocks, restores `other`
            advanceUntilIdle()
            log.clear()

            // A dropdown item composed one frame before the release can still fire a click for
            // the now-stale exclusive viz even though isVizLocked has already gone false.
            vm.actions.onSelectViz(exclusive)
            advanceUntilIdle()

            assertEquals(other.id, vm.stateFlow.value.selectedViz.id)
            assertTrue(log.isEmpty(), "no lifecycle calls expected for a refused stale selection: $log")
        }

    @Test
    fun `startup locked with no stored lastVizId - release restores Off`() = runTest(mainDispatcher) {
        val exclusive = FakeViz(id = "excl", exclusiveToSong = "Song A")
        val other = FakeViz(id = "other")
        val metadata = FakeMetadataProducer(initialSong = "Song A")
        val prefsRepo = FakePrefsRepo(AppPreferences(randomVizMode = false))
        val vm = makeVm(exclusive, other, metadata = metadata, prefsRepo = prefsRepo)
        advanceUntilIdle()

        assertTrue(vm.stateFlow.value.isVizLocked)
        assertEquals(exclusive.id, vm.stateFlow.value.selectedViz.id)

        metadata.song.value = "Song B"
        advanceUntilIdle()

        val released = vm.stateFlow.value
        assertFalse(released.isVizLocked)
        assertEquals("off", released.selectedViz.id)
    }
}
