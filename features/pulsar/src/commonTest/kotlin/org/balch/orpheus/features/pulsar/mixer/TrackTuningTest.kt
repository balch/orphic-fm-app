package org.balch.orpheus.features.pulsar.mixer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackTuningTest {

    private val ports = mutableMapOf<String, PortValue>()
    private val writes = mutableListOf<Pair<Int, Float>>()

    private fun controller(
        vibeLoads: MutableStateFlow<Int> = MutableStateFlow(0),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): TrackTuningController {
        val synth = SynthController().apply {
            setDelegates(
                setter = { id, value ->
                    ports[id.symbol] = value
                    val t = id.symbol.removePrefix("track_").substringBefore('_').toIntOrNull()
                    if (t != null && id.symbol.endsWith("_audition_gain")) writes += t to value.asFloat()
                    true
                },
                getter = { id -> ports[id.symbol] },
            )
        }
        // Unconfined: a vibeLoads change reaches the controller synchronously.
        return TrackTuningController(synth, vibeLoads, scope).also { writes.clear() }
    }

    private fun pushed(t: Int) = ports[PulsarSymbol.entries[PulsarSymbol.TRACK_0_AUDITION_GAIN.ordinal + t].symbol]?.asFloat()

    @Test
    fun defaultsAreUnityEverywhere() {
        assertEquals(List(8) { 1f }, TrackTuning().gains())
        assertFalse(TrackTuning().isTouched)
    }

    @Test
    fun oneSoloSilencesEveryOtherTrack() {
        val gains = TrackTuning().toggleSolo(4).gains()
        assertEquals(List(8) { if (it == 4) 1f else 0f }, gains)
    }

    @Test
    fun soloIsAdditive() {
        val gains = TrackTuning().toggleSolo(1).toggleSolo(6).gains()
        assertEquals(List(8) { if (it == 1 || it == 6) 1f else 0f }, gains)
    }

    @Test
    fun muteBeatsSolo() {
        val tuning = TrackTuning().toggleSolo(2).toggleMute(2)
        assertEquals(0f, tuning.gain(2))
        assertEquals(0f, tuning.gain(3))
    }

    @Test
    fun levelScalesAndClamps() {
        val tuning = TrackTuning().withLevel(3, 0.4f).withLevel(5, 9f).withLevel(6, -1f)
        assertEquals(0.4f, tuning.gain(3))
        assertEquals(TrackTuning.MAX_LEVEL, tuning.gain(5))
        assertEquals(0f, tuning.gain(6))
        // A soloed track keeps its own level.
        assertEquals(0.4f, tuning.toggleSolo(3).gain(3))
    }

    @Test
    fun actionsPushOnlyTheGainsThatChange() {
        val c = controller()
        c.toggleSolo(0)
        assertEquals((1..7).map { it to 0f }, writes)
        writes.clear()
        c.setLevel(0, 0.5f)
        assertEquals(listOf(0 to 0.5f), writes)
        writes.clear()
        c.toggleMute(3) // already silent under the solo
        assertTrue(writes.isEmpty())
        c.toggleSolo(0)
        // Solo off: everyone but the muted track comes back.
        assertEquals(listOf(1 to 1f, 2 to 1f, 4 to 1f, 5 to 1f, 6 to 1f, 7 to 1f), writes)
        assertEquals(0.5f, pushed(0))
        assertEquals(0f, pushed(3))
    }

    @Test
    fun constructionPushesUnitySoAStaleEngineGainCannotLinger() {
        ports[PulsarSymbol.TRACK_3_AUDITION_GAIN.symbol] = PortValue.FloatValue(0f)
        controller()
        for (t in 0..7) assertEquals(1f, pushed(t), "track $t")
    }

    @Test
    fun resetReturnsEveryTrackToUnity() {
        val c = controller()
        c.toggleSolo(2); c.toggleMute(5); c.setLevel(2, 1.3f)
        c.reset()
        assertEquals(TrackTuning(), c.state.value)
        for (t in 0..7) assertEquals(1f, pushed(t), "track $t")
    }

    @Test
    fun aBusIsSilentWhenEveryTrackInItMakesNoSound() {
        val perc = MixerGroup.PERC.tracks
        assertFalse(TrackTuning().isBusSilent(perc))
        // Muting two of three leaves the third sounding.
        assertFalse(TrackTuning().toggleMute(0).toggleMute(1).isBusSilent(perc))
        assertTrue(TrackTuning().toggleMute(0).toggleMute(1).toggleMute(2).isBusSilent(perc))
        // A solo elsewhere silences PERC and not the soloed bus.
        val soloBass = TrackTuning().toggleSolo(3)
        assertTrue(soloBass.isBusSilent(perc))
        assertFalse(soloBass.isBusSilent(MixerGroup.BASS.tracks))
        // A zero level counts too.
        assertTrue(TrackTuning().withLevel(3, 0f).isBusSilent(MixerGroup.BASS.tracks))
    }

    @Test
    fun vibeLoadResetsTuningState() {
        val loads = MutableStateFlow(0)
        val c = controller(loads)
        c.toggleSolo(2); c.toggleMute(5); c.setLevel(2, 1.3f)
        // Reloading the same vibe still bumps the count.
        loads.value = 1
        assertEquals(TrackTuning(), c.state.value)
        assertEquals(List(8) { 1f }, List(8) { pushed(it) })
        c.toggleMute(0)
        loads.value = 2
        assertEquals(TrackTuning(), c.state.value)
        assertEquals(List(8) { 1f }, List(8) { pushed(it) })
    }

    @Test
    fun muteBeforeTheLoadResetLandsDoesNotLeaveATrackSilent() {
        val loads = MutableStateFlow(0)
        val c = controller(loads)
        c.toggleMute(0)
        // PulsarFeature's applyVibe wrote unity to every track on IO, before the controller saw the load.
        for (t in 0 until 8) {
            ports[PulsarSymbol.entries[PulsarSymbol.TRACK_0_AUDITION_GAIN.ordinal + t].symbol] = PortValue.FloatValue(1f)
        }
        // A tap in that window diffs against the stale state and writes only BASS.
        c.toggleMute(3)
        assertEquals(0f, pushed(3))
        loads.value = 1
        assertEquals(TrackTuning(), c.state.value)
        assertEquals(List(8) { 1f }, List(8) { pushed(it) })
    }

    @Test
    fun loadPushesEveryTrackGain() {
        val c = controller()
        val tuning = TrackTuning(levels = List(8) { 0.5f })
        c.load(tuning)
        assertEquals(List(8) { 0.5f }, List(8) { pushed(it) })
        // The engine was reset to unity behind the controller's back; the same state must still push.
        for (t in 0 until 8) {
            ports[PulsarSymbol.entries[PulsarSymbol.TRACK_0_AUDITION_GAIN.ordinal + t].symbol] = PortValue.FloatValue(1f)
        }
        writes.clear()
        c.load(tuning)
        assertEquals(8, writes.size)
        assertEquals(List(8) { 0.5f }, List(8) { pushed(it) })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadForCurrentVibeLoadSurvivesTheClear() {
        val loads = MutableStateFlow(0)
        val dispatcher = StandardTestDispatcher()
        val c = controller(loads, CoroutineScope(dispatcher))
        // applyVibe bumps the count, then loads, all before the collector gets to run.
        loads.value = 1
        val muted = TrackTuning(muted = List(8) { it == 2 })
        c.load(muted)
        dispatcher.scheduler.runCurrent()
        assertEquals(muted, c.state.value)
        assertEquals(0f, pushed(2))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nextVibeLoadClearsALoadedTuning() {
        val loads = MutableStateFlow(0)
        val dispatcher = StandardTestDispatcher()
        val c = controller(loads, CoroutineScope(dispatcher))
        loads.value = 1
        c.load(TrackTuning(muted = List(8) { it == 2 }))
        dispatcher.scheduler.runCurrent()
        loads.value = 2
        dispatcher.scheduler.runCurrent()
        assertEquals(TrackTuning(), c.state.value)
        assertEquals(List(8) { 1f }, List(8) { pushed(it) })
    }
}
