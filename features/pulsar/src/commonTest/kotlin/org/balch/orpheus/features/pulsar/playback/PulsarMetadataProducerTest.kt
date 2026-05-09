package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.viz.ARRANGEMENT_STATE_UNKNOWN
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals

private fun sampleVibe(
    name: String = "Test Vibe",
    bpm: Float = 128f,
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
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
            role = if (it < 3) TrackRole.Percussive else TrackRole.Melodic(),
        )
    },
)

private class FakePulsarFeature(initial: Vibe) : PulsarFeature {
    override val vibeList = listOf(initial)
    override val vibeFlow = MutableStateFlow(initial)
    override fun applyVibe(vibe: Vibe) {}
    override val arrangementStateFlow: StateFlow<PulsarArrangementState> =
        MutableStateFlow(ARRANGEMENT_STATE_UNKNOWN)
    override val stateFlow: StateFlow<PulsarUiState> get() = TODO("not used in producer tests")
    override val actions: PulsarPanelActions get() = TODO("not used in producer tests")
}

private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

class PulsarMetadataProducerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun testScope() = AppCoroutineScope(TestDispatchers(UnconfinedTestDispatcher()))

    @Test fun `title is initial vibe name`() = runTest {
        val feature = FakePulsarFeature(sampleVibe(name = "Initial Vibe"))
        val producer = PulsarMetadataProducer(feature, testScope())
        assertEquals("Initial Vibe", producer.titleFlow.value)
    }

    @Test fun `subtitle is initial album title`() = runTest {
        val feature = FakePulsarFeature(sampleVibe(album = Album.ZERO_TO_ONE))
        val producer = PulsarMetadataProducer(feature, testScope())
        assertEquals(Album.ZERO_TO_ONE.title, producer.subtitleFlow.value)
    }

    @Test fun `title updates when vibe changes`() = runTest {
        val feature = FakePulsarFeature(sampleVibe(name = "First"))
        val producer = PulsarMetadataProducer(feature, testScope())
        feature.vibeFlow.value = sampleVibe(name = "Second")
        assertEquals("Second", producer.titleFlow.value)
    }
}
