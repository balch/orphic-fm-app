package org.balch.orpheus.features.pulsar.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.balch.orpheus.features.pulsar.mkMinimalVibe
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionDelay
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.Vibe

private val base: Vibe = mkMinimalVibe("apply").let { v ->
    v.copy(
        tracks = v.tracks.mapIndexed { t, track ->
            track.copy(
                pan = 0.1f * t - 0.3f,
                engineEdm = track.engineEdm.copy(volume = 0.7f, reverbSend = 0.2f, delaySend = 0.3f),
                engineSpace = track.engineSpace.copy(volume = 0.5f, reverbSend = 0.4f, delaySend = 0.1f),
            )
        },
        energy = 0.6f,
        arrangement = Arrangement(
            sections = listOf(
                Section(
                    "verse",
                    trackOverrides = mapOf(5 to TrackSectionOverride(reverbSend = 0.3f, density = 0.4f)),
                    delay = SectionDelay(timeA = 0.2f),
                ),
                Section(
                    "chorus",
                    macroOverrides = MacroOverrides(energy = 1.2f, volume = 0.9f),
                    rampTo = MacroOverrides(energy = 1.4f),
                    trackOverrides = mapOf(1 to TrackSectionOverride(volume = 0.5f, delaySend = 0.2f)),
                ),
            ),
        ),
    )
}

private fun db(t: Int, v: Float) = List(8) { if (it == t) v else 0f }
private fun <T> at(t: Int, v: T) = List<T?>(8) { if (it == t) v else null }

/** One layer set per field, each making that field's rows. */
private val layersByField: Map<CaptureField, BenchLayers> = mapOf(
    CaptureField.VOLUME to BenchLayers(vibe = VibeLayer(levelDb = db(1, -6f))),
    CaptureField.VOLUME_SPACE to BenchLayers(vibe = VibeLayer(levelDb = db(0, 2f))),
    CaptureField.PAN to BenchLayers(vibe = VibeLayer(pan = at(2, -0.5f))),
    CaptureField.REVERB_SEND to BenchLayers(vibe = VibeLayer(reverbRatio = List(8) { if (it == 3) 1.5f else 1f })),
    CaptureField.REVERB_SEND_SPACE to BenchLayers(vibe = VibeLayer(reverbRatio = List(8) { if (it == 6) 0.5f else 1f })),
    CaptureField.DELAY_SEND to BenchLayers(vibe = VibeLayer(delayRatio = List(8) { if (it == 4) 0.5f else 1f })),
    CaptureField.DELAY_SEND_SPACE to BenchLayers(vibe = VibeLayer(delayRatio = List(8) { if (it == 7) 2f else 1f })),
    CaptureField.MACRO to BenchLayers(
        vibe = VibeLayer(macros = mapOf(Macro.ENERGY to 0.8f, Macro.MOOD to 0.2f)),
        sections = mapOf(0 to SectionLayer(levelDb = db(6, -3f))),
    ),
    CaptureField.MACRO_RAMP to BenchLayers(sections = mapOf(1 to SectionLayer(macrosHeard = mapOf(Macro.ENERGY to 0.9f)))),
    CaptureField.DELAY_TIME_A to BenchLayers(
        vibe = VibeLayer(delayFx = mapOf(DelayFx.TIME_A to 0.6f)),
        sections = mapOf(0 to SectionLayer(delayFx = mapOf(DelayFx.TIME_A to 0.45f))),
    ),
    CaptureField.DELAY_TIME_B to BenchLayers(sections = mapOf(1 to SectionLayer(delayFx = mapOf(DelayFx.TIME_B to 0.7f)))),
    CaptureField.DELAY_FEEDBACK to BenchLayers(vibe = VibeLayer(delayFx = mapOf(DelayFx.FEEDBACK to 0.8f))),
)

/** Every layer at once: whole-vibe and section edits that touch the same tracks. */
private val everything = BenchLayers(
    vibe = VibeLayer(
        levelDb = List(8) { if (it == 1) -6f else if (it == 6) 1f else 0f },
        pan = at(2, -0.5f),
        reverbRatio = List(8) { if (it == 5) 1.5f else 1f },
        delayRatio = List(8) { if (it == 1) 0.5f else 1f },
        macros = mapOf(Macro.ENERGY to 0.8f, Macro.SPACE to 0.3f),
        delayFx = mapOf(DelayFx.TIME_A to 0.5f, DelayFx.FEEDBACK to 0.3f),
    ),
    sections = mapOf(
        0 to SectionLayer(levelDb = db(6, -3f), reverbSend = at(5, 0.4f), delayFx = mapOf(DelayFx.TIME_B to 0.9f)),
        1 to SectionLayer(levelDb = db(1, 2f), delaySend = at(3, 0.25f), macrosHeard = mapOf(Macro.ENERGY to 0.9f, Macro.SPACE to 0.2f)),
    ),
)

/** What one track plays in one section: volume after the fader, pan and sends. */
private fun heard(vibe: Vibe, layers: BenchLayers, s: Int, t: Int): List<Float> {
    val o = vibe.arrangement?.sections?.getOrNull(s)?.trackOverrides?.get(t)
    val volume = (o?.volume ?: layers.activeEngine(vibe, s, t).volume) * layers.auditionGain(t, s)
    val e = layers.effective(vibe, s, t)
    return listOf(volume, e.pan, e.reverbSend, e.delaySend)
}

/** The macros one section plays: knob times the section multiplier, or the bench's heard value. */
private fun heardMacros(vibe: Vibe, layers: BenchLayers, s: Int): List<Float> = Macro.entries.map { m ->
    layers.sections[s]?.macrosHeard?.get(m)
        ?: ((layers.vibe.macros[m] ?: m.vibeDefault(vibe)) * (vibe.arrangement!!.sections[s].macroOverrides.multiplier(m) ?: 1f))
}

private fun assertSoundsTheSame(vibe: Vibe, layers: BenchLayers, updated: Vibe, label: String) {
    for (s in vibe.arrangement!!.sections.indices) {
        for (t in 0 until 8) {
            val want = heard(vibe, layers, s, t)
            val got = heard(updated, BenchLayers(), s, t)
            for (i in want.indices) assertEquals(want[i], got[i], 1e-5f, "$label: section $s track $t value $i")
        }
        val want = heardMacros(vibe, layers, s)
        val got = heardMacros(updated, BenchLayers(), s)
        for (i in want.indices) assertEquals(want[i], got[i], 1e-5f, "$label: section $s macro ${Macro.entries[i]}")
        for (p in DelayFx.entries) {
            assertEquals(layers.heardDelay(vibe, s, p), BenchLayers().heardDelay(updated, s, p), 1e-5f, "$label: section $s $p")
        }
    }
}

class ApplyCaptureTest {
    @Test
    fun withCaptureRoundTrips() {
        for (field in CaptureField.entries) {
            val layers = layersByField.getValue(field)
            val rows = capture(base, layers)
            assertTrue(rows.any { it.field == field }, "$field: no $field row in $rows")
            val updated = base.withCapture(rows)
            assertEquals(emptyList(), capture(updated, BenchLayers()), "$field")
            assertSoundsTheSame(base, layers, updated, "$field")
        }
        val updated = base.withCapture(capture(base, everything))
        assertEquals(emptyList(), capture(updated, BenchLayers()))
        assertSoundsTheSame(base, everything, updated, "everything")
    }

    @Test
    fun clampedVolumeRoundTripsAtTheCap() {
        // +6 dB on a 0.7 volume is heard at 1.05 (the gain stops at 1.5); the vibe can hold only 1.0.
        val rows = capture(base, BenchLayers(vibe = VibeLayer(levelDb = db(0, 6f))))
        val volume = rows.single { it.field == CaptureField.VOLUME }
        assertEquals(1f, volume.new)
        assertEquals(0.42f, volume.clampedDb, 0.01f)
        val updated = base.withCapture(rows)
        assertEquals(1f, updated.tracks[0].engineEdm.volume)
        assertEquals(0.75f, updated.tracks[0].engineSpace.volume, 1e-5f)
        assertEquals(emptyList(), capture(updated, BenchLayers()))
    }

    @Test
    fun eachRowLandsOnItsField() {
        val rows = capture(base, everything)
        val v = base.withCapture(rows)
        for (r in rows) {
            val got = when (r.scope) {
                Scope.VIBE -> when (r.field) {
                    CaptureField.VOLUME -> v.tracks[r.track!!].engineEdm.volume
                    CaptureField.VOLUME_SPACE -> v.tracks[r.track!!].engineSpace.volume
                    CaptureField.PAN -> v.tracks[r.track!!].pan
                    CaptureField.REVERB_SEND -> v.tracks[r.track!!].engineEdm.reverbSend
                    CaptureField.REVERB_SEND_SPACE -> v.tracks[r.track!!].engineSpace.reverbSend
                    CaptureField.DELAY_SEND -> v.tracks[r.track!!].engineEdm.delaySend
                    CaptureField.DELAY_SEND_SPACE -> v.tracks[r.track!!].engineSpace.delaySend
                    CaptureField.MACRO -> r.macro!!.vibeDefault(v)
                    CaptureField.MACRO_RAMP -> error("no whole-vibe ramp rows")
                    CaptureField.DELAY_TIME_A -> v.effects.delayTimeA
                    CaptureField.DELAY_TIME_B -> v.effects.delayTimeB
                    CaptureField.DELAY_FEEDBACK -> v.effects.delayFeedback
                }
                Scope.SECTION -> {
                    val section = v.arrangement!!.sections[r.section!!]
                    when (r.field) {
                        CaptureField.VOLUME -> section.trackOverrides!![r.track]!!.volume!!
                        CaptureField.REVERB_SEND -> section.trackOverrides!![r.track]!!.reverbSend!!
                        CaptureField.DELAY_SEND -> section.trackOverrides!![r.track]!!.delaySend!!
                        CaptureField.MACRO -> section.macroOverrides.multiplier(r.macro!!)!!
                        CaptureField.MACRO_RAMP -> section.rampTo.multiplier(r.macro!!)!!
                        CaptureField.DELAY_TIME_A -> section.delay!!.timeA!!
                        CaptureField.DELAY_TIME_B -> section.delay!!.timeB!!
                        CaptureField.DELAY_FEEDBACK -> section.delay!!.feedback!!
                        else -> error("no section ${r.field} rows")
                    }
                }
            }
            assertEquals(r.new, got, 1e-6f, "$r")
        }
    }

    @Test
    fun keepsWhatTheRowsDoNotTouch() {
        val rows = capture(base, everything)
        val v = base.withCapture(rows)
        val verse = v.arrangement!!.sections[0]
        val chorus = v.arrangement!!.sections[1]
        // A new override keeps the existing fields of the track's override, and the section's macro volume.
        assertEquals(0.4f, verse.trackOverrides!![5]!!.density)
        assertEquals(0.9f, chorus.macroOverrides!!.volume)
        assertEquals(base.bpm, v.bpm)
        assertEquals(base.tracks[7], v.tracks[7])
        assertEquals(base.withCapture(emptyList()), base)
    }

    @Test
    fun rejectsARowItCannotPlace() {
        assertFailsWith<IllegalArgumentException> {
            base.withCapture(listOf(CaptureRow(Scope.SECTION, 9, "x", 0, CaptureField.VOLUME, null, 0f, 1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            base.withCapture(listOf(CaptureRow(Scope.VIBE, null, null, null, CaptureField.VOLUME, null, 0f, 1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            base.withCapture(listOf(CaptureRow(Scope.SECTION, 0, "verse", 0, CaptureField.PAN, null, 0f, 1f)))
        }
    }
}
