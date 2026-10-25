package org.balch.orpheus.features.ai.composer

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.ai.tools.decodeVibe
import org.balch.orpheus.features.ai.tools.vibeApplyJson
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.CastPinRecord
import org.balch.orpheus.features.pulsar.models.CastProvenance
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.chordFollow
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VibeCasterTest {

    private fun cast(mode: CastMode, seed: Long, pins: List<CastPin> = emptyList()) =
        VibeCaster.cast(CastRequest(mode = mode, seed = seed, pins = pins))

    private val edmTuple: (Vibe) -> List<OrpheusEngineId> = { v -> v.tracks.map { it.engineEdm.engineId } }

    private val FX_NOTE = "speaks below energy 0.4"
    private val NOTCH_NOTE = "mid-energy notch"

    @Test
    fun sameSeedSameSkeleton() {
        for (mode in CastMode.entries) {
            for (seed in listOf(0L, 1L, 42L, -7L, Long.MAX_VALUE)) {
                assertEquals(cast(mode, seed), cast(mode, seed), "$mode seed $seed rolled two different skeletons")
            }
        }
        val tagged = CastRequest(CastMode.ALL_ROLLED, seed = 9L, styleTags = setOf("reggae", "psych"))
        assertEquals(VibeCaster.cast(tagged), VibeCaster.cast(tagged), "a tagged request rolled two different skeletons")
    }

    // The cast block is enough to cast the same skeleton again from an archived vibe.
    @Test
    fun castRecordsItsProvenance() {
        val pins = listOf(CastPin(track = 4, engine = OrpheusEngineId.VOWEL_FOF), CastPin(track = 6, role = "comp"))
        for (mode in CastMode.entries) {
            for (seed in 1L..50L) {
                val r = VibeCaster.cast(CastRequest(mode, seed, styleTags = setOf("psych", "reggae"), pins = pins))
                val record = assertNotNull(r.vibe.cast, "$mode seed $seed: no cast block")
                assertEquals(CastProvenance(seed, mode.name, listOf("psych", "reggae"), listOf(CastPinRecord(4, null, OrpheusEngineId.VOWEL_FOF), CastPinRecord(6, "comp", null))), record)
                val decoded = decodeVibe(vibeApplyJson, vibeApplyJson.encodeToString(Vibe.serializer(), r.vibe)).getOrThrow()
                val again = decoded.cast!!.let { c ->
                    VibeCaster.cast(CastRequest(CastMode.valueOf(c.mode), c.seed, c.styleTags.toSet(), c.pins.map { CastPin(it.track, it.role, it.engine) }))
                }
                assertEquals(r, again, "$mode seed $seed: the archived cast block does not rebuild the skeleton")
            }
        }
    }

    @Test
    fun differentSeedsVary() {
        val rolls = (1L..200L).map { cast(CastMode.ALL_ROLLED, it).vibe }
        val tuples = rolls.map(edmTuple).toSet()
        assertTrue(tuples.size >= 150, "only ${tuples.size} distinct 8-track EDM engine tuples in 200 rolls, want 150+")
        val bdOnTrack0 = rolls.count { it.tracks[0].engineEdm.engineId == OrpheusEngineId.BD }
        assertTrue(bdOnTrack0 <= 150, "track 0 was BD in $bdOnTrack0 of 200 rolls, want at most 150")
    }

    @Test
    fun bedFixedUsesLibrary() {
        val used = mutableSetOf<String>()
        for (seed in 1L..200L) {
            val tracks = cast(CastMode.BED_FIXED, seed).vibe.tracks
            val bed = DRONE_BEDS.firstOrNull { tracks.subList(BED_FIRST_TRACK, BED_FIRST_TRACK + it.tracks.size) == it.tracks }
            assertNotNull(bed, "BED_FIXED seed $seed: tracks $BED_FIRST_TRACK+ match no drone-bed recipe")
            used += bed.name
        }
        assertEquals(DRONE_BEDS.map { it.name }.toSet(), used, "200 seeds never rolled some drone beds")
    }

    // A bed sits under the bass, near the cast's pads, not on top of the mix.
    @Test
    fun noBedIsLouderThanTheBass() {
        val bass = PART_SHAPES.getValue(Part.BASS).volume
        DRONE_BEDS.forEach { bed ->
            bed.tracks.flatMap { listOf(it.engineEdm, it.engineSpace) }.forEach { e ->
                assertTrue(e.volume < bass, "bed '${bed.name}' ${e.engineId} volume ${e.volume} is not under the bass ($bass)")
                assertTrue(e.volume <= 0.45f, "bed '${bed.name}' ${e.engineId} volume ${e.volume} starts above 0.45")
            }
        }
    }

    @Test
    fun theoryInvariantsHoldFor500Seeds() {
        for (mode in CastMode.entries) {
            for (seed in 1L..500L) {
                val v = cast(mode, seed).vibe
                val at = "$mode seed $seed"
                assertRegistersApart(v, at)
                assertBassPlaysTheRoot(v, at)
                assertAtMostOneArpeggiator(v, at)
                assertPadsFollowAndSustain(v, at)
                assertEdmAndSpacePaired(v, at)
                assertNonBassVoicesHaveOwnRange(v, at)
            }
        }
    }

    @Test
    fun pinsAreHonoured() {
        for (mode in CastMode.entries) {
            for (seed in 1L..100L) {
                val r = cast(mode, seed, listOf(CastPin(track = 5, engine = OrpheusEngineId.VOWEL_FOF)))
                assertEquals(OrpheusEngineId.VOWEL_FOF, r.vibe.tracks[5].engineEdm.engineId, "$mode seed $seed lost the track 5 pin")
                assertTrue("pinned" in r.summary[5], "$mode seed $seed: the summary does not mark track 5 pinned: ${r.summary[5]}")
                val padPin = cast(mode, seed, listOf(CastPin(track = 4, role = "pad"))).vibe.tracks[4]
                val comping = (padPin.role as? TrackRole.Chordal)?.comping
                assertEquals(CompingStyle.PAD, comping?.style, "$mode seed $seed: a PAD role pin on track 4 did not cast a pad")
                val at = "$mode seed $seed pinned"
                assertRegistersApart(r.vibe, at)
                assertEdmAndSpacePaired(r.vibe, at)
                assertNonBassVoicesHaveOwnRange(r.vibe, at)
            }
        }
    }

    @Test
    fun tagOrderDoesNotMatter() {
        for (mode in CastMode.entries) {
            for (seed in 1L..100L) {
                val a = VibeCaster.cast(CastRequest(mode, seed, styleTags = linkedSetOf("reggae", "funk", "psych")))
                val b = VibeCaster.cast(CastRequest(mode, seed, styleTags = linkedSetOf("psych", "funk", "reggae")))
                assertEquals(a, b, "$mode seed $seed: the same style tags in another order rolled a different skeleton")
            }
        }
    }

    @Test
    fun pinnedPadClearsTheBed() {
        for (seed in 1L..200L) {
            val v = cast(CastMode.BED_FIXED, seed, listOf(CastPin(track = 4, role = "pad"))).vibe
            assertEquals(CompingStyle.PAD, (v.tracks[4].role as? TrackRole.Chordal)?.comping?.style, "seed $seed lost the pad pin")
            assertRegistersApart(v, "BED_FIXED seed $seed, pad pinned on 4")
        }
    }

    @Test
    fun rolledKnobsAreWhatPlays() {
        val bedVoices = DRONE_BEDS.flatMap { it.tracks }.toSet()
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                cast(mode, seed).vibe.tracks.forEachIndexed { i, t ->
                    if (t in bedVoices) return@forEachIndexed
                    for (e in listOf(t.engineEdm, t.engineSpace)) {
                        val played = effective(e, t.macroMap)
                        val written = listOf(e.harmonics, e.timbre, e.morph)
                        assertTrue(
                            played.zip(written).all { (p, w) -> abs(p - w) < 0.011f },
                            "$mode seed $seed track $i ${e.engineId}: writes h/t/m $written but plays $played at mid macros",
                        )
                    }
                }
            }
        }
    }

    // Render lifts a knob below the engine's floor, so the cast never writes one there (VA timbre 0.35).
    @Test
    fun castKnobsSitOnOrAboveThePlayabilityFloors() {
        val bedVoices = DRONE_BEDS.flatMap { it.tracks }.toSet()
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                cast(mode, seed).vibe.tracks.filter { it !in bedVoices }.forEach { t ->
                    for (e in listOf(t.engineEdm, t.engineSpace)) {
                        val f = EngineMirror.floors(e.engineId)
                        assertTrue(
                            e.harmonics >= f.harmonics && e.timbre >= f.timbre && e.morph >= f.morph,
                            "$mode seed $seed ${e.engineId} writes h/t/m ${e.harmonics}/${e.timbre}/${e.morph} under its floors $f",
                        )
                    }
                }
            }
        }
    }

    // A knob every palette voice of an engine fixes to one point (VOWEL_FOF timbre, CHD morph) is
    // pinned in the EDM and the SPACE slot alike.
    @Test
    fun pointRangeKnobsArePinnedInBothSlots() {
        fun fixed(id: OrpheusEngineId, range: (PaletteVoice) -> ClosedFloatingPointRange<Float>) =
            VOICE_PALETTE.filter { it.engine == id }.let { vs -> vs.isNotEmpty() && vs.all { range(it).start == range(it).endInclusive } }
        val bedVoices = DRONE_BEDS.flatMap { it.tracks }.toSet()
        var checked = 0
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                cast(mode, seed).vibe.tracks.filter { it !in bedVoices }.forEach { t ->
                    listOf("EDM" to t.engineEdm, "SPACE" to t.engineSpace).forEach { (slot, e) ->
                        val at = "$mode seed $seed $slot ${e.engineId}"
                        if (fixed(e.engineId) { it.timbre }) { checked++; assertTrue(e.pinTimbre, "$at: point-range timbre not pinned") }
                        if (fixed(e.engineId) { it.morph }) { checked++; assertTrue(e.pinMorph, "$at: point-range morph not pinned") }
                        if (fixed(e.engineId) { it.harmonics }) { checked++; assertTrue(e.pinHarmonics, "$at: point-range harmonics not pinned") }
                    }
                }
            }
        }
        assertTrue(checked > 100, "precondition: only $checked point-range knobs were cast")
    }

    @Test
    fun skeletonAppliesCleanly() {
        for (mode in CastMode.entries) {
            for (seed in 1L..50L) {
                val r = cast(mode, seed)
                assertEquals(8, r.summary.size, "$mode seed $seed: want one summary line per track")
                assertNull(r.vibe.arrangement, "$mode seed $seed: a skeleton carries no arrangement")
                assertNull(r.vibe.lick, "$mode seed $seed: a skeleton carries no lick")
                val decoded = decodeVibe(vibeApplyJson, vibeApplyJson.encodeToString(Vibe.serializer(), r.vibe))
                assertTrue(decoded.isSuccess, "$mode seed $seed fails apply validation: ${decoded.exceptionOrNull()?.message}")
                assertEquals(r.vibe, decoded.getOrThrow(), "$mode seed $seed changes on a JSON round trip")
            }
        }
    }

    // Judged by EngineMirror, the Kotlin copy of the notch and track 7's gate that
    // EngineMirrorParityTest holds to orpheus_unit_pulsar.cpp:728-733, 3720-3738, 5116-5131.
    @Test
    fun everyCastTrackSoundsAtDefaultMacros() {
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                val r = cast(mode, seed)
                val v = r.vibe
                v.tracks.forEachIndexed { i, t ->
                    if (EngineMirror.sounds(i, t, v.energy, v.complexity)) return@forEachIndexed
                    val at = "$mode seed $seed track $i (${r.summary[i]})"
                    // No role sounds on track 7 at mid energy, so only it may wait, and it must say so.
                    assertEquals(EngineMirror.FX_TRACK, i, "$at is silent at energy ${v.energy}, complexity ${v.complexity}")
                    assertTrue(FX_NOTE in r.summary[i], "$at: a quiet FX slot must say when it speaks")
                    assertTrue(EngineMirror.sounds(i, t, 0.3f, v.complexity), "$at: the note says energy 0.3 opens it, but it stays shut")
                }
            }
        }
    }

    @Test
    fun aPinnedQuietPartSaysWhenItSounds() {
        // ALL_ROLLED: in BED_FIXED a lead pinned beside a wide bed is refused for want of a register.
        for (mode in listOf(CastMode.ALL_ROLLED)) {
            for (seed in 1L..50L) {
                val r = cast(mode, seed, listOf(CastPin(track = 6, role = "lead")))
                val t = r.vibe.tracks[6]
                assertTrue(!EngineMirror.sounds(6, t, r.vibe.energy, r.vibe.complexity), "$mode seed $seed: precondition, a lick-less lead on 6 sits in the notch")
                assertTrue(NOTCH_NOTE in r.summary[6], "$mode seed $seed: the summary hides the notch: ${r.summary[6]}")
                assertTrue(EngineMirror.sounds(6, t, 0.3f, r.vibe.complexity), "$mode seed $seed: the lead should sound at energy 0.3")
            }
        }
    }

    // The singer's vowel rise lands on the knob that is the vowel: harmonics on VOWEL_FOF, morph on SPK.
    @Test
    fun singersRiseOnTheirVowelKnob() {
        var fof = 0
        var spk = 0
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                // A lead pin rolls more singers; BED_FIXED refuses some (an SPK lead finds no bed that fits).
                val pins = if (mode == CastMode.ALL_ROLLED) listOf(CastPin(track = 4, role = "lead")) else emptyList()
                for (t in cast(mode, seed, pins).vibe.tracks) {
                    for (e in listOf(t.engineEdm, t.engineSpace).filter { it.vibratoDepth > 0f }) {
                        when (e.engineId) {
                            OrpheusEngineId.VOWEL_FOF -> {
                                fof++
                                assertTrue(e.harmonicsRise > 0f && e.morphRise == 0f, "$mode seed $seed: VOWEL_FOF rises morph (voice type), not harmonics (vowel)")
                            }
                            OrpheusEngineId.SPK -> {
                                spk++
                                assertTrue(e.morphRise > 0f && e.harmonicsRise == 0f, "$mode seed $seed: SPK's vowel is morph")
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
        assertTrue(fof > 0 && spk > 0, "precondition: 400 casts rolled $fof VOWEL_FOF and $spk SPK singers")
    }

    @Test
    fun stepCountFollowsTheKindOfVibe() {
        val table = listOf(
            Triple(emptyList<String>(), true, false) to 64,
            Triple(listOf("ambient"), false, false) to 64,
            Triple(emptyList<String>(), null, true) to 64,
            Triple(listOf("electro"), false, true) to 16,
            Triple(listOf("electro"), null, false) to 16,
            Triple(listOf("electro", "funk"), false, false) to 32,
            Triple(listOf("reggae"), false, false) to 32,
            Triple(emptyList<String>(), null, false) to 32,
        )
        for ((input, want) in table) {
            assertEquals(want, castStepCount(input.first, input.second, input.third), "$input")
        }
    }

    @Test
    fun castWithALeadPlaysSixtyFourSteps() {
        for (seed in 1L..50L) {
            val v = cast(CastMode.ALL_ROLLED, seed, listOf(CastPin(track = 4, role = "lead"))).vibe
            assertEquals(64, v.stepCount, "seed $seed")
            assertEquals(4, v.genre.chordsPerBar, "seed $seed")
        }
    }

    @Test
    fun castStepCountAndChordsAgree() {
        val tagSets = listOf(emptySet(), setOf("ambient"), setOf("electro"), setOf("electro", "funk"), setOf("reggae"))
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                val tags = tagSets[(seed % tagSets.size).toInt()]
                val v = VibeCaster.cast(CastRequest(mode, seed, styleTags = tags)).vibe
                assertTrue(v.stepCount in setOf(16, 32, 64), "$mode seed $seed: ${v.stepCount}")
                assertEquals(v.stepCount / 16, v.genre.chordsPerBar, "$mode seed $seed")
            }
        }
    }

    @Test
    fun leadLineDoesNotMoveTheRoll() {
        for (seed in 1L..50L) {
            val on = VibeCaster.cast(CastRequest(CastMode.ALL_ROLLED, seed, leadLine = true)).vibe
            val off = VibeCaster.cast(CastRequest(CastMode.ALL_ROLLED, seed, leadLine = false)).vibe
            assertEquals(on.tracks, off.tracks, "seed $seed")
        }
    }

    @Test
    fun leadsAndSingersAreDoubled() {
        var leads = 0
        var singers = 0
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                val pins = if (mode == CastMode.ALL_ROLLED) listOf(CastPin(track = 4, role = "lead")) else emptyList()
                val v = cast(mode, seed, pins).vibe
                for ((i, t) in v.tracks.withIndex()) {
                    for (e in listOf(t.engineEdm, t.engineSpace)) {
                        val at = "$mode seed $seed track $i ${e.engineId}"
                        when {
                            // A drone bed's tracks are hand-authored, so only rolled casts are held to the rule.
                            mode == CastMode.BED_FIXED -> if (i in 0..3 && e.vibratoDepth == 0f) assertEquals(0f, e.doubleDetune, at)
                            e.vibratoDepth > 0f -> {
                                singers++
                                assertEquals(18f, e.doubleDetune, at)
                                assertEquals(0.8f, e.doubleSpread, at)
                            }
                            // Relies on SLOT_PARTS rolling LEAD only on track 4.
                            i == 4 -> {
                                leads++
                                assertEquals(10f, e.doubleDetune, at)
                                assertEquals(0.4f, e.doubleSpread, at)
                            }
                            i in 0..3 -> assertEquals(0f, e.doubleDetune, at)
                        }
                    }
                }
            }
        }
        assertTrue(leads > 0 && singers > 0, "precondition: $leads lead and $singers singer voices")
    }

    @Test
    fun noShippedTemplate() {
        val shipped = shippedVibes().associateBy(edmTuple) { it.name }
        for (mode in CastMode.entries) {
            for (seed in 1L..200L) {
                val tuple = edmTuple(cast(mode, seed).vibe)
                assertTrue(tuple !in shipped, "$mode seed $seed rolled the engines of shipped vibe '${shipped[tuple]}': $tuple")
            }
        }
    }

    // ── Theory rules, one per Global Constraint ──

    /**
     * What render plays for h/t/m at energy, mood and space 0.5: a pinned knob as written, else the
     * macro lerp, lifted to the engine's playability floor (held to the C++ by EngineMirrorParityTest).
     */
    private fun effective(e: OrpheusEngine, mm: TrackMacroMap): List<Float> {
        fun lerp(t: MacroTarget) = t.min + 0.5f * (t.max - t.min)
        val f = EngineMirror.floors(e.engineId)
        return listOf(
            maxOf(f.harmonics, if (e.pinHarmonics || e.engineId.forcePinHarmonics) e.harmonics else lerp(mm.moodHarmonics)),
            maxOf(f.timbre, if (e.pinTimbre) e.timbre else lerp(mm.moodTimbre)),
            maxOf(f.morph, if (e.pinMorph) e.morph else lerp(mm.spaceDecay)),
        )
    }

    private val TrackVoice.isSustained: Boolean
        get() = role !is TrackRole.Percussive && (
            envelopeProfile == EnvelopeProfile.DRONE || envelopeProfile == EnvelopeProfile.EFFECT ||
                (role as? TrackRole.Chordal)?.comping?.style == CompingStyle.PAD
            )

    // A lead (Melodic, MELODIC envelope, FOLLOW) also keeps its own octave, clear of the drones.
    private val TrackVoice.isLead: Boolean
        get() = (role as? TrackRole.Melodic)?.chordFollow == ChordFollow.FOLLOW && envelopeProfile == EnvelopeProfile.MELODIC

    // Sustained and lead voices may share at most 2 semitones of their full note ranges.
    private fun assertRegistersApart(v: Vibe, at: String) {
        val owners = v.tracks.withIndex().filter { it.value.isSustained || it.value.isLead }
        for ((n, a) in owners.withIndex()) {
            for (b in owners.drop(n + 1)) {
                for (x in listOf(a.value.engineEdm, a.value.engineSpace)) for (y in listOf(b.value.engineEdm, b.value.engineSpace)) {
                    val shared = minOf(x.noteRangeHigh, y.noteRangeHigh) - maxOf(x.noteRangeLow, y.noteRangeLow)
                    assertTrue(
                        shared <= 2,
                        "$at: tracks ${a.index} (${x.noteRangeLow}-${x.noteRangeHigh}) and ${b.index} " +
                            "(${y.noteRangeLow}-${y.noteRangeHigh}) share $shared semitones of register",
                    )
                }
            }
        }
    }

    private fun assertBassPlaysTheRoot(v: Vibe, at: String) {
        val bass = v.tracks[3].role
        assertTrue(
            bass is TrackRole.Melodic && bass.chordFollow == ChordFollow.ROOT_ONLY,
            "$at: the bass on track 3 must be Melodic with chordFollow ROOT_ONLY, was $bass",
        )
    }

    private fun assertAtMostOneArpeggiator(v: Vibe, at: String) {
        val arps = v.tracks.withIndex()
            .filter { (it.value.role as? TrackRole.Chordal)?.comping?.arpMode.let { m -> m != null && m != ArpMode.NEVER } }
            .map { it.index }
        assertTrue(arps.size <= 1, "$at: tracks $arps all arpeggiate; at most one Chordal track may, the rest NEVER")
        arps.forEach { i ->
            val engines = listOf(v.tracks[i].engineEdm.engineId, v.tracks[i].engineSpace.engineId)
            assertTrue(
                engines.none { it == OrpheusEngineId.CHD || it.isChordEngine },
                "$at: track $i arpeggiates a chord engine in one of its slots: $engines",
            )
        }
    }

    private fun assertPadsFollowAndSustain(v: Vibe, at: String) {
        v.tracks.forEachIndexed { i, t ->
            val chordal = t.role as? TrackRole.Chordal
            val isPad = chordal?.comping?.style == CompingStyle.PAD
            val isDrone = t.role !is TrackRole.Percussive && t.envelopeProfile == EnvelopeProfile.DRONE
            if (!isPad && !isDrone) return@forEachIndexed
            assertEquals(ChordFollow.FOLLOW, t.chordFollow, "$at: pad/drone track $i must follow the chords")
            assertTrue(
                t.envelopeProfile == EnvelopeProfile.DRONE || t.envelopeProfile == EnvelopeProfile.EFFECT,
                "$at: pad track $i must sustain (DRONE or EFFECT), was ${t.envelopeProfile}",
            )
            if (chordal != null) {
                assertTrue(chordal.comping.restrikeOnChordChange, "$at: pad track $i holds one note across chord changes")
                assertEquals(ArpMode.NEVER, chordal.comping.arpMode, "$at: pad track $i must not arpeggiate")
            }
        }
    }

    // The pair is judged on what plays: a same-engine pair must differ by 0.15 on an effective knob.
    private fun assertEdmAndSpacePaired(v: Vibe, at: String) {
        v.tracks.forEachIndexed { i, t ->
            val e = t.engineEdm
            val s = t.engineSpace
            val delta = effective(e, t.macroMap).zip(effective(s, t.macroMap)).maxOf { (a, b) -> abs(a - b) }
            assertTrue(
                e != s && (e.engineId != s.engineId || delta >= 0.15f),
                "$at: track $i EDM and SPACE play alike (${e.engineId}/${s.engineId}, max effective h/t/m delta $delta)",
            )
        }
    }

    private fun assertNonBassVoicesHaveOwnRange(v: Vibe, at: String) {
        v.tracks.forEachIndexed { i, t ->
            // Percussive tracks play fixed drum notes and ignore the range.
            if (i == 3 || t.role is TrackRole.Percussive) return@forEachIndexed
            listOf(t.engineEdm, t.engineSpace).forEach { e ->
                assertTrue(
                    e.noteRangeLow >= 48 && e.noteRangeHigh > e.noteRangeLow,
                    "$at: track $i ${e.engineId} needs its own range at 48 or above, was ${e.noteRangeLow}-${e.noteRangeHigh}",
                )
            }
        }
    }
}

// ── Shipped vibes, off the compiled classpath (the no-DI pattern of VibeCatalogScan) ──

internal fun shippedVibes(): List<Vibe> = shippedVibeProviders().map { it.vibe }

internal fun shippedVibeProviders(): List<VibeProvider> {
    val path = "org/balch/orpheus/features/pulsar/vibes"
    val loader = VibeProvider::class.java.classLoader
    val roots = loader.getResources(path).toList()
    assertTrue(roots.isNotEmpty(), "found no classpath entry for $path, so nothing could be scanned")
    val names = roots.flatMap { url ->
        when (url.protocol) {
            "file" -> File(url.toURI()).let { root ->
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                    .map { "$path/" + it.relativeTo(root).invariantSeparatorsPath }.toList()
            }
            "jar" -> (url.openConnection() as JarURLConnection).jarFile.entries().toList()
                .map { it.name }.filter { it.startsWith("$path/") && it.endsWith(".class") }
            else -> fail("cannot scan ${url.protocol} classpath entry $url")
        }
    }.filter { '$' !in it.substringAfterLast('/') }.distinct().sorted()
    val classes = names.map { it.removeSuffix(".class").replace('/', '.') }
        .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
        .filter { VibeProvider::class.java.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
    val providers = classes.map { it.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as VibeProvider }
    assertTrue(providers.size >= 20, "classpath scan found only ${providers.size} shipped vibes, so the check is hollow")
    return providers
}
