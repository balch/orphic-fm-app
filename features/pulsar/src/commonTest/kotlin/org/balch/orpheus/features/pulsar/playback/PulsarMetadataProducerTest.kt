package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.pulsar.PulsarSession
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

private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
    override val main get() = d
    override val io get() = d
    override val default get() = d
    override val unconfined get() = d
}

class PulsarMetadataProducerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun testDispatchers(): DispatcherProvider =
        TestDispatchers(UnconfinedTestDispatcher())

    /**
     * Real [PulsarSession] seeded with [initial], not a fake. Mirrors production, where the
     * producer only ever reads `PulsarSession.vibeFlow`.
     */
    private fun buildProducer(initial: Vibe): Pair<PulsarSession, PulsarMetadataProducer> =
        buildProducer().also { (session, _) -> session.updateVibe(initial) }

    /** No vibe pushed — the AppScope window before PulsarViewModel is ever constructed. */
    private fun buildProducer(): Pair<PulsarSession, PulsarMetadataProducer> {
        val dispatchers = testDispatchers()
        val scope = AppCoroutineScope(dispatchers)
        val session = PulsarSession(NoOpSynthEngine(), scope, dispatchers)
        return session to PulsarMetadataProducer(session, scope, dispatchers)
    }

    @Test fun `title is initial vibe name`() = runTest {
        val (_, producer) = buildProducer(sampleVibe(name = "Initial Vibe"))
        assertEquals("Initial Vibe", producer.titleFlow.value)
    }

    @Test fun `subtitle is initial album title`() = runTest {
        val (_, producer) = buildProducer(sampleVibe(album = Album.ZERO_TO_ONE))
        assertEquals(Album.ZERO_TO_ONE.title, producer.subtitleFlow.value)
    }

    @Test fun `title updates when vibe changes`() = runTest {
        val (session, producer) = buildProducer(sampleVibe(name = "First"))
        session.updateVibe(sampleVibe(name = "Second"))
        assertEquals("Second", producer.titleFlow.value)
    }

    @Test fun `no vibe yet publishes the neutral defaults rather than a placeholder`() = runTest {
        val (session, producer) = buildProducer()
        assertEquals("Orpheus", producer.titleFlow.value, "a placeholder vibe must never reach the media session")
        assertEquals("", producer.subtitleFlow.value)
        assertEquals(null, producer.artworkPngFlow.value)

        session.updateVibe(sampleVibe(name = "Real"))
        assertEquals("Real", producer.titleFlow.value, "the first real vibe still lands")
    }
}
