package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.ui.input.key.Key
import java.lang.reflect.Proxy
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
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.djapp.ai.tools.FakePulsar
import org.balch.orpheus.djapp.ai.tools.OneDispatcher
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LickLabViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    /** Every port write in order: symbol and value. */
    private val writes = mutableListOf<Pair<String, Float>>()
    private val synth = SynthController().apply {
        setDelegates(setter = { id, v -> writes += id.symbol to v.asFloat(); true }, getter = { null })
    }
    private val viz = MutableStateFlow(PulsarVizData())
    private val scope = FeatureCoroutineScope()

    @BeforeTest fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterTest fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    /** The VM's scope shares the test scheduler; its recording ticker never idles, so it ends with the body. */
    private fun labTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            scope.cancel()
        }
    }

    private fun track(role: TrackRole) = TrackVoice(
        engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
        engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
        role = role,
    )

    /** B minor, lick octave 5 (degree 0 = B4 = 71), bass octave 1 (B1 = 35); tracks 0-2 and 7 are drums. */
    private fun or5Like(name: String = "OR-like") = Vibe(
        name = name,
        tracks = listOf(
            track(TrackRole.Percussive), track(TrackRole.Percussive), track(TrackRole.Percussive),
            track(TrackRole.Melodic(lickSource = LickSource.BASS)), track(TrackRole.Melodic()),
            track(TrackRole.Chordal()), track(TrackRole.Melodic()), track(TrackRole.Percussive),
        ),
        lickOctave = 5,
        bassLineOctave = 2,
        bpm = 120f,
        rootNote = RootNote.B,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(0f, 0f, 36, 72, RhythmPattern.SPARSE.density),
    )

    private fun engineWithViz(flow: StateFlow<PulsarVizData>) = Proxy.newProxyInstance(
        SynthEngine::class.java.classLoader,
        arrayOf(SynthEngine::class.java),
    ) { _, method, _ ->
        when {
            method.name == "getPulsarVizFlow" -> flow
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Float::class.javaPrimitiveType -> 0f
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    } as SynthEngine

    private class Rig(val pulsar: FakePulsar, val vm: LickLabViewModel)

    private fun TestScope.rig(): Rig {
        val pulsar = FakePulsar(listOf(or5Like(), or5Like("Other")))
        val vm = LickLabViewModel(
            pulsarFeature = pulsar,
            synthEngine = engineWithViz(viz),
            synthController = synth,
            scope = scope,
            dispatchers = OneDispatcher(testDispatcher),
            nowMs = { testScheduler.currentTime },
        )
        runCurrent()
        return Rig(pulsar, vm)
    }

    private fun TestScope.shownAndArmed(track: Int = 4): Rig = rig().also {
        it.vm.actions.toggleShown()
        it.vm.actions.arm(track)
        runCurrent()
        writes.clear()
    }

    private fun idx(symbol: String) = writes.indexOfFirst { it.first == symbol }
    private fun value(symbol: String) = writes.last { it.first == symbol }.second

    @Test
    fun `arming a track arms it and disarms the one before`() = labTest {
        val r = rig()
        r.vm.actions.toggleShown()
        r.vm.actions.arm(3)
        assertEquals(listOf("track_3_live_active" to 1f), writes)
        writes.clear()
        r.vm.actions.arm(4)
        assertEquals(
            listOf(
                "track_3_live_gate" to 0f, "track_3_live_active" to 0f, "track_3_live_boost" to 1f, "track_3_live_ghost" to 0f,
                "track_4_live_active" to 1f,
            ),
            writes,
        )
        assertEquals(4, r.vm.stateFlow.value.armedTrack)
    }

    @Test
    fun `a key strikes with pitch first, overlap is legato and shift overlap slides`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.keyDown(Key.A)
        assertEquals(71f, value("track_4_live_note"))
        assertEquals(0.8f, value("track_4_live_velocity"))
        assertEquals(1f, value("track_4_live_strike"))
        assertEquals(1f, value("track_4_live_gate"))
        val gate = idx("track_4_live_gate")
        val strike = idx("track_4_live_strike")
        for (s in listOf("track_4_live_note", "track_4_live_velocity", "track_4_live_glide")) {
            assertTrue(idx(s) < strike && idx(s) < gate, "$s lands before the strike: $writes")
        }
        assertTrue(strike < gate, "strike before gate: $writes")
        val note = idx("track_4_live_note")
        assertTrue(idx("track_4_live_velocity") < note && idx("track_4_live_glide") < note, "note last of the three: $writes")
        assertEquals(71, r.vm.stateFlow.value.heldMidi)

        writes.clear()
        r.vm.actions.keyDown(Key.S)
        assertEquals(listOf("track_4_live_glide" to 0f, "track_4_live_note" to 73f), writes)

        writes.clear()
        r.vm.actions.keyDown(Key.ShiftLeft)
        r.vm.actions.keyDown(Key.D)
        assertEquals(listOf("track_4_live_glide" to 0.3f, "track_4_live_note" to 74f), writes)

        // Letting go of the top key falls back to the still-held one below it.
        writes.clear()
        r.vm.actions.keyUp(Key.D)
        assertEquals(73f, value("track_4_live_note"))
        r.vm.actions.keyUp(Key.S)
        r.vm.actions.keyUp(Key.A)
        assertEquals(0f, value("track_4_live_gate"))
        assertNull(r.vm.stateFlow.value.heldMidi)
    }

    @Test
    fun `auto repeat of a held key writes nothing`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.keyDown(Key.A)
        writes.clear()
        r.vm.actions.keyDown(Key.A)
        r.vm.actions.keyDown(Key.A)
        assertEquals(emptyList(), writes)
    }

    @Test
    fun `a vibe change disarms, clears held notes, keeps the take and stops playback`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.toggleRecording()
        r.vm.actions.keyDown(Key.A)
        advanceTimeBy(300)
        writes.clear()
        r.pulsar.applyVibe(r.pulsar.vibeList[1])
        runCurrent()
        assertEquals(listOf("track_4_live_gate" to 0f, "track_4_live_active" to 0f, "track_4_live_boost" to 1f, "track_4_live_ghost" to 0f), writes)
        assertNull(r.vm.stateFlow.value.armedTrack)
        assertNull(r.vm.stateFlow.value.heldMidi)
        val take = r.vm.stateFlow.value.takes.single()
        assertEquals(listOf(71), take.notes.map { it.midi })
        r.vm.actions.toggleRecording()

        // Playback in flight stops with the vibe change: nothing lands after the disarm.
        r.vm.actions.arm(4)
        r.vm.actions.playTake(take.id)
        runCurrent()
        assertEquals(1f, value("track_4_live_gate"))
        r.pulsar.applyVibe(r.pulsar.vibeList[0])
        runCurrent()
        writes.clear()
        advanceTimeBy(3000)
        assertEquals(emptyList(), writes)

        // The held A is gone: re-arming and pressing it strikes fresh.
        r.vm.actions.arm(4)
        writes.clear()
        r.vm.actions.keyDown(Key.A)
        assertEquals(1f, value("track_4_live_gate"))
    }

    @Test
    fun `a restart of the same vibe keeps the armed track, caller, slot and boost`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.setCaller(5)
        val slot = r.vm.stateFlow.value.slot
        r.vm.actions.toggleBoost()
        r.vm.actions.keyDown(Key.A)
        writes.clear()
        r.pulsar.applyVibe(r.pulsar.vibeFlow.value)
        runCurrent()
        val s = r.vm.stateFlow.value
        assertEquals(4, s.armedTrack)
        assertEquals(5, s.callerTrack)
        assertEquals(slot, s.slot)
        assertEquals(true, s.boost)
        assertNull(s.heldMidi)
        assertEquals(1f, value("track_4_live_active"))
        assertEquals(1.585f, value("track_4_live_boost"))
    }

    @Test
    fun `hiding Lick Lab disarms`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.keyDown(Key.A)
        writes.clear()
        r.vm.actions.toggleShown()
        assertEquals(listOf("track_4_live_gate" to 0f, "track_4_live_active" to 0f, "track_4_live_boost" to 1f, "track_4_live_ghost" to 0f), writes)
        assertNull(r.vm.stateFlow.value.armedTrack)
        writes.clear()
        r.vm.actions.keyDown(Key.S)
        assertEquals(emptyList(), writes)
    }

    @Test
    fun `drum tracks are not offered`() = labTest {
        val r = rig()
        assertEquals(listOf(3, 4, 5, 6), r.vm.stateFlow.value.tracks.map { it.index })
        r.vm.actions.toggleShown()
        r.vm.actions.arm(0)
        assertNull(r.vm.stateFlow.value.armedTrack)
        assertEquals(emptyList(), writes)
    }

    @Test
    fun `a bar of silence closes a take and copy emits its Kotlin`() = labTest {
        val r = shownAndArmed()
        val copied = mutableListOf<String>()
        backgroundScope.launch { r.vm.copied.collect { copied += it } }
        r.vm.actions.toggleRecording()
        r.vm.actions.keyDown(Key.A)
        advanceTimeBy(500)
        r.vm.actions.keyUp(Key.A)
        runCurrent()
        assertEquals(emptyList(), r.vm.stateFlow.value.takes)
        // 128 bpm (the fake's live tempo): a bar is 1875 ms.
        advanceTimeBy(2500)
        runCurrent()
        val take = r.vm.stateFlow.value.takes.single()
        assertEquals(listOf(71), take.notes.map { it.midi })

        r.vm.actions.copy(take.id)
        runCurrent()
        val slot = SlotRef(LickSlot.Lead)
        // Track 4 follows chords (the TrackRole default), so the export says so.
        val expected = TakeExport.toKotlin(TakeExport.toSteps(take, r.pulsar.vibeFlow.value, slot), slot, followsChords = true)
        assertEquals(listOf(expected), copied)
        assertEquals(expected, r.vm.kotlinFor(take.id))
        assertTrue("// ${TakeExport.FOLLOWS_CHORDS_NOTE}" in expected)
    }

    @Test
    fun `a take on a FIXED track is not marked as following chords`() = labTest {
        val r = rig()
        val fixed = or5Like("Fixed").let { v ->
            v.copy(tracks = v.tracks.mapIndexed { i, t -> if (i == 4) t.copy(role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED)) else t })
        }
        r.pulsar.applyVibe(fixed)
        runCurrent()
        r.vm.actions.toggleShown()
        r.vm.actions.arm(4)
        r.vm.actions.toggleRecording()
        r.vm.actions.keyDown(Key.A)
        advanceTimeBy(500)
        r.vm.actions.keyUp(Key.A)
        r.vm.actions.toggleRecording()
        runCurrent()
        val take = r.vm.stateFlow.value.takes.single()
        assertEquals(false, take.context?.followsChords)
        assertTrue(TakeExport.FOLLOWS_CHORDS_NOTE !in r.vm.kotlinFor(take.id).orEmpty())
    }

    @Test
    fun `stopping the recording keeps the take and the list keeps the last 20`() = labTest {
        val r = shownAndArmed()
        repeat(22) {
            r.vm.actions.toggleRecording()
            r.vm.actions.keyDown(Key.A)
            advanceTimeBy(100)
            r.vm.actions.keyUp(Key.A)
            r.vm.actions.toggleRecording()
        }
        val takes = r.vm.stateFlow.value.takes
        assertEquals(LickLabViewModel.MAX_TAKES, takes.size)
        assertEquals(22L, takes.last().id)
        r.vm.actions.star(takes.last().id)
        assertTrue(r.vm.stateFlow.value.takes.last().starred)
        r.vm.actions.delete(takes.first().id)
        assertEquals(19, r.vm.stateFlow.value.takes.size)
    }

    @Test
    fun `the bass slot re-bases degree 0 and the key label`() = labTest {
        val r = rig()
        assertEquals(71, r.vm.stateFlow.value.baseMidi)
        assertEquals("B minor · 0 = B4", r.vm.stateFlow.value.keyLabel)
        r.vm.actions.setSlot(SlotRef(LickSlot.Bass))
        assertEquals(35, r.vm.stateFlow.value.baseMidi)
        assertEquals("B minor · 0 = B1", r.vm.stateFlow.value.keyLabel)
    }

    @Test
    fun `arming the bass track picks the bass slot`() = labTest {
        val r = shownAndArmed(track = 3)
        assertEquals(SlotRef(LickSlot.Bass), r.vm.stateFlow.value.slot)
        r.vm.actions.keyDown(Key.A)
        assertEquals(35f, value("track_3_live_note"))
    }

    @Test
    fun `a kraken shift moves the played note but not the exported degree`() = labTest {
        val r = shownAndArmed()
        viz.value = PulsarVizData(krakenShift = 1) // V: up a fifth, same scale
        runCurrent()
        r.vm.actions.toggleRecording()
        r.vm.actions.keyDown(Key.A)
        assertEquals(78f, value("track_4_live_note"))
        advanceTimeBy(500)
        r.vm.actions.keyUp(Key.A)
        r.vm.actions.toggleRecording()
        val take = r.vm.stateFlow.value.takes.single()
        assertEquals(71, take.notes.single().midi)
        val steps = TakeExport.toSteps(take, r.pulsar.vibeFlow.value, SlotRef(LickSlot.Lead)).steps
        assertEquals(0, steps.first { !it.rest }.scaleDegree)
    }

    @Test
    fun `mouse slides glide and bends ride the held note`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.pointerDown(1L, 71, 0.6f)
        assertEquals(0.6f, value("track_4_live_velocity"))
        writes.clear()
        r.vm.actions.pointerSlide(1L, 74, 0.4f)
        assertEquals(listOf("track_4_live_glide" to 0.4f, "track_4_live_note" to 74f), writes)
        writes.clear()
        r.vm.actions.pointerBend(1L, 5f)
        assertEquals(listOf("track_4_live_glide" to 0f, "track_4_live_note" to 76f), writes)
        r.vm.actions.pointerUp(1L)
        assertEquals(0f, value("track_4_live_gate"))
    }

    @Test
    fun `play take replays the notes on the armed track`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.toggleRecording()
        r.vm.actions.keyDown(Key.A)
        advanceTimeBy(400)
        r.vm.actions.keyDown(Key.S)
        advanceTimeBy(400)
        r.vm.actions.keyUp(Key.S)
        r.vm.actions.keyUp(Key.A)
        r.vm.actions.toggleRecording()
        val take = r.vm.stateFlow.value.takes.single()
        writes.clear()
        r.vm.actions.playTake(take.id)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(
            listOf(71f, 73f),
            writes.filter { it.first == "track_4_live_note" }.map { it.second },
        )
        assertEquals(1, writes.count { it.first == "track_4_live_strike" })
        assertEquals(0f, value("track_4_live_gate"))
    }

    @Test
    fun `the strike counter wraps below the port ceiling`() {
        val live = LiveTrackInput(synth)
        live.strikeCount = LiveTrackInput.STRIKE_WRAP
        live.strike(2, 60f, 0.8f, 0f)
        assertEquals(1f, value("track_2_live_strike"))
        live.strike(2, 60f, 0.8f, 0f)
        assertEquals(2f, value("track_2_live_strike"))
    }

    @Test
    fun `release all lets go of keys and pointers but stays armed`() = labTest {
        val r = shownAndArmed()
        r.vm.actions.keyDown(Key.ShiftLeft)
        r.vm.actions.keyDown(Key.A)
        r.vm.actions.pointerDown(7L, 76, 0.5f)
        writes.clear()
        r.vm.actions.releaseAll()
        assertEquals(listOf("track_4_live_gate" to 0f), writes)
        assertEquals(4, r.vm.stateFlow.value.armedTrack)
        assertNull(r.vm.stateFlow.value.heldMidi)
        // Nothing is still held: the keys strike fresh, and Shift no longer slides.
        r.vm.actions.keyUp(Key.A)
        r.vm.actions.pointerUp(7L)
        writes.clear()
        r.vm.actions.keyDown(Key.A)
        assertEquals(1f, value("track_4_live_gate"))
        writes.clear()
        r.vm.actions.keyDown(Key.S)
        assertEquals(listOf("track_4_live_glide" to 0f, "track_4_live_note" to 73f), writes)
    }

    @Test
    fun `the armed track can be its own caller, which runs its pattern unheard`() = labTest {
        val r = rig()
        r.vm.actions.toggleShown()
        r.vm.actions.setCaller(4)
        r.vm.actions.arm(4)
        assertEquals(4, r.vm.stateFlow.value.armedTrack)
        assertEquals(4, r.vm.stateFlow.value.callerTrack)
        assertEquals(1f, value("track_4_live_ghost"))
        // Picking another caller hands the readback back to the live note.
        r.vm.actions.setCaller(3)
        assertEquals(3, r.vm.stateFlow.value.callerTrack)
        assertEquals(0f, value("track_4_live_ghost"))
        // Picked while armed.
        r.vm.actions.setCaller(4)
        assertEquals(1f, value("track_4_live_ghost"))
        r.vm.actions.setCaller(null)
        assertEquals(0f, value("track_4_live_ghost"))
        // Moving the player off the track stops the ghost and keeps the caller.
        r.vm.actions.setCaller(4)
        r.vm.actions.arm(5)
        assertEquals(0f, value("track_4_live_ghost"))
        assertTrue(writes.none { it.first == "track_5_live_ghost" })
        assertEquals(4, r.vm.stateFlow.value.callerTrack)
    }

    @Test
    fun `a caller that is no longer offered is dropped on a vibe change`() = labTest {
        val r = rig()
        r.vm.actions.setCaller(5)
        assertEquals(5, r.vm.stateFlow.value.callerTrack)
        val drums = or5Like("Drums").let { v ->
            v.copy(tracks = v.tracks.mapIndexed { i, t -> if (i == 5) t.copy(role = TrackRole.Percussive) else t })
        }
        r.pulsar.applyVibe(drums)
        runCurrent()
        assertNull(r.vm.stateFlow.value.callerTrack)
    }

    @Test
    fun `a chip names one engine for both slots, both when they differ, then the track's name`() {
        val va = track(TrackRole.Melodic())
        assertEquals("VA", LickLabViewModel.chipDetail(va))
        assertEquals("VA · timpani", LickLabViewModel.chipDetail(va.copy(name = "timpani")))
        val split = va.copy(engineSpace = OrpheusEngine(engineId = OrpheusEngineId.DX2), name = "pad")
        assertEquals("VA/DX2 · pad", LickLabViewModel.chipDetail(split))
    }

    @Test
    fun `boost writes the gain and 1 and needs an armed track`() = labTest {
        val r = rig()
        r.vm.actions.toggleShown()
        r.vm.actions.toggleBoost()
        assertEquals(false, r.vm.stateFlow.value.boost)
        r.vm.actions.arm(4)
        writes.clear()
        r.vm.actions.toggleBoost()
        assertEquals(listOf("track_4_live_boost" to 1.585f), writes)
        assertEquals(true, r.vm.stateFlow.value.boost)
        assertEquals(4, r.pulsar.volumeBypass)
        r.vm.actions.toggleBoost()
        assertEquals("track_4_live_boost" to 1f, writes.last())
        assertEquals(false, r.vm.stateFlow.value.boost)
        assertEquals(null, r.pulsar.volumeBypass)
    }

    private fun TestScope.boosted(): Rig = shownAndArmed().also {
        it.vm.actions.toggleBoost()
        writes.clear()
    }

    private fun assertBoostDropped(r: Rig, track: Int = 4) {
        assertEquals(false, r.vm.stateFlow.value.boost)
        assertEquals(1f, value("track_${track}_live_boost"))
        assertEquals(null, r.pulsar.volumeBypass)
    }

    @Test
    fun `boost drops on disarm`() = labTest {
        val r = boosted()
        r.vm.actions.disarm()
        assertBoostDropped(r)
    }

    @Test
    fun `boost drops on hide`() = labTest {
        val r = boosted()
        r.vm.actions.toggleShown()
        assertBoostDropped(r)
    }

    @Test
    fun `boost follows a switch to another track and drops on another vibe`() = labTest {
        val r = boosted()
        r.vm.actions.arm(5)
        assertEquals(1f, value("track_4_live_boost"))
        assertEquals(1.585f, value("track_5_live_boost"))
        assertEquals(true, r.vm.stateFlow.value.boost)
        assertEquals(5, r.pulsar.volumeBypass)
        writes.clear()
        r.pulsar.applyVibe(r.pulsar.vibeList[1])
        runCurrent()
        assertBoostDropped(r, 5)
    }

    @Test
    fun `boost drops when the armed track is armed again`() = labTest {
        val r = boosted()
        r.vm.actions.arm(4)
        assertBoostDropped(r)
    }

    @Test
    fun `the clearing of the view model drops the boost port`() = labTest {
        val r = boosted()
        scope.cancel()
        runCurrent()
        assertEquals(1f, value("track_4_live_boost"))
    }

    @Test
    fun `the next chord and its beat come from the viz while armed`() = labTest {
        val r = shownAndArmed()
        advanceTimeBy(1000)
        val now = r.vm.currentBeat()
        viz.value = PulsarVizData(nextChordRootDegree = 3, beatsToChordChange = 1.5f)
        runCurrent()
        val s = r.vm.stateFlow.value
        assertEquals(3, s.nextChordRootDegree)
        assertEquals(now + 1.5, s.changeBeat!!, 0.05)
        viz.value = PulsarVizData(nextChordRootDegree = -1, beatsToChordChange = -1f)
        runCurrent()
        assertEquals(-1, r.vm.stateFlow.value.nextChordRootDegree)
        assertNull(r.vm.stateFlow.value.changeBeat)
    }

    @Test
    fun `polls counting down to the same change leave the state unchanged`() = labTest {
        val r = shownAndArmed()
        advanceTimeBy(1000)
        val target = r.vm.currentBeat() + 2.0
        viz.value = PulsarVizData(nextChordRootDegree = 3, beatsToChordChange = 2f)
        runCurrent()
        val first = r.vm.stateFlow.value
        // Each poll is late by up to 1/32 beat, as the monitor's rounding and staleness make it.
        repeat(20) { i ->
            advanceTimeBy(16)
            val late = (1 + i % 3) / 128.0
            viz.value = PulsarVizData(nextChordRootDegree = 3, beatsToChordChange = (target - r.vm.currentBeat() + late).toFloat())
            runCurrent()
            assertSame(first, r.vm.stateFlow.value)
        }
        assertEquals(target, first.changeBeat!!, 0.05)
    }

    @Test
    fun `a new next chord or a real jump re-anchors the change`() = labTest {
        val r = shownAndArmed()
        advanceTimeBy(1000)
        val now = r.vm.currentBeat()
        viz.value = PulsarVizData(nextChordRootDegree = 3, beatsToChordChange = 2f)
        runCurrent()
        viz.value = PulsarVizData(nextChordRootDegree = 3, beatsToChordChange = 1f)
        runCurrent()
        assertEquals(now + 1.0, r.vm.stateFlow.value.changeBeat!!, 0.05)
        viz.value = PulsarVizData(nextChordRootDegree = 5, beatsToChordChange = 1.1f)
        runCurrent()
        assertEquals(now + 1.1, r.vm.stateFlow.value.changeBeat!!, 0.05)
    }

    @Test
    fun `no hint is shown while nothing is armed`() = labTest {
        val r = rig()
        r.vm.actions.toggleShown()
        runCurrent()
        viz.value = PulsarVizData(nextChordRootDegree = 3, beatsToChordChange = 1.5f)
        runCurrent()
        assertEquals(-1, r.vm.stateFlow.value.nextChordRootDegree)
        assertNull(r.vm.stateFlow.value.changeBeat)
    }

    @Test
    fun `the hand is the held key then the last played then degree 0`() = labTest {
        val r = shownAndArmed()
        assertEquals(71, r.vm.stateFlow.value.handMidi)
        r.vm.actions.keyDown(Key.S)
        val held = r.vm.stateFlow.value.heldMidi!!
        assertEquals(held, r.vm.stateFlow.value.handMidi)
        r.vm.actions.keyUp(Key.S)
        assertEquals(held, r.vm.stateFlow.value.handMidi)
        assertEquals(held, r.vm.stateFlow.value.lastPlayedMidi)
        r.vm.actions.setHintMode(HintMode.Move)
        assertEquals(HintMode.Move, r.vm.stateFlow.value.hintMode)
    }

    private fun TestScope.windowAfter(vararg samples: Pair<Int, Float>): Double {
        val r = shownAndArmed()
        samples.forEach { (chord, left) ->
            viz.value = PulsarVizData(chordRootDegree = chord, nextChordRootDegree = 3, beatsToChordChange = left)
            runCurrent()
        }
        return r.vm.stateFlow.value.hintWindowBeats
    }

    @Test
    fun `a half beat chord gives a half beat window`() = labTest {
        assertEquals(0.5, windowAfter(0 to 0.5f, 0 to 0.25f), 1e-6)
    }

    @Test
    fun `a four beat chord gives a two beat window`() = labTest {
        assertEquals(2.0, windowAfter(0 to 4f, 0 to 1f), 1e-6)
    }

    @Test
    fun `a two beat chord gives a one beat window and a long one stops at two`() = labTest {
        assertEquals(1.0, windowAfter(0 to 2f), 1e-6)
        assertEquals(2.0, windowAfter(1 to 8f), 1e-6)
    }

    @Test
    fun `an unknown chord length gives a one beat window`() = labTest {
        val r = shownAndArmed()
        assertEquals(1.0, r.vm.stateFlow.value.hintWindowBeats, 1e-6)
        assertEquals(1.0, windowAfter(0 to 0f), 1e-6)
    }

    @Test
    fun `a late first sample keeps the longest countdown of the chord`() = labTest {
        // First seen with 0.3 left, but an earlier sample of this chord had 0.5.
        assertEquals(0.5, windowAfter(0 to 0.5f, 0 to 0.3f), 1e-6)
        // The next chord starts its own count.
        assertEquals(0.25, windowAfter(2 to 0.25f), 1e-6)
    }

    @Test
    fun `the compact switch disarm drops the boost`() = labTest {
        val r = boosted()
        r.vm.actions.disarm()
        assertBoostDropped(r)
        assertEquals(null, r.vm.stateFlow.value.armedTrack)
    }
}
