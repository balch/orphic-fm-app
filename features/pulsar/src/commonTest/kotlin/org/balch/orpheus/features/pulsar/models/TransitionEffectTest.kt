package org.balch.orpheus.features.pulsar.models

import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialization + validation contract for [TransitionEffect] (`SectionTransition.effects` and
 * `Section.exitEffects`), [SectionWeather] (`Section.weather`), and the breathe fields.
 * Mirrors AnomalyTest's style: layer the new schema onto a known-good DogHouse vibe.
 */
class TransitionEffectTest {

    private val base = DogHouseVibe().vibe
    private val arrangement = base.arrangement!!
    private val json = Json { encodeDefaults = true }

    // DogHouse's first section has a real outgoing edge to layer effects onto.
    private val edgeSectionIndex = arrangement.sections.indexOfFirst { it.transitions.isNotEmpty() }

    private fun withEdgeEffects(effects: List<TransitionEffect>): Vibe {
        val section = arrangement.sections[edgeSectionIndex]
        val edge = section.transitions.first().copy(effects = effects)
        val sections = arrangement.sections.toMutableList().also {
            it[edgeSectionIndex] = section.copy(transitions = listOf(edge) + section.transitions.drop(1))
        }
        return base.copy(arrangement = arrangement.copy(sections = sections))
    }

    private fun firstEdgeEffects(vibe: Vibe): List<TransitionEffect> =
        vibe.arrangement!!.sections[edgeSectionIndex].transitions.first().effects

    /** Layers section-level [Section.exitEffects] on, optionally alongside edge effects. */
    private fun withExitEffects(
        exitEffects: List<TransitionEffect>,
        edgeEffects: List<TransitionEffect> = emptyList(),
    ): Vibe {
        val section = arrangement.sections[edgeSectionIndex]
        val edge = section.transitions.first().copy(effects = edgeEffects)
        val sections = arrangement.sections.toMutableList().also {
            it[edgeSectionIndex] = section.copy(
                exitEffects = exitEffects,
                transitions = listOf(edge) + section.transitions.drop(1),
            )
        }
        return base.copy(arrangement = arrangement.copy(sections = sections))
    }

    private fun exitEffects(vibe: Vibe): List<TransitionEffect> =
        vibe.arrangement!!.sections[edgeSectionIndex].exitEffects

    // --- TransitionEffect round trips ---

    @Test
    fun scratch_effect_round_trips_with_its_discriminator() {
        val vibe = withEdgeEffects(listOf(ScratchEffect(ms = 300)))
        val encoded = json.encodeToString(Vibe.serializer(), vibe)
        // "scratchEffect", not "scratch" — ScratchAnomaly already claims "scratch" and the
        // Vibe-wide schema generator needs every polymorphic tag to be globally unique.
        assertTrue(encoded.contains("\"type\":\"scratchEffect\""), "missing scratchEffect discriminator: $encoded")
        val decoded = json.decodeFromString(Vibe.serializer(), encoded)
        assertEquals(vibe, decoded)
    }

    @Test
    fun tapeStop_effect_round_trips_with_its_discriminator() {
        val vibe = withEdgeEffects(listOf(TapeStopEffect(ms = 900)))
        val encoded = json.encodeToString(Vibe.serializer(), vibe)
        assertTrue(encoded.contains("\"type\":\"tapeStop\""), "missing tapeStop discriminator: $encoded")
        val decoded = json.decodeFromString(Vibe.serializer(), encoded)
        assertEquals(vibe, decoded)
    }

    @Test
    fun strike_effect_round_trips_with_its_discriminator() {
        val vibe = withEdgeEffects(listOf(StrikeEffect(intensity = 0.9f, distance = 0.15f, offsetBars = -1f)))
        val encoded = json.encodeToString(Vibe.serializer(), vibe)
        assertTrue(encoded.contains("\"type\":\"strike\""), "missing strike discriminator: $encoded")
        val decoded = json.decodeFromString(Vibe.serializer(), encoded)
        assertEquals(vibe, decoded)
    }

    @Test
    fun mixed_effects_list_preserves_order_through_round_trip() {
        val effects = listOf(TapeStopEffect(ms = 500), StrikeEffect(offsetBars = 0f), ScratchEffect())
        val vibe = withEdgeEffects(effects)
        val decoded = json.decodeFromString(Vibe.serializer(), json.encodeToString(Vibe.serializer(), vibe))
        assertEquals(effects, firstEdgeEffects(decoded))
    }

    @Test
    fun effects_default_to_empty_list() {
        assertTrue(arrangement.sections[edgeSectionIndex].transitions.first().effects.isEmpty())
    }

    // --- ScratchEffect / TapeStopEffect / StrikeEffect field validation ---

    @Test
    fun rejects_scratch_ms_at_zero() {
        assertFailsWith<IllegalArgumentException> { ScratchEffect(ms = 0) }
    }

    @Test
    fun rejects_tapeStop_ms_negative() {
        assertFailsWith<IllegalArgumentException> { TapeStopEffect(ms = -500) }
    }

    @Test
    fun rejects_strike_intensity_out_of_range() {
        assertFailsWith<IllegalArgumentException> { StrikeEffect(intensity = 1.1f) }
    }

    @Test
    fun rejects_strike_distance_out_of_range() {
        assertFailsWith<IllegalArgumentException> { StrikeEffect(distance = -0.1f) }
    }

    @Test
    fun rejects_strike_delay_out_of_range() {
        assertFailsWith<IllegalArgumentException> { StrikeEffect(delayMs = -1) }
        assertFailsWith<IllegalArgumentException> {
            StrikeEffect(delayMs = StrikeEffect.MAX_DELAY_MS + 1)
        }
    }

    @Test
    fun accepts_strike_delay_across_its_whole_range() {
        // 0 is the default and the immediate path C++ keeps bit-identical; the ceiling is
        // what StormVoice's kStrikeMaxDelayMs clamps to, so both ends have to author.
        assertEquals(0, StrikeEffect().delayMs)
        assertEquals(
            StrikeEffect.MAX_DELAY_MS,
            StrikeEffect(delayMs = StrikeEffect.MAX_DELAY_MS).delayMs,
        )
    }

    // --- MAX_PER_FLIP ---

    @Test
    fun max_per_flip_is_four() {
        // Pinned: task-4's trans_fx_data bank sizes its per-flip row cap off this constant.
        assertEquals(4, TransitionEffect.MAX_PER_FLIP)
    }

    @Test
    fun accepts_effects_list_at_max_per_flip() {
        val vibe = withEdgeEffects(List(TransitionEffect.MAX_PER_FLIP) { ScratchEffect() })
        assertEquals(TransitionEffect.MAX_PER_FLIP, firstEdgeEffects(vibe).size)
    }

    @Test
    fun rejects_effects_list_beyond_max_per_flip() {
        assertFailsWith<IllegalArgumentException> {
            withEdgeEffects(List(TransitionEffect.MAX_PER_FLIP + 1) { ScratchEffect() })
        }
    }

    // --- Section.exitEffects ---

    @Test
    fun exit_effects_default_to_empty_list() {
        assertTrue(arrangement.sections[edgeSectionIndex].exitEffects.isEmpty())
    }

    @Test
    fun exit_effects_round_trip_through_vibe_json() {
        val effects = listOf(TapeStopEffect(ms = 700), StrikeEffect(intensity = 0.6f, offsetBars = -2f))
        val vibe = withExitEffects(effects)
        val decoded = json.decodeFromString(Vibe.serializer(), json.encodeToString(Vibe.serializer(), vibe))
        assertEquals(effects, exitEffects(decoded))
        assertEquals(vibe, decoded)
    }

    @Test
    fun accepts_exit_effects_at_max_per_flip() {
        assertEquals(
            TransitionEffect.MAX_PER_FLIP,
            exitEffects(withExitEffects(List(TransitionEffect.MAX_PER_FLIP) { ScratchEffect() })).size,
        )
    }

    @Test
    fun rejects_exit_effects_beyond_max_per_flip() {
        assertFailsWith<IllegalArgumentException> {
            withExitEffects(List(TransitionEffect.MAX_PER_FLIP + 1) { ScratchEffect() })
        }
    }

    @Test
    fun accepts_exit_effect_strike_offsetBars_at_bounds() {
        withExitEffects(listOf(StrikeEffect(offsetBars = -8f)))
        withExitEffects(listOf(StrikeEffect(offsetBars = 1f)))
    }

    @Test
    fun rejects_exit_effect_strike_offsetBars_below_minimum() {
        assertFailsWith<IllegalArgumentException> {
            withExitEffects(listOf(StrikeEffect(offsetBars = -8.1f)))
        }
    }

    @Test
    fun rejects_exit_effect_strike_offsetBars_above_maximum() {
        assertFailsWith<IllegalArgumentException> {
            withExitEffects(listOf(StrikeEffect(offsetBars = 1.1f)))
        }
    }

    // --- Combined cap: exitEffects union the worst edge must still fit one flip ---

    @Test
    fun accepts_exit_plus_edge_effects_summing_to_max_per_flip() {
        val vibe = withExitEffects(
            exitEffects = List(2) { ScratchEffect() },
            edgeEffects = List(2) { TapeStopEffect() },
        )
        assertEquals(2, exitEffects(vibe).size)
        assertEquals(2, firstEdgeEffects(vibe).size)
    }

    @Test
    fun rejects_exit_plus_edge_effects_exceeding_max_per_flip() {
        // 3 + 2 = 5 pending at one flip; C++ holds 4 and silently drops the rest.
        assertFailsWith<IllegalArgumentException> {
            withExitEffects(
                exitEffects = List(3) { ScratchEffect() },
                edgeEffects = List(2) { TapeStopEffect() },
            )
        }
    }

    @Test
    fun combined_cap_measures_the_worst_edge_not_the_total() {
        // Two edges of 2 effects each alongside 2 exit effects. Summed across edges that
        // is 6, but no single flip stages more than 4, so the section is legal.
        val edge = arrangement.sections[edgeSectionIndex].transitions.first()
        val section = Section(
            name = "two-edges",
            exitEffects = List(2) { ScratchEffect() },
            transitions = listOf(
                edge.copy(effects = List(2) { TapeStopEffect() }),
                edge.copy(effects = List(2) { TapeStopEffect() }),
            ),
        )
        assertEquals(2, section.exitEffects.size)
        assertEquals(4, section.transitions.sumOf { it.effects.size })
    }

    // --- Section.entryEffects ---

    private fun entrySections(
        exitEffects: List<TransitionEffect> = emptyList(),
        edgeEffects: List<TransitionEffect> = emptyList(),
        entryEffects: List<TransitionEffect> = emptyList(),
    ) = listOf(
        Section(
            name = "source",
            exitEffects = exitEffects,
            transitions = listOf(SectionTransition(targetIndex = 1, weight = 1f, effects = edgeEffects)),
        ),
        Section(name = "destination", entryEffects = entryEffects),
    )

    @Test
    fun entry_effects_default_to_empty_list() {
        assertTrue(arrangement.sections[edgeSectionIndex].entryEffects.isEmpty())
    }

    @Test
    fun entry_effects_round_trip_through_vibe_json() {
        val effects = listOf(ScratchEffect(ms = 250), StrikeEffect(intensity = 0.6f, offsetBars = 1f))
        val section = arrangement.sections[edgeSectionIndex]
        val sections = arrangement.sections.toMutableList().also {
            it[edgeSectionIndex] = section.copy(entryEffects = effects)
        }
        val vibe = base.copy(arrangement = arrangement.copy(sections = sections))
        val decoded = json.decodeFromString(Vibe.serializer(), json.encodeToString(Vibe.serializer(), vibe))
        assertEquals(effects, decoded.arrangement!!.sections[edgeSectionIndex].entryEffects)
        assertEquals(vibe, decoded)
    }

    @Test
    fun accepts_entry_effects_at_max_per_flip() {
        val section = Section(name = "arrival", entryEffects = List(TransitionEffect.MAX_PER_FLIP) { ScratchEffect() })
        assertEquals(TransitionEffect.MAX_PER_FLIP, section.entryEffects.size)
    }

    @Test
    fun rejects_entry_effects_beyond_max_per_flip() {
        assertFailsWith<IllegalArgumentException> {
            Section(name = "arrival", entryEffects = List(TransitionEffect.MAX_PER_FLIP + 1) { ScratchEffect() })
        }
    }

    @Test
    fun rejects_entry_effect_strike_offsetBars_out_of_range() {
        assertFailsWith<IllegalArgumentException> {
            Section(name = "arrival", entryEffects = listOf(StrikeEffect(offsetBars = 1.1f)))
        }
    }

    // --- Combined cap: exit + edge + the DESTINATION's entry must fit one flip ---

    @Test
    fun accepts_exit_plus_edge_plus_entry_effects_summing_to_max_per_flip() {
        val arr = Arrangement(
            sections = entrySections(
                exitEffects = List(1) { ScratchEffect() },
                edgeEffects = List(2) { TapeStopEffect() },
                entryEffects = List(1) { ScratchEffect() },
            ),
        )
        assertEquals(1, arr.sections[1].entryEffects.size)
    }

    @Test
    fun rejects_exit_plus_edge_plus_entry_effects_exceeding_max_per_flip() {
        // 1 + 2 + 2 = 5 pending at one flip. Section.init cannot see it — only the
        // arrangement knows which section the edge lands in.
        assertFailsWith<IllegalArgumentException> {
            Arrangement(
                sections = entrySections(
                    exitEffects = List(1) { ScratchEffect() },
                    edgeEffects = List(2) { TapeStopEffect() },
                    entryEffects = List(2) { ScratchEffect() },
                ),
            )
        }
    }

    @Test
    fun combined_cap_charges_entry_effects_to_every_inbound_edge() {
        // The destination's 4 entryEffects already fill a flip on their own, so any
        // inbound edge carrying an effect of its own is over budget.
        assertFailsWith<IllegalArgumentException> {
            Arrangement(
                sections = entrySections(
                    edgeEffects = List(1) { TapeStopEffect() },
                    entryEffects = List(TransitionEffect.MAX_PER_FLIP) { ScratchEffect() },
                ),
            )
        }
    }

    // --- StrikeEffect.offsetBars range (enforced by SectionTransition.init) ---

    @Test
    fun accepts_strike_offsetBars_at_bounds() {
        withEdgeEffects(listOf(StrikeEffect(offsetBars = -8f)))
        withEdgeEffects(listOf(StrikeEffect(offsetBars = 1f)))
    }

    @Test
    fun rejects_strike_offsetBars_below_minimum() {
        assertFailsWith<IllegalArgumentException> {
            withEdgeEffects(listOf(StrikeEffect(offsetBars = -8.1f)))
        }
    }

    @Test
    fun rejects_strike_offsetBars_above_maximum() {
        assertFailsWith<IllegalArgumentException> {
            withEdgeEffects(listOf(StrikeEffect(offsetBars = 1.1f)))
        }
    }

    // --- SectionWeather ---

    @Test
    fun section_weather_round_trips_through_vibe_json() {
        val weather = SectionWeather(
            rain = 0.8f, rumble = 0.55f, strikeChance = 0.3f, distance = 0.4f, rainLevel = 0.6f,
        )
        val section = arrangement.sections[edgeSectionIndex]
        val sections = arrangement.sections.toMutableList().also {
            it[edgeSectionIndex] = section.copy(weather = weather)
        }
        val vibe = base.copy(arrangement = arrangement.copy(sections = sections))
        val decoded = json.decodeFromString(Vibe.serializer(), json.encodeToString(Vibe.serializer(), vibe))
        assertEquals(weather, decoded.arrangement!!.sections[edgeSectionIndex].weather)
    }

    @Test
    fun section_weather_defaults_to_null() {
        assertNull(arrangement.sections[edgeSectionIndex].weather)
    }

    @Test
    fun rejects_section_weather_rain_out_of_range() {
        assertFailsWith<IllegalArgumentException> { SectionWeather(rain = 1.1f) }
    }

    @Test
    fun rejects_section_weather_rumble_out_of_range() {
        assertFailsWith<IllegalArgumentException> { SectionWeather(rumble = -0.1f) }
    }

    @Test
    fun rejects_section_weather_strikeChance_out_of_range() {
        assertFailsWith<IllegalArgumentException> { SectionWeather(strikeChance = 1.1f) }
    }

    @Test
    fun rejects_section_weather_distance_out_of_range() {
        assertFailsWith<IllegalArgumentException> { SectionWeather(distance = -0.1f) }
    }

    @Test
    fun rejects_section_weather_rainLevel_out_of_range() {
        assertFailsWith<IllegalArgumentException> { SectionWeather(rainLevel = 1.1f) }
    }

    @Test
    fun section_weather_rainLevel_defaults_to_full() {
        // Unity by default, so a bed authored before the dial existed is unattenuated.
        assertEquals(1f, SectionWeather(rain = 0.5f).rainLevel)
    }

    // --- TrackSectionOverride breathe fields ---

    @Test
    fun breathe_fields_default_to_off() {
        val override = TrackSectionOverride()
        assertEquals(0, override.breatheBars)
        assertEquals(0f, override.breatheFloor)
        assertEquals(0f, override.breatheTimbreSpan)
    }

    @Test
    fun breathe_fields_round_trip_through_vibe_json() {
        val override = TrackSectionOverride(breatheBars = 2, breatheFloor = 0.05f, breatheTimbreSpan = 0.6f)
        val section = arrangement.sections[edgeSectionIndex]
        val sections = arrangement.sections.toMutableList().also {
            it[edgeSectionIndex] = section.copy(trackOverrides = mapOf(3 to override))
        }
        val vibe = base.copy(arrangement = arrangement.copy(sections = sections))
        val decoded = json.decodeFromString(Vibe.serializer(), json.encodeToString(Vibe.serializer(), vibe))
        assertEquals(override, decoded.arrangement!!.sections[edgeSectionIndex].trackOverrides!![3])
    }

    @Test
    fun rejects_negative_breatheBars() {
        assertFailsWith<IllegalArgumentException> { TrackSectionOverride(breatheBars = -1) }
    }

    @Test
    fun rejects_breatheFloor_out_of_range() {
        assertFailsWith<IllegalArgumentException> { TrackSectionOverride(breatheFloor = 1.1f) }
    }

    @Test
    fun rejects_breatheTimbreSpan_out_of_range() {
        assertFailsWith<IllegalArgumentException> { TrackSectionOverride(breatheTimbreSpan = -0.1f) }
    }
}
