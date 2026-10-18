package org.balch.orpheus.features.record

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.features.record.store.TakeInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {

    private val takeId = "take-2026-10-17-132431"

    private class Rig(testScope: TestScope, preloaded: List<TakeInfo> = emptyList()) {
        val capture = FakeOutputCapture()
        val store = FakeTakeStore().also { it.takes += preloaded }
        val featureScope = FeatureCoroutineScope()
        val level = MutableStateFlow(0f)
        private val base: Instant =
            LocalDateTime(2026, 10, 17, 13, 24, 31).toInstant(TimeZone.currentSystemDefault())
        private val clock = object : Clock {
            override fun now() = base + testScope.testScheduler.currentTime.milliseconds
        }
        val session = RecordSession(
            capture, store, FakePresetSnapshotter(), SynthController(), clock,
            testScope.backgroundScope, StandardTestDispatcher(testScope.testScheduler),
        )
        val player = FakeTakePlayer()
        val vm = RecordViewModel(
            session, store, player,
            object : MasterLevelSource { override val level: StateFlow<Float> = this@Rig.level },
            featureScope,
        )
        val state: StateFlow<RecordUiState> = vm.stateFlow

        init {
            // WhileSubscribed needs a live collector, like the panel.
            testScope.backgroundScope.launch { state.collect { } }
            testScope.testScheduler.runCurrent()
        }
    }

    private fun take(id: String, createdAtMs: Long = 0L) =
        TakeInfo(id, 10f, createdAtMs, "/takes/$id/audio.wav")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun toggleStartsAndStops() = runTest {
        val rig = Rig(this)

        rig.vm.actions.toggleRecord()
        runCurrent()
        assertTrue(rig.state.value.recording)

        rig.vm.actions.toggleRecord()
        advanceTimeBy(100)
        runCurrent()
        assertFalse(rig.state.value.recording)
        rig.featureScope.cancel()
    }

    @Test
    fun finishedTakeIsSelectedAndListed() = runTest {
        val rig = Rig(this, preloaded = listOf(take("take-old")))

        rig.vm.actions.toggleRecord()
        runCurrent()
        rig.vm.actions.toggleRecord()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf(takeId, "take-old"), rig.state.value.takes.map { it.takeId })
        assertEquals(takeId, rig.state.value.selectedTakeId)
        rig.featureScope.cancel()
    }

    @Test
    fun takeBeingRecordedIsNotListed() = runTest {
        val rig = Rig(this, preloaded = listOf(take("take-old")))

        rig.vm.actions.toggleRecord()
        runCurrent()
        // A delete refreshes the list, which now includes the open take's folder.
        rig.vm.actions.requestDelete("take-old")
        rig.vm.actions.confirmDelete()
        runCurrent()

        assertTrue(rig.store.takes.any { it.takeId == takeId })
        assertTrue(rig.state.value.recording)
        assertEquals(emptyList(), rig.state.value.takes.map { it.takeId })

        rig.vm.actions.toggleRecord()
        advanceTimeBy(100)
        runCurrent()
        rig.featureScope.cancel()
    }

    @Test
    fun abortedTakeLeavesNoGhostRow() = runTest {
        val rig = Rig(this)
        rig.store.writer.throwOnWrite = true
        rig.capture.chunks += 100

        rig.vm.actions.toggleRecord()
        runCurrent()
        // Force a refresh while recording so the open take is in the underlying list.
        rig.vm.actions.requestDelete("none")
        rig.vm.actions.confirmDelete()
        runCurrent()
        assertTrue(rig.state.value.recording)

        advanceTimeBy(100)
        runCurrent()

        assertTrue(rig.store.writer.aborted)
        assertFalse(rig.state.value.recording)
        assertEquals(emptyList(), rig.state.value.takes.map { it.takeId })
        rig.featureScope.cancel()
    }

    @Test
    fun levelComesFromMasterLevelSource() = runTest {
        val rig = Rig(this)
        rig.level.value = 0.6f
        runCurrent()

        assertEquals(0.6f, rig.state.value.level)
        rig.featureScope.cancel()
    }

    @Test
    fun deleteNeedsConfirm() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a", 2L), take("b", 1L)))
        val actions = rig.vm.actions

        actions.requestDelete("a")
        runCurrent()
        assertEquals("a", rig.state.value.pendingDeleteId)

        actions.cancelDelete()
        runCurrent()
        assertNull(rig.state.value.pendingDeleteId)
        assertTrue(rig.store.deleted.isEmpty())

        actions.requestDelete("a")
        actions.confirmDelete()
        runCurrent()
        assertEquals(listOf("a"), rig.store.deleted)
        assertEquals(listOf("b"), rig.state.value.takes.map { it.takeId })
        assertNull(rig.state.value.pendingDeleteId)
        rig.featureScope.cancel()
    }

    @Test
    fun deletingSelectedTakeClearsSelection() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a")))
        rig.vm.actions.select("a")
        rig.vm.actions.requestDelete("a")
        rig.vm.actions.confirmDelete()
        runCurrent()

        assertNull(rig.state.value.selectedTakeId)
        rig.featureScope.cancel()
    }

    @Test
    fun cannotDeleteRecordingTake() = runTest {
        val rig = Rig(this)
        rig.vm.actions.toggleRecord()
        runCurrent()

        rig.vm.actions.requestDelete(takeId)
        rig.vm.actions.confirmDelete()
        runCurrent()

        assertNull(rig.state.value.pendingDeleteId)
        assertTrue(rig.store.deleted.isEmpty())
        assertTrue(rig.state.value.recording)

        rig.vm.actions.toggleRecord()
        advanceTimeBy(100)
        runCurrent()
        rig.featureScope.cancel()
    }

    @Test
    fun startingRecordingStopsPlayback() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a")))
        rig.vm.actions.play("a")
        runCurrent()
        assertTrue(rig.player.state.value.playing)

        rig.vm.actions.toggleRecord()
        runCurrent()

        assertEquals(1, rig.player.stopCount)
        assertFalse(rig.player.state.value.playing)
        assertTrue(rig.state.value.recording)

        rig.vm.actions.toggleRecord()
        advanceTimeBy(100)
        runCurrent()
        rig.featureScope.cancel()
    }

    @Test
    fun deletingPlayingTakeStopsPlaybackFirst() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a")))
        var deletedWhenStopped: List<String>? = null
        rig.player.onStop = { deletedWhenStopped = rig.store.deleted.toList() }
        rig.vm.actions.play("a")
        runCurrent()

        rig.vm.actions.requestDelete("a")
        rig.vm.actions.confirmDelete()
        runCurrent()

        assertEquals(emptyList(), deletedWhenStopped)
        assertEquals(listOf("a"), rig.store.deleted)
        rig.featureScope.cancel()
    }

    @Test
    fun deletingTakeStillLoadingStopsPlaybackFirst() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a")))
        var deletedWhenStopped: List<String>? = null
        rig.player.onStop = { deletedWhenStopped = rig.store.deleted.toList() }
        rig.player.loadPending = true
        rig.vm.actions.play("a")
        runCurrent()
        assertNull(rig.player.state.value.takeId)

        rig.vm.actions.requestDelete("a")
        rig.vm.actions.confirmDelete()
        runCurrent()

        assertEquals(1, rig.player.stopCount)
        assertEquals(emptyList(), deletedWhenStopped)
        assertEquals(listOf("a"), rig.store.deleted)
        rig.featureScope.cancel()
    }

    @Test
    fun playingTakeBeingRecordedDoesNothing() = runTest {
        val rig = Rig(this)
        rig.vm.actions.toggleRecord()
        runCurrent()

        rig.vm.actions.play(takeId)
        runCurrent()

        assertTrue(rig.player.played.isEmpty())

        rig.vm.actions.toggleRecord()
        advanceTimeBy(100)
        runCurrent()
        rig.featureScope.cancel()
    }

    @Test
    fun deletingOtherTakeKeepsPlaying() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a"), take("b")))
        rig.vm.actions.play("a")
        runCurrent()

        rig.vm.actions.requestDelete("b")
        rig.vm.actions.confirmDelete()
        runCurrent()

        assertEquals(0, rig.player.stopCount)
        assertTrue(rig.player.state.value.playing)
        rig.featureScope.cancel()
    }

    @Test
    fun playSelectsTake() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a"), take("b")))

        rig.vm.actions.play("b")
        runCurrent()

        assertEquals(listOf("b"), rig.player.played)
        assertEquals("b", rig.state.value.selectedTakeId)
        assertEquals("b", rig.state.value.playback.takeId)
        assertTrue(rig.state.value.playback.playing)
        rig.featureScope.cancel()
    }

    @Test
    fun pauseAndSeekReachThePlayer() = runTest {
        val rig = Rig(this, preloaded = listOf(take("a")))
        rig.vm.actions.play("a")
        rig.vm.actions.seek(0.5f)
        runCurrent()
        assertEquals(5f, rig.state.value.playback.positionSec)

        rig.vm.actions.pause()
        runCurrent()
        assertFalse(rig.state.value.playback.playing)
        rig.featureScope.cancel()
    }

    @Test
    fun playingUnknownTakeDoesNothing() = runTest {
        val rig = Rig(this)
        rig.vm.actions.play("ghost")
        runCurrent()

        assertTrue(rig.player.played.isEmpty())
        assertNull(rig.state.value.selectedTakeId)
        rig.featureScope.cancel()
    }
}
