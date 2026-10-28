package org.balch.orpheus.djapp.vibepresets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.DjSymbol
import org.balch.orpheus.core.plugin.symbols.HornSymbol
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.mixer.TrackGainSink
import org.balch.orpheus.features.pulsar.mixer.TrackTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibePresetPortsTest {

    private val ports = mutableMapOf<String, PortValue>()
    private val setterWrites = mutableListOf<Pair<String, PortValue>>()

    private val synth = SynthController().apply {
        setDelegates(
            setter = { id, value ->
                ports[id.key] = value
                setterWrites += id.key to value
                true
            },
            getter = { id -> ports[id.key] },
        )
    }

    private class RecordingSink : TrackGainSink {
        val loaded = mutableListOf<TrackTuning>()
        override val state = MutableStateFlow(TrackTuning())
        override fun toggleMute(t: Int) {}
        override fun toggleSolo(t: Int) {}
        override fun setLevel(t: Int, level: Float) {}
        override fun reset() {}
        override fun load(tuning: TrackTuning) {
            loaded += tuning
            state.value = tuning
        }
    }

    private class FakePulsar(
        private val base: PulsarFeature = PulsarViewModel.previewFeature(),
    ) : PulsarFeature by base {
        var bpm = 100f
        val setBpms = mutableListOf<Float>()
        val spaces = mutableListOf<Float>()
        val deeps = mutableListOf<Float>()

        override val baseBpm: Float get() = bpm
        override fun setBaseBpm(bpm: Float) {
            setBpms += bpm
            this.bpm = bpm
        }

        override val actions: PulsarPanelActions = base.actions.copy(
            setSpace = { spaces += it },
            setDeep = { deeps += it },
        )
    }

    private val pulsar = FakePulsar()
    private val sink = RecordingSink()

    private fun ports(captureTracks: Boolean = true) =
        VibePresetPorts(synth, pulsar, sink, captureTracks)

    private fun portOf(id: PluginControlId) = ports[id.key]

    @Test
    fun readThenWriteRoundTrips() {
        val p = ports()
        val tuning = TrackTuning().withLevel(2, 0.4f).toggleMute(5).toggleSolo(1)
        val values = mapOf(
            PresetField.MIX_PERC.key to 0.3f,
            PresetField.MIX_BASS.key to 0.6f,
            PresetField.MIX_KEYS.key to 0.7f,
            PresetField.MIX_FX.key to 0.8f,
            PresetField.MIX_DIST.key to 0.45f,
            PresetField.HORN_SPEED.key to 0.2f,
            PresetField.HORN_RATIO.key to 0.3f,
            PresetField.HORN_DEPTH.key to 0.4f,
            PresetField.HORN_MIX.key to 0.5f,
            PresetField.HORN_BRAKE.key to 1f,
            PresetField.DJ_WET_A.key to 0.1f,
            PresetField.DJ_WET_B.key to 0.9f,
            PresetField.DJ_SOURCE_A.key to 2f,
            PresetField.DJ_SOURCE_B.key to 4f,
            PresetField.DJ_CROSSFADER.key to 0.25f,
            PresetField.DJ_DELAY_SEND.key to 0.35f,
            PresetField.DJ_REVERB_SEND.key to 0.55f,
            PresetField.PULSAR_ENERGY.key to 0.65f,
            PresetField.PULSAR_COMPLEXITY.key to 0.75f,
            PresetField.PULSAR_MOOD.key to 0.85f,
            PresetField.PULSAR_ENVELOPE.key to 2f,
            PresetField.PULSAR_ROOT.key to 7f,
            PresetField.PULSAR_SCALE.key to 3f,
            PresetField.PULSAR_BPM.key to 128f,
        ) + tracksOf(tuning)

        p.write(values)
        // space and deep are not ports here: the fake records them, so seed the ports to read back.
        synth.controlFlow(PulsarSymbol.SPACE.controlId).value = FloatValue(0.15f)
        synth.controlFlow(PulsarSymbol.DEEP.controlId).value = FloatValue(0.95f)

        val read = ports().read(sink.state.value)

        assertEquals(values + (PresetField.PULSAR_SPACE.key to 0.15f) + (PresetField.PULSAR_DEEP.key to 0.95f), read)
    }

    @Test
    fun distWritesDriveAndMix() {
        ports().write(mapOf(PresetField.MIX_DIST.key to 0.6f))

        assertEquals(FloatValue(0.6f), portOf(DistortionSymbol.DRIVE.controlId))
        assertEquals(FloatValue(0.6f), portOf(DistortionSymbol.MIX.controlId))
    }

    @Test
    fun brakeAndSourcesWriteIntValues() {
        ports().write(
            mapOf(
                PresetField.HORN_BRAKE.key to 1f,
                PresetField.DJ_SOURCE_A.key to 2f,
                PresetField.DJ_SOURCE_B.key to 3.2f,
                PresetField.PULSAR_ROOT.key to 5f,
                PresetField.PULSAR_SCALE.key to 1f,
                PresetField.PULSAR_ENVELOPE.key to 2f,
            ),
        )

        assertEquals(IntValue(1), portOf(HornSymbol.BRAKE.controlId))
        assertEquals(IntValue(2), portOf(DjSymbol.SOURCE_A.controlId))
        assertEquals(IntValue(3), portOf(DjSymbol.SOURCE_B.controlId))
        assertEquals(IntValue(5), portOf(PulsarSymbol.ROOT_NOTE.controlId))
        assertEquals(IntValue(1), portOf(PulsarSymbol.SCALE.controlId))
        assertEquals(IntValue(2), portOf(PulsarSymbol.ENVELOPE_MODE.controlId))
    }

    @Test
    fun bpmGoesThroughBaseBpm() {
        pulsar.bpm = 90f
        assertEquals(90f, ports().read(TrackTuning())[PresetField.PULSAR_BPM.key])

        ports().write(mapOf(PresetField.PULSAR_BPM.key to 140f))

        assertEquals(listOf(140f), pulsar.setBpms)
        assertTrue(setterWrites.isEmpty())
    }

    @Test
    fun spaceAndDeepGoThroughPulsarActions() {
        synth.controlFlow(PulsarSymbol.SPACE.controlId).value = FloatValue(0.2f)
        synth.controlFlow(PulsarSymbol.DEEP.controlId).value = FloatValue(0.4f)
        setterWrites.clear()
        val read = ports().read(TrackTuning())
        assertEquals(0.2f, read[PresetField.PULSAR_SPACE.key])
        assertEquals(0.4f, read[PresetField.PULSAR_DEEP.key])

        ports().write(
            mapOf(PresetField.PULSAR_SPACE.key to 0.7f, PresetField.PULSAR_DEEP.key to 0.8f),
        )

        assertEquals(listOf(0.7f), pulsar.spaces)
        assertEquals(listOf(0.8f), pulsar.deeps)
        // The actions own the port writes, so the ports were not touched directly.
        assertTrue(setterWrites.isEmpty())
    }

    @Test
    fun tracksOmittedWhenNotCaptured() {
        val tuning = TrackTuning().withLevel(0, 0.5f)
        val read = ports(captureTracks = false).read(tuning)

        assertTrue(read.keys.none { it.startsWith("tracks.") })
        assertTrue(PresetField.MIX_BASS.key in read)

        ports(captureTracks = false).write(tracksOf(tuning))
        assertTrue(sink.loaded.isEmpty())
    }

    @Test
    fun writeIgnoresUnknownAndMissingKeys() {
        ports().write(mapOf("mix.bass" to 0.5f, "gone" to 1f))

        assertEquals(listOf<Pair<String, PortValue>>(PulsarSymbol.BASS_GAIN.controlId.key to FloatValue(0.5f)), setterWrites)
        assertTrue(pulsar.setBpms.isEmpty() && pulsar.spaces.isEmpty() && pulsar.deeps.isEmpty())
        assertTrue(sink.loaded.isEmpty())
    }

    @Test
    fun tracksWriteCallsLoadOnce() {
        val tuning = TrackTuning().withLevel(3, 0.8f).toggleMute(0).toggleSolo(6)

        ports().write(tracksOf(tuning))

        assertEquals(listOf(tuning), sink.loaded)
    }

    @Test
    fun partialTracksWriteStartsFromUnity() {
        ports().write(mapOf(PresetField.TRACKS_2_LEVEL.key to 0.5f))

        assertEquals(listOf(TrackTuning().withLevel(2, 0.5f)), sink.loaded)
        assertFalse(sink.loaded.single().anySolo)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changesEmitWhenACapturedPortOrTheTuningMoves() = runTest(UnconfinedTestDispatcher()) {
        val p = ports()
        var emissions = 0
        val job = launch { p.changes.collect { emissions++ } }
        val afterSubscribe = emissions

        synth.controlFlow(PulsarSymbol.BASS_GAIN.controlId).value = FloatValue(0.1f)
        val afterPort = emissions
        sink.load(TrackTuning().toggleMute(2))
        val afterTracks = emissions
        job.cancel()

        assertTrue(afterPort > afterSubscribe)
        assertTrue(afterTracks > afterPort)
    }

    private fun tracksOf(t: TrackTuning): Map<String, Float> = buildMap {
        for (i in 0 until TrackTuning.TRACK_COUNT) {
            put("tracks.$i.level", t.levels[i])
            put("tracks.$i.mute", if (t.muted[i]) 1f else 0f)
            put("tracks.$i.solo", if (t.soloed[i]) 1f else 0f)
        }
    }
}
