package org.balch.orpheus.tools.vibecodegen

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertTrue

class VibeFileGeneratorTest {

    @Test
    fun `generated file is a real provider with imports collapsed to simple names`() {
        val vibe = Vibe(
            name = "Test Vibe",
            bpm = 120f,
            rootNote = RootNote.C,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f,
                ghostProbability = 0f,
                noteRangeLow = 36,
                noteRangeHigh = 72,
                rhythmDensity = 0.5f,
            ),
            tracks = List(8) {
                OrpheusEngine(engineId = OrpheusEngineId.BD).let { engine ->
                    TrackVoice(engineEdm = engine, engineSpace = engine)
                }
            },
        )

        val output = generateVibeFile(vibe, "TestVibe").toString()

        assertTrue(output.contains("class TestVibe : VibeProvider"), output)
        assertTrue(output.contains("override val name: String = \"Test Vibe\""), output)
        assertTrue(output.contains("override val vibe: Vibe by lazy {"), output)
        assertTrue(output.contains("import org.balch.orpheus.features.pulsar.models.Vibe"), output)
        assertTrue(output.contains("import dev.zacsweers.metro.binding"), output)
        assertTrue(
            output.contains("@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())"),
            output,
        )
    }
}
