package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BandPresets
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BandInvariantTest {

    private val twoMembers = listOf(
        BandMember(name = "A", tracks = listOf(0)),
        BandMember(name = "B", tracks = listOf(1)),
    )

    @Test
    fun `band with wrong-size handoff matrix throws`() {
        // 2 members => matrix must be 4 floats; give it 3
        assertFailsWith<IllegalArgumentException> {
            Band(
                members = twoMembers,
                handoffMatrix = listOf(0f, 0f, 0f),
                pullInMatrix = listOf(0f, 0f, 0f, 0f),
            )
        }
    }

    @Test
    fun `band with correct-size matrices constructs`() {
        Band(
            members = twoMembers,
            handoffMatrix = listOf(0f, 0f, 0f, 0f),
            pullInMatrix = listOf(0f, 0f, 0f, 0f),
        )
    }

    @Test
    fun `memberless band throws`() {
        // 0x0 matrices satisfy the size checks, so nothing else would catch a cast with nobody in it.
        assertFailsWith<IllegalArgumentException> {
            Band(members = emptyList(), handoffMatrix = emptyList(), pullInMatrix = emptyList())
        }
    }

    // ── BandPresets ─────────────────────────────────────────────────────────────

    @Test
    fun `every preset builds a band whose matrices match its member count`() {
        // Band.init does the arithmetic; this pins that each preset actually satisfies it, since a
        // preset shipping a mismatched row is a compile-clean landmine for its first caller.
        val presets = mapOf(
            "quartet" to BandPresets.quartet(
                kit = listOf(0, 1, 2, 7), bass = listOf(3), lead = listOf(4), colour = listOf(5, 6),
            ),
            "tradingLeads" to BandPresets.tradingLeads(
                kit = listOf(0, 1, 2), bass = listOf(3), leadA = listOf(4, 5), leadB = listOf(6, 7),
            ),
            "twoVoiceTexture" to BandPresets.twoVoiceTexture(
                bed = listOf(0, 1, 2, 3), voiceA = listOf(4, 5), voiceB = listOf(6, 7),
            ),
        )
        presets.forEach { (name, band) ->
            val n = band.members.size
            assertEquals(n * n, band.handoffMatrix.size, "$name handoffMatrix")
            assertEquals(n * n, band.pullInMatrix.size, "$name pullInMatrix")
        }
    }

    @Test
    fun `every preset leaves at least two members able to take the lead`() {
        // select_next_lead refuses to hand the solo to an alwaysActive member, so a preset with
        // fewer than two non-anchor members deadlocks into handing the lead back to the drums.
        val bands = listOf(
            BandPresets.quartet(listOf(0, 1, 2), listOf(3), listOf(4), listOf(5, 6, 7)),
            BandPresets.tradingLeads(listOf(0, 1, 2), listOf(3), listOf(4, 5), listOf(6, 7)),
            BandPresets.twoVoiceTexture(listOf(0, 1, 2, 3), listOf(4, 5), listOf(6, 7)),
        )
        bands.forEach { band ->
            val soloists = band.members.count { !it.alwaysActive }
            assertTrue(soloists >= 2, "${band.members.map { it.name }} has only $soloists soloist(s)")
            assertTrue(
                band.members.any { it.alwaysActive },
                "${band.members.map { it.name }} has no always-active anchor, so everything ducks",
            )
        }
    }

    @Test
    fun `preset rejects a track claimed by two members`() {
        // The engine's track-to-member lookup keeps the LAST claimant, silently dropping the other
        // member's ducking — so a duplicate has to fail loudly at authoring time.
        val e = assertFailsWith<IllegalArgumentException> {
            BandPresets.quartet(
                kit = listOf(0, 1, 2), bass = listOf(3), lead = listOf(4), colour = listOf(4, 5),
            )
        }
        assertContains(e.message.orEmpty(), "[4]")
    }

    @Test
    fun `preset rejects an empty member and an out-of-range track`() {
        assertFailsWith<IllegalArgumentException> {
            BandPresets.quartet(
                kit = listOf(0, 1, 2), bass = emptyList(), lead = listOf(4), colour = listOf(5),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BandPresets.twoVoiceTexture(bed = listOf(0), voiceA = listOf(4), voiceB = listOf(8))
        }
    }

    // ── A soloMode is inert without a band ──────────────────────────────────────

    @Test
    fun `vibe with a solo section and no band throws, naming the section`() {
        val e = assertFailsWith<IllegalArgumentException> {
            soloVibe(band = null)
        }
        // The author has to be able to find the offending section from the message alone.
        assertContains(e.message.orEmpty(), "1 'jam'")
        assertContains(e.message.orEmpty(), "BandPresets")
    }

    @Test
    fun `vibe with a solo section and a band constructs`() {
        soloVibe(
            band = BandPresets.quartet(
                kit = listOf(0, 1, 2, 7), bass = listOf(3), lead = listOf(4), colour = listOf(5, 6),
            ),
        )
    }

    @Test
    fun `bandless vibe with no solo section still constructs`() {
        // The require must key off soloMode, not off the arrangement — most vibes ship no band.
        soloVibe(band = null, soloMode = null)
    }

    private fun soloVibe(band: Band?, soloMode: SoloMode? = SoloMode.Jam(probability = 0.8f)): Vibe =
        Vibe(
            name = "solo fixture",
            tracks = List(8) {
                TrackVoice(
                    engineEdm = OrpheusEngine(engineId = OrpheusEngineId.VA),
                    engineSpace = OrpheusEngine(engineId = OrpheusEngineId.VA),
                )
            },
            band = band,
            bpm = 120f,
            rootNote = RootNote.A,
            scaleType = ScaleType.MINOR,
            genre = GenreProfile(
                swingAmount = 0f,
                ghostProbability = 0f,
                noteRangeLow = 36,
                noteRangeHigh = 72,
                rhythmDensity = RhythmPattern.SPARSE.density,
            ),
            arrangement = Arrangement(
                sections = listOf(
                    Section(name = "groove"),
                    Section(name = "jam", soloMode = soloMode),
                ),
            ),
        )
}
