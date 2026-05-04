package org.balch.orpheus.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.TestDispatcherProvider
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.media.PlaybackMode
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.pulsar.Album
import org.balch.orpheus.features.pulsar.GenreProfile
import org.balch.orpheus.features.pulsar.OrpheusEngine
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.RhythmPattern
import org.balch.orpheus.features.pulsar.RootNote
import org.balch.orpheus.features.pulsar.ScaleType
import org.balch.orpheus.features.pulsar.TrackRole
import org.balch.orpheus.features.pulsar.TrackVoice
import org.balch.orpheus.features.pulsar.Vibe
import org.balch.orpheus.features.pulsar.playback.PulsarMetadataProducer
import kotlin.test.Test
import kotlin.test.assertEquals

private fun sampleVibe(
    name: String = "Test",
    bpm: Float = 120f,
    album: Album = Album.STEALTH,
) = Vibe(
    name = name,
    album = album,
    bpm = bpm,
    rootNote = RootNote.C,
    scaleType = ScaleType.MINOR,
    genre = GenreProfile(
        swingAmount = 0f,
        ghostProbability = 0f,
        noteRangeLow = 36,
        noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.SPARSE.density,
    ),
    tracks = List(8) {
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VIRTUAL_ANALOG),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VIRTUAL_ANALOG),
            role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
        )
    },
)

private class FakePulsarFeature(initial: Vibe) : PulsarFeature {
    override val vibeList = listOf(initial)
    override val vibeFlow = MutableStateFlow(initial)
    override fun applyVibe(vibe: Vibe) { vibeFlow.value = vibe }
    override val arrangementStateFlow: StateFlow<PulsarArrangementState> =
        MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
    override val stateFlow: StateFlow<PulsarUiState> get() = TODO("not used in producer tests")
    override val actions: PulsarPanelActions get() = TODO("not used in producer tests")
}

private class FakeAiFeature(initialMode: PlaybackMode = PlaybackMode.USER) : AiOptionsFeature {
    override val currentMode = MutableStateFlow(initialMode)
    override val stateFlow get() = TODO("not used in producer tests")
    override val actions get() = TODO("not used in producer tests")
}

class OrpheusMetadataProducerTest {

    private data class Harness(
        val producer: OrpheusMetadataProducer,
        val ai: FakeAiFeature,
        val pulsar: FakePulsarFeature,
        val mediaState: MediaSessionStateManager,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun build(
        mode: PlaybackMode,
        vibeName: String = "MyVibe",
        bpm: Float = 130f,
        album: Album = Album.STEALTH,
        evoActive: Boolean = false,
    ): Harness {
        val scope = AppCoroutineScope(TestDispatcherProvider(UnconfinedTestDispatcher()))
        val pulsarFeature = FakePulsarFeature(sampleVibe(vibeName, bpm, album))
        val pulsarMetadata = PulsarMetadataProducer(pulsarFeature, scope)
        val aiFeature = FakeAiFeature(mode)
        val mediaState = MediaSessionStateManager(scope).apply { setEvoActive(evoActive) }
        val producer = OrpheusMetadataProducer(aiFeature, pulsarMetadata, mediaState, scope)
        return Harness(producer, aiFeature, pulsarFeature, mediaState)
    }

    @Test fun `AI active title is Orpheus`() = runTest {
        val h = build(mode = PlaybackMode.DRONE)
        assertEquals("Orpheus", h.producer.titleFlow.value)
    }

    @Test fun `AI active subtitle is mode displayName`() = runTest {
        val h = build(mode = PlaybackMode.DRONE)
        assertEquals(PlaybackMode.DRONE.displayName, h.producer.subtitleFlow.value)
    }

    @Test fun `USER mode delegates title to Pulsar vibe name`() = runTest {
        val h = build(mode = PlaybackMode.USER, vibeName = "Funky Vibe")
        assertEquals("Funky Vibe", h.producer.titleFlow.value)
    }

    @Test fun `USER mode delegates subtitle to Pulsar album title`() = runTest {
        val h = build(mode = PlaybackMode.USER, album = Album.ZERO_TO_ONE)
        assertEquals(Album.ZERO_TO_ONE.title, h.producer.subtitleFlow.value)
    }

    @Test fun `transition from AI active to USER falls back to Pulsar`() = runTest {
        val h = build(mode = PlaybackMode.SOLO, vibeName = "FallBackVibe")
        assertEquals("Orpheus", h.producer.titleFlow.value)
        h.ai.currentMode.value = PlaybackMode.USER
        assertEquals("FallBackVibe", h.producer.titleFlow.value)
    }

    @Test fun `Evo active in USER mode shows Orpheus + Audio Evo`() = runTest {
        val h = build(mode = PlaybackMode.USER, evoActive = true)
        assertEquals("Orpheus", h.producer.titleFlow.value)
        assertEquals(PlaybackMode.EVO.displayName, h.producer.subtitleFlow.value)
    }

    @Test fun `AI mode wins over Evo when both active`() = runTest {
        val h = build(mode = PlaybackMode.DRONE, evoActive = true)
        assertEquals(PlaybackMode.DRONE.displayName, h.producer.subtitleFlow.value)
    }

    @Test fun `toggling Evo on in USER mode flips title from Pulsar to Orpheus`() = runTest {
        val h = build(mode = PlaybackMode.USER, vibeName = "PulsarVibe")
        assertEquals("PulsarVibe", h.producer.titleFlow.value)
        h.mediaState.setEvoActive(true)
        assertEquals("Orpheus", h.producer.titleFlow.value)
        assertEquals(PlaybackMode.EVO.displayName, h.producer.subtitleFlow.value)
    }
}
