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

    @Test fun sectionCustomProgressionRejectsSizeAbove12() {
        // 12 is the boundary (raised from 8 when longer progressions landed) — must construct.
        Section(name = "ok-12", customProgression = chords(0, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4))
        val thirteenDegrees = chords(0, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4, 5)
        check(thirteenDegrees.size == 13)
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", customProgression = thirteenDegrees)
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

    @Test fun sectionRecencyDecayRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", recencyDecay = 1.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            Section(name = "bad", recencyDecay = -0.1f)
        }
    }

    @Test fun sectionRecencyDecayAcceptsBothEndpoints() {
        // 0.0 fully suppresses the section just left; 1.0 disables the penalty.
        Section(name = "ban", recencyDecay = 0f)
        Section(name = "off", recencyDecay = 1f)
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
