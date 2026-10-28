package org.balch.orpheus.djapp.ai.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.HistoryPoint
import org.balch.orpheus.djapp.ai.collab.HistoryVibeEdit
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.collab.MixHistory
import org.balch.orpheus.djapp.ai.collab.PointKind
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.LayerWrite
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.SectionLayer
import org.balch.orpheus.features.pulsar.bench.VibeLayer
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.EngineSlot
import org.balch.orpheus.features.pulsar.bench.capture
import org.balch.orpheus.features.pulsar.bench.knobPath
import org.balch.orpheus.features.pulsar.bench.withEngineKnob

class AiMixerStateTest {
    private val v = vibe("Mixer")

    private fun state(
        layers: BenchLayers = BenchLayers(),
        vibe: org.balch.orpheus.features.pulsar.models.Vibe = v,
        section: Int = 0,
        scope: Scope = Scope.VIBE,
        muted: Set<Int> = emptySet(),
        soloed: Set<Int> = emptySet(),
        knobs: Map<Macro, Float> = emptyMap(),
    ) = mixerState(vibe, section, layers, scope, muted, soloed, null, knobs)

    @Test
    fun tracksMirrorEffective() {
        val layers = BenchLayers(vibe = VibeLayer(levelDb = List(8) { if (it == 3) -4f else 0f }))
        val s = state(layers, muted = setOf(1))
        assertEquals(-4f, s.tracks[3].levelDb)
        assertEquals("BASS", s.tracks[3].name)
        assertTrue(s.tracks[1].muted && s.tracks[1].silenced)
    }

    @Test
    fun soloSilencesOthers() {
        val s = state(soloed = setOf(2))
        assertTrue(s.tracks.all { it.silenced == (it.index != 2) })
    }

    @Test
    fun vibeScopeMacroShowsKnob() {
        val file = Macro.ENERGY.let { v.energy }
        val s = state(BenchLayers(vibe = VibeLayer(macros = mapOf(Macro.ENERGY to 0.6f))))
        val m = s.macros.first { it.macro == Macro.ENERGY }
        assertEquals(0.6f, m.value)
        assertEquals(file, m.resetValue)
        assertTrue(m.changed)
    }

    @Test
    fun sectionScopeMacroShowsHeard() {
        val layers = BenchLayers(sections = mapOf(1 to SectionLayer(macrosHeard = mapOf(Macro.MOOD to 0.3f))))
        val s = state(layers, section = 1, scope = Scope.SECTION)
        assertTrue(s.sectionScope)
        val m = s.macros.first { it.macro == Macro.MOOD }
        assertEquals(0.3f, m.value)
        assertTrue(m.changed)
        assertEquals("heard here", m.note)
    }

    @Test
    fun sectionScopeUnavailableShowsVibe() {
        val one = v.copy(arrangement = v.arrangement!!.copy(sections = v.arrangement!!.sections.take(1)))
        val s = state(vibe = one, scope = Scope.SECTION, knobs = mapOf(Macro.SPACE to 0.7f))
        assertFalse(s.sectionScopeAvailable)
        assertFalse(s.sectionScope)
        assertEquals(0.7f, s.macros.first { it.macro == Macro.SPACE }.value)
    }

    @Test
    fun changeLinesMatchBench() {
        val row = CaptureRow(Scope.VIBE, null, null, 3, CaptureField.VOLUME, null, 0.5f, 0.6f)
        assertEquals(
            listOf(
                ChangeLine(ChangeKind.HEADER, "// vibe · BASS"),
                ChangeLine(ChangeKind.OLD, "- volume (edm) = 0.500"),
                ChangeLine(ChangeKind.NEW, "+ volume (edm) = 0.600  (+1.6 dB)"),
            ),
            changeLines(listOf(row)),
        )
    }

    @Test
    fun changeCountMatchesChangeLines() {
        val rows = listOf(
            CaptureRow(Scope.VIBE, null, null, 3, CaptureField.VOLUME, null, 0.5f, 0.6f),
            CaptureRow(Scope.VIBE, null, null, 3, CaptureField.PAN, null, 0f, 0.2f),
            CaptureRow(Scope.SECTION, 1, "groove", 4, CaptureField.VOLUME, null, 0.9f, 1f, clampedDb = 1.5f),
            CaptureRow(Scope.SECTION, 1, "groove", null, CaptureField.MACRO, Macro.SPACE, 1f, 1.2f),
        )
        val s = AiMixerUiState(changes = rows)
        // Headers and flags are extra lines; the count is one per "+" line.
        assertEquals(changeLines(rows).count { it.kind == ChangeKind.NEW }, s.changeCount)
        assertEquals(4, s.changeCount)
        assertEquals("4 changes", changesLabel(s.changeCount))
        assertEquals("1 change", changesLabel(1))
        assertEquals("No changes", changesLabel(0))
    }

    @Test
    fun theHeaderCountsHistoryChangesNotFileValues() {
        // One whole-vibe level move writes the vibe's value and each section's own: 3 file values, 1 change.
        val rows = listOf(
            CaptureRow(Scope.VIBE, null, null, 0, CaptureField.VOLUME, null, 0.5f, 0.42f),
            CaptureRow(Scope.SECTION, 0, "intro", 0, CaptureField.VOLUME, null, 0.6f, 0.5f),
            CaptureRow(Scope.SECTION, 1, "groove", 0, CaptureField.VOLUME, null, 0.9f, 0.76f),
        )
        val history = listOf(
            ChangeRow(1, "Claude Code", "KICK · level", "0.0 dB", "-1.5 dB", "whole vibe", atMs = 0L, on = true, covered = false),
            PointRow(2, "Point 1", atMs = 0L),
        )
        val s = AiMixerUiState(changes = rows, history = history)
        assertEquals(1, s.historyChangeCount)
        assertEquals(3, s.changeCount)
    }

    @Test
    fun historyRowsNameWhoWhereAndCovered() {
        fun MixHistory.rec(origin: EditOrigin, control: MixControl, field: LayerField, section: Int?, name: String?, at: Long) =
            record(origin, control, section, name, "0.00", "0.40", mapOf(field to LayerWrite(0f, 0.4f)), at)

        val h = MixHistory()
            .rec(EditOrigin.USER, MixControl.Pan(2), LayerField.VibePan(2), null, null, 1_000L)
            .capture(10_000L, "Capture 1", null)
            .rec(EditOrigin.AGENT, MixControl.Pan(2), LayerField.VibePan(2), null, null, 20_000L)
            .mark(30_000L)
            .rec(EditOrigin.MCP, MixControl.Level(3), LayerField.VibeLevel(3), 1, "groove", 40_000L)
        val rows = historyRows(h) { "T$it" }
        val (a, b) = rows.filterIsInstance<ChangeRow>().take(2)
        assertEquals("you", a.who)
        assertEquals("T2 · pan", a.label)
        assertEquals("whole vibe", a.where)
        assertTrue(a.covered, "a later on change sets the same pan")
        assertFalse(b.covered)
        assertEquals("Orpheus", b.who)
        assertEquals(PointRow(h.items[3].id, "Point 1", 30_000L), rows[3])
        val mcp = rows[4] as ChangeRow
        assertEquals("Claude Code", mcp.who)
        assertEquals("groove", mcp.where)
        assertEquals("0.00", mcp.before)
        assertEquals("0.40", mcp.after)
    }

    @Test
    fun eachChangeShowsTheFileValuesItWrites() {
        val h = MixHistory()
            .record(EditOrigin.USER, MixControl.Level(3), null, null, "0.0 dB", "-4.0 dB", mapOf(LayerField.VibeLevel(3) to LayerWrite(null, -4f)), 1_000L)
            .record(EditOrigin.USER, MixControl.Pan(2), null, null, "0.00", "0.30", mapOf(LayerField.VibePan(2) to LayerWrite(null, 0.3f)), 10_000L)
            .capture(15_000L, "Capture 1", null)
            .record(EditOrigin.AGENT, MixControl.Pan(2), null, null, "0.30", "0.50", mapOf(LayerField.VibePan(2) to LayerWrite(0.3f, 0.5f)), 20_000L)
            .record(EditOrigin.USER, MixControl.Pan(5), null, null, "0.00", "0.20", mapOf(LayerField.VibePan(5) to LayerWrite(null, 0.2f)), 30_000L)
        val off = h.toggle(h.changes.last().id)
        val (level, coveredPan, pan, offPan) = historyRows(off, v) { "T$it" }.filterIsInstance<ChangeRow>()
        val all = capture(v, off.layers())

        assertTrue(level.writes.isNotEmpty() && level.writes.all { it.track == 3 }, "a level move writes track 3's volumes: ${level.writes}")
        assertTrue(level.writes.any { it.scope == Scope.VIBE && it.field == CaptureField.VOLUME })
        assertEquals(emptyList(), coveredPan.writes, "a covered change writes nothing of its own")
        assertEquals(listOf(CaptureRow(Scope.VIBE, null, null, 2, CaptureField.PAN, null, v.tracks[2].pan, 0.5f)), pan.writes)
        assertEquals(emptyList(), offPan.writes, "an off change writes nothing")
        assertEquals(all.toSet(), (level.writes + pan.writes).toSet(), "together the rows account for all Capture writes")
    }

    @Test
    fun capturedRowsAreTagged() {
        fun MixHistory.rec(track: Int, at: Long) =
            record(EditOrigin.USER, MixControl.Pan(track), null, null, "0.00", "0.40", mapOf(LayerField.VibePan(track) to LayerWrite(0f, 0.4f)), at)

        val none = MixHistory().rec(1, 1_000L).rec(2, 2_000L)
        assertEquals(null, uncapturedOn(historyRows(none) { "T$it" }), "no capture keeps today's header")
        assertEquals(listOf(false, false), historyRows(none) { "T$it" }.filterIsInstance<ChangeRow>().map { it.captured })

        val h = none.capture(3_000L, "Captured", "a.json").rec(3, 4_000L)
        val rows = historyRows(h) { "T$it" }
        assertEquals(listOf(true, true, false), rows.filterIsInstance<ChangeRow>().map { it.captured })
        assertEquals(PointRow(h.items[2].id, "Captured", 3_000L, capture = true), rows[2])
        assertEquals(1, uncapturedOn(rows))
        assertEquals(" · 1 uncaptured of 3 changes on", nowDetail(rows, h.onCount))
        assertEquals(" · 3 of 3 changes on", nowDetail(historyRows(none.rec(3, 4_000L)) { "T$it" }, 3))
    }

    private fun vibeEdit(id: Int, path: String, before: JsonElement, after: JsonElement, on: Boolean = true) =
        HistoryVibeEdit(id, atMs = id * 1_000L, origin = EditOrigin.AGENT, path = path, before = before, after = after, updatedMs = id * 1_000L, on = on)

    private fun vibeRows(vararg edits: HistoryVibeEdit) =
        historyRows(MixHistory(items = edits.toList(), nextId = 100), v) { "T$it" }.filterIsInstance<VibeEditRow>()

    @Test
    fun vibeRowLabels() {
        val num = JsonPrimitive(0.5)
        val rows = vibeRows(
            vibeEdit(1, "tracks[2].engineEdm.engineId", JsonPrimitive("VA"), JsonPrimitive("FM")),
            vibeEdit(2, "arrangement.sections[2].trackOverrides.2.morph", num, JsonPrimitive(0.8)),
            vibeEdit(3, "bpm", JsonPrimitive(120), JsonPrimitive(96)),
            vibeEdit(4, "genre.swingAmount", num, JsonPrimitive(0.1)),
        )
        assertEquals(
            listOf("T2 · EDM engine", "chorus · T2 morph", "bpm", "genre.swingAmount"),
            rows.map { it.label },
        )
        assertEquals("Orpheus", rows[0].who)
        assertEquals(2_000L, rows[1].atMs)
    }

    @Test
    fun vibeRowLabelsFallBackToTrackAndSectionNames() {
        val num = JsonPrimitive(0.5)
        val rows = vibeRows(
            vibeEdit(1, "arrangement.sections[2].compingStyle", JsonPrimitive("A"), JsonPrimitive("B")),
            vibeEdit(2, "tracks[4].envelopeProfile", num, JsonPrimitive(0.8)),
            vibeEdit(3, "arrangement.sections[40].compingStyle", JsonPrimitive("A"), JsonPrimitive("B")),
        )
        assertEquals(listOf("chorus · compingStyle", "T4 · envelopeProfile", "section 40 · compingStyle"), rows.map { it.label })
    }

    @Test
    fun vibeRowShortValues() {
        val steps = JsonArray(List(16) { JsonPrimitive(it) })
        val rows = vibeRows(
            vibeEdit(1, "bpm", JsonPrimitive(120f), JsonPrimitive(96f)),
            vibeEdit(7, "genre.swingAmount", JsonPrimitive(0.5f), JsonPrimitive(96.256f)),
            vibeEdit(2, "scaleType", JsonPrimitive("MINOR"), JsonPrimitive("MAJOR")),
            vibeEdit(3, "tracks[1].mute", JsonPrimitive(false), JsonPrimitive(true)),
            vibeEdit(4, "tracks[1].steps", steps, JsonArray(List(8) { JsonPrimitive(it) })),
            vibeEdit(5, "genre", JsonObject(mapOf("a" to JsonPrimitive(1))), JsonObject(mapOf("a" to JsonPrimitive(2)))),
            vibeEdit(6, "tracks[1].note", JsonNull, JsonPrimitive(3)),
        )
        assertEquals(
            listOf("120" to "96", "0.50" to "96.26", "MINOR" to "MAJOR", "false" to "true", "(16 steps)" to "(8 steps)", "(changed)" to "(changed)", "(none)" to "3"),
            rows.map { it.before to it.after },
        )
    }

    @Test
    fun vibeRowsCarryOnAndCapturedLikeChanges() {
        val h = MixHistory(
            items = listOf(
                vibeEdit(1, "bpm", JsonPrimitive(120.0), JsonPrimitive(96.0)),
                HistoryPoint(2, 2_000L, "Captured", PointKind.CAPTURE, "a.json"),
                vibeEdit(3, "scaleType", JsonPrimitive("MINOR"), JsonPrimitive("MAJOR"), on = false),
            ),
            nextId = 4,
        )
        val rows = historyRows(h, v) { "T$it" }
        val (a, b) = rows.filterIsInstance<VibeEditRow>()
        assertTrue(a.on && a.captured)
        assertFalse(b.on || b.captured)
    }

    @Test
    fun nowDetailCountsVibeRows() {
        val h = MixHistory()
            .record(EditOrigin.USER, MixControl.Pan(1), null, null, "0.00", "0.40", mapOf(LayerField.VibePan(1) to LayerWrite(0f, 0.4f)), 1_000L)
            .capture(2_000L, "Captured", "a.json")
            .let {
                it.copy(
                    items = it.items + listOf(
                        vibeEdit(50, "bpm", JsonPrimitive(120.0), JsonPrimitive(96.0)),
                        vibeEdit(51, "scaleType", JsonPrimitive("MINOR"), JsonPrimitive("MAJOR")),
                        vibeEdit(52, "rootNote", JsonPrimitive("A"), JsonPrimitive("C"), on = false),
                    ),
                )
            }
        val rows = historyRows(h, v) { "T$it" }
        assertEquals(2, uncapturedOn(rows))
        assertEquals(" · 2 uncaptured of 4 changes on", nowDetail(rows, h.onCount))
        assertEquals(4, AiMixerUiState(history = rows).historyChangeCount)
    }
    @Test
    fun voiceKnobsShowTheMacroUntilPinned() {
        val tv = v.tracks[2]
        val window = tv.macroMap.moodHarmonics
        val pinned = v.withEngineKnob(2, EngineSlot.EDM, EngineKnob.TIMBRE, 0.8f)
        val s = mixerState(
            pinned, 0, BenchLayers(), Scope.VIBE, emptySet(), emptySet(), null, mapOf(Macro.MOOD to 0.5f),
            VoiceView(heard = EngineSlot.EDM, edited = setOf(knobPath(2, EngineSlot.EDM, EngineKnob.TIMBRE))),
        )
        val (h, t, _) = s.tracks[2].voice
        assertEquals(window.min + 0.5f * (window.max - window.min), h.value, 1e-6f)
        assertEquals(Macro.MOOD, h.source)
        assertFalse(h.pinned || h.changed)
        assertEquals("detune", h.label)
        assertEquals(0.8f, t.value)
        assertTrue(t.pinned && t.changed && t.source == null)
        assertEquals("VA", s.tracks[2].edmEngine)
    }

    @Test
    fun mixerTrackCarriesBothSlotEngineIds() {
        val split = v.copy(
            tracks = v.tracks.toMutableList().also { it[4] = it[4].copy(engineSpace = it[4].engineSpace.copy(engineId = OrpheusEngineId.VOWEL_FOF)) },
        )
        val s = mixerState(split, 0, BenchLayers(), Scope.VIBE, emptySet(), emptySet(), null, emptyMap())
        assertEquals(OrpheusEngineId.VA, s.tracks[4].edmEngineId)
        assertEquals(OrpheusEngineId.VOWEL_FOF, s.tracks[4].spaceEngineId)
        assertEquals("VOWEL_FOF", s.tracks[4].spaceEngine)
        assertEquals(OrpheusEngineId.VA, s.tracks[3].spaceEngineId)
    }

    @Test
    fun aPickedSlotWinsOverTheHeardOne() {
        val s = mixerState(
            v, 0, BenchLayers(), Scope.VIBE, emptySet(), emptySet(), null, emptyMap(),
            VoiceView(heard = EngineSlot.EDM, picks = mapOf(4 to EngineSlot.SPACE)),
        )
        assertEquals(EngineSlot.SPACE, s.tracks[4].slot)
        assertFalse(s.tracks[4].slotHeard)
        assertEquals(EngineSlot.EDM, s.tracks[3].slot)
        assertTrue(s.tracks[3].slotHeard)
    }

    @Test
    fun aKnobPinRowReadsAsItsKnob() {
        val names = { t: Int -> BenchMeters.CHANNEL_NAMES[t] }
        assertEquals("BASS · EDM harmonics pin", vibePathLabel("tracks[3].engineEdm.pinHarmonics", v, names))
        assertEquals("BASS · Space morph", vibePathLabel("tracks[3].engineSpace.morph", v, names))
    }

    @Test
    fun lockRowReadsPlays() {
        val names = { t: Int -> BenchMeters.CHANNEL_NAMES[t] }
        assertEquals("${BenchMeters.CHANNEL_NAMES[4]} · plays", vibePathLabel("tracks[4].slotLock", v, names))
    }
}
