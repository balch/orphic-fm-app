package org.balch.orpheus.features.pulsar

import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.chords
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PulsarVibeSectionValidationTest {
    @Test fun sectionCustomProgressionRejectsDegreeAbove6() {
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", customProgression = chords(0, 9))
        }
    }

    @Test fun sectionCustomProgressionRejectsEmptyList() {
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", customProgression = emptyList())
        }
    }

    @Test fun sectionCustomProgressionRejectsSizeAbove8() {
        val nineDegrees = chords(0, 1, 2, 3, 4, 5, 6, 0, 1)
        check(nineDegrees.size == 9)
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", customProgression = nineDegrees)
        }
    }

    @Test fun sectionChordsPerBarRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", chordsPerBar = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", chordsPerBar = 0)
        }
    }

    @Test fun sectionValidOverridesConstruct() {
        // Must not throw
        Section(name = "ok", customProgression = chords(0, 5, 3, 4), chordsPerBar = 2)
        Section(name = "ok-null", customProgression = null, chordsPerBar = null)
    }

    @Test fun genreProfileCustomProgressionStillValidates() {
        // Regression: shared helper must still be called from GenreProfile.init
        assertFailsWith<IllegalArgumentException> {
            GenreProfile(
                swingAmount = 0.1f,
                ghostProbability = 0.1f,
                noteRangeLow = 36,
                noteRangeHigh = 60,
                rhythmDensity = 0.5f,
                customProgression = chords(7),
            )
        }
    }
}
