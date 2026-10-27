package org.balch.orpheus.features.pulsar.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.features.pulsar.anonmalies.FogHornAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import org.balch.orpheus.features.pulsar.vibes.VibeCatalogScan

class VibeDiffTest {
    // A LIVE vibe: EXCLUSIVE and non-LIVE sources can be cut from the build.
    private val fixture: Vibe = DogHouseVibe().vibe
    private val minimal = mkMinimalVibe("Diff")

    private fun Vibe.withSection(section: Section) =
        copy(arrangement = arrangement!!.copy(sections = listOf(section)))

    @Test fun leafChangeIsOneRow() {
        val edm = fixture.tracks[2].engineEdm
        val edited = fixture.copy(
            tracks = fixture.tracks.toMutableList().also {
                it[2] = it[2].copy(engineEdm = edm.copy(harmonics = edm.harmonics + 0.1f))
            },
        )
        val changes = diffVibe(fixture, edited)
        assertEquals(1, changes.size)
        assertEquals("tracks[2].engineEdm.harmonics", changes[0].path)
        assertEquals(edm.harmonics, changes[0].old.let { (it as JsonPrimitive).content.toFloat() })
        assertEquals(edm.harmonics + 0.1f, (changes[0].new as JsonPrimitive).content.toFloat())
    }

    @Test fun lickIsAtomic() {
        val a = minimal.copy(lick = Lick(listOf(LickStep(0, 1f), LickStep(2, 1f))))
        val b = minimal.copy(lick = Lick(listOf(LickStep(0, 1f), LickStep(3, 1f))))
        assertEquals(listOf("lick"), diffVibe(a, b).map { it.path })
    }

    @Test fun primitiveArrayIsAtomic() {
        val sec = minimal.arrangement!!.sections[0]
        val a = minimal.withSection(sec.copy(trackOverrides = mapOf(0 to TrackSectionOverride(hits = listOf(1, 2)))))
        val b = minimal.withSection(sec.copy(trackOverrides = mapOf(0 to TrackSectionOverride(hits = listOf(1, 3)))))
        assertEquals(listOf("arrangement.sections[0].trackOverrides.0.hits"), diffVibe(a, b).map { it.path })
    }

    @Test fun lengthChangeReplacesWhole() {
        val a = minimal.copy(anomalies = listOf(FogHornAnomaly(probability = 0.5f)))
        val b = minimal.copy(anomalies = listOf(FogHornAnomaly(probability = 0.5f), VoidAnomaly()))
        assertEquals(listOf("anomalies"), diffVibe(a, b).map { it.path })
    }

    @Test fun roundTripsAcrossCatalog() {
        val vibes = VibeCatalogScan.allProviders(subpackages = true).map { it.vibe }
        assertTrue(vibes.size >= 2, "catalog scan found ${vibes.size} vibes")
        vibes.zipWithNext().forEach { (a, b) ->
            val changes = diffVibe(a, b)
            assertEquals(b, a.patched(changes.map { it.path to it.new }), "${a.name} -> ${b.name}")
        }
    }

    @Test fun nullRemovesMapKey() {
        val path = "arrangement.sections[0].trackOverrides.0"
        val sec = minimal.arrangement!!.sections[0]
        val original = minimal.withSection(sec.copy(trackOverrides = mapOf(1 to TrackSectionOverride(density = 0.2f))))
        val added = original.patched(
            listOf(path to vibeTreeJson.encodeToJsonElement(TrackSectionOverride.serializer(), TrackSectionOverride(morph = 0.3f))),
        )
        assertEquals(setOf(0, 1), added.arrangement!!.sections[0].trackOverrides!!.keys)
        assertEquals(original, added.patched(listOf(path to JsonNull)))
    }

    @Test fun addedOverrideEntryStripsMixerFields() {
        val base = minimal
        val sec = base.arrangement!!.sections[0]
        val edited = base.withSection(
            sec.copy(trackOverrides = mapOf(3 to TrackSectionOverride(volume = 0.5f, morph = 0.3f))),
        )
        val path = "arrangement.sections[0].trackOverrides"
        assertEquals(listOf("$path.3.volume"), mixerChanges(base, edited))
        val rows = diffVibe(base, edited.withMixerFieldsOf(base))
        assertEquals(listOf(path), rows.map { it.path })
        val value = (rows[0].new as kotlinx.serialization.json.JsonObject)["3"] as kotlinx.serialization.json.JsonObject
        assertTrue(value["volume"] == null || value["volume"] == JsonNull)
        assertEquals(0.3f, (value["morph"] as JsonPrimitive).content.toFloat())
    }

    @Test fun nullDelayToObjectIsStripped() {
        val base = minimal
        val sec = base.arrangement!!.sections[0]
        val edited = base.withSection(sec.copy(delay = org.balch.orpheus.features.pulsar.models.SectionDelay(timeA = 0.2f)))
        assertEquals(listOf("arrangement.sections[0].delay.timeA"), mixerChanges(base, edited))
        val stripped = edited.withMixerFieldsOf(base)
        assertEquals(emptyList(), mixerChanges(base, stripped))
        assertEquals(null, stripped.arrangement!!.sections[0].delay?.timeA)
        assertEquals(emptyList(), diffVibe(base, stripped))
    }

    @Test fun strippedEmptyObjectReturnsToNullWhereBaseHadNone() {
        val base = minimal
        val sec = base.arrangement!!.sections[0]
        val edited = base.withSection(sec.copy(delay = org.balch.orpheus.features.pulsar.models.SectionDelay(timeA = 0.2f, timeB = 0.3f)))
        assertNull(edited.withMixerFieldsOf(base).arrangement!!.sections[0].delay)
    }

    @Test fun nonMixerChangeHasNoMixerChanges() {
        val edm = minimal.tracks[0].engineEdm
        val edited = minimal.copy(
            tracks = minimal.tracks.toMutableList().also { it[0] = it[0].copy(engineEdm = edm.copy(harmonics = 0.9f)) },
        )
        assertEquals(emptyList(), mixerChanges(minimal, edited))
        assertEquals(edited, edited.withMixerFieldsOf(minimal))
    }

    @Test fun roleSubtypeSwapIsOneRowAndRoundTrips() {
        val a = minimal.tracks[0]
        val chordal = a.copy(role = org.balch.orpheus.features.pulsar.models.TrackRole.Chordal())
        val from = minimal.copy(tracks = minimal.tracks.toMutableList().also { it[0] = chordal })
        val to = minimal.copy(tracks = minimal.tracks.toMutableList().also { it[0] = a })
        val rows = diffVibe(from, to).filter { it.path.startsWith("tracks[0].role") }
        assertEquals(listOf("tracks[0].role"), rows.map { it.path })
        assertEquals(to, from.patched(diffVibe(from, to).map { it.path to it.new }))
    }

    @Test fun nullAndAbsentKeyAreEqual() {
        val a = kotlinx.serialization.json.buildJsonObject { put("x", JsonNull) }
        val b = kotlinx.serialization.json.buildJsonObject { }
        assertEquals(emptyList(), diffTree(a, b))
    }

    @Test fun keyStepOnPrimitiveThrows() {
        val tree = kotlinx.serialization.json.buildJsonObject { put("x", JsonPrimitive(1)) }
        kotlin.test.assertFailsWith<IllegalStateException> { tree.patch("x.y", JsonPrimitive(2)) }
    }

    @Test fun atReadsPaths() {
        val tree = fixture.toTree()
        assertNotNull(tree.at("tracks[2].engineEdm.harmonics"))
        assertNull(tree.at("tracks[2].nope"))
        assertNull(tree.at("tracks[99]"))
    }

    @Test fun mixerPaths() {
        listOf(
            "tracks[1].engineEdm.volume", "tracks[1].engineSpace.reverbSend", "tracks[7].engineEdm.delaySend",
            "tracks[3].pan", "energy", "complexity", "mood", "space",
            "effects.delayTimeA", "effects.delayTimeB", "effects.delayFeedback",
            "arrangement.sections[2].trackOverrides.4.volume",
            "arrangement.sections[2].trackOverrides.4.reverbSend",
            "arrangement.sections[2].trackOverrides.4.delaySend",
            "arrangement.sections[0].macroOverrides.energy",
            "arrangement.sections[0].rampTo.space",
            "arrangement.sections[1].delay.timeA", "arrangement.sections[1].delay.feedback",
        ).forEach { assertTrue(isMixerPath(it), it) }
        listOf(
            "tracks[0].engineEdm.harmonics", "levelDb", "arrangement.sections[0].macroOverrides.volume",
        ).forEach { assertFalse(isMixerPath(it), it) }
    }

    @Test fun atomicPaths() {
        listOf("lick", "bassLine", "band", "genre.customProgression", "arrangement.sections[4].customProgression")
            .forEach { assertTrue(isAtomicPath(it), it) }
        assertFalse(isAtomicPath("tracks[0].engineEdm.harmonics"))
    }

    @Test fun structure() {
        val two = minimal.copy(arrangement = minimal.arrangement!!.copy(sections = listOf(Section(name = "loop"), Section(name = "b"))))
        assertNotNull(structureRefusal(minimal, two))
        assertNotNull(structureRefusal(minimal, minimal.withSection(Section(name = "renamed"))))
        val edm = minimal.tracks[0].engineEdm
        val engineEdit = minimal.copy(
            tracks = minimal.tracks.toMutableList().also {
                it[0] = it[0].copy(engineEdm = edm.copy(harmonics = 0.9f))
            },
        )
        assertNull(structureRefusal(minimal, engineEdit))
    }

    @Test fun structureRefusalLeavesTheNewVibePointerToTheTool() {
        val two = minimal.copy(arrangement = minimal.arrangement!!.copy(sections = listOf(Section(name = "loop"), Section(name = "b"))))
        assertFalse("brand new vibe" in structureRefusal(minimal, two)!!)
    }

    @Test fun patchErrorsNameTheKindNotTheContents() {
        val tree = fixture.toTree()
        val dotted = kotlin.test.assertFailsWith<IllegalStateException> { tree.patch("tracks.2.pan", JsonPrimitive(0.5f)) }
        assertEquals("'tracks' is a list; use tracks[2]", dotted.message)
        val number = kotlin.test.assertFailsWith<IllegalStateException> { tree.patch("energy[0]", JsonPrimitive(1)) }
        assertEquals("'energy' is a number or flag, not a list", number.message)
        val key = kotlin.test.assertFailsWith<IllegalStateException> { tree.patch("bpm.x", JsonPrimitive(1)) }
        assertEquals("'bpm' is a number or flag, so it has no 'x'", key.message)
    }
}
