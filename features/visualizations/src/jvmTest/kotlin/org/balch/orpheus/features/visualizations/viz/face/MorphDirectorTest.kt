package org.balch.orpheus.features.visualizations.viz.face

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MorphDirectorTest {
    private val dt = 1f / 60f

    /** The song position where the user's pacing curve has turned the face [floor] of the way. */
    private fun progressAt(floor: Float): Float {
        var lo = 0f; var hi = 1f
        repeat(30) { val m = (lo + hi) / 2f; if (shape(m) < floor) lo = m else hi = m }
        return hi
    }

    // Tests probe where the face is half turned, wherever the user's pacing curve puts that.
    private val mid: Float = progressAt(0.5f)

    private fun MorphDirector.run(seconds: Float, progress: Float, level: Float): MorphFrame {
        var frame = update(dt, progress, level, 0f, 0f)
        repeat((seconds / dt).toInt()) { frame = update(dt, progress, level, 0f, 0f) }
        return frame
    }

    @Test fun `the floor never falls when progress slips back`() {
        val d = MorphDirector()
        val at60 = d.run(1f, progress = 0.6f, level = 0f).morph
        // The seek bar's duration estimate can grow mid-song, which nudges the fraction down.
        val at50 = d.run(1f, progress = 0.5f, level = 0f).morph
        assertTrue(at50 >= at60 - 1e-5f, "floor fell from $at60 to $at50")
    }

    @Test fun `the end of the song is fully turned at any level`() {
        assertEquals(1f, MorphDirector().run(1f, 1f, 0f).morph, 1e-4f)
        assertEquals(1f, MorphDirector().run(1f, 1f, 1f).morph, 1e-4f)
    }

    @Test fun `a loud passage surges and then relaxes back to the floor`() {
        val d = MorphDirector()
        val quiet = d.run(2f, mid, 0f).morph
        val loud = d.run(4f, mid, 1f).morph
        assertTrue(loud > quiet + 0.02f, "no surge: $quiet -> $loud")
        val after = d.run(15f, mid, 0f).morph
        assertEquals(quiet, after, 0.01f)
    }

    @Test fun `a single hit does not surge the face`() {
        val d = MorphDirector()
        val quiet = d.run(2f, mid, 0f).morph
        val blip = d.run(0.05f, mid, 1f).morph
        assertTrue(blip - quiet < 0.5f * (MorphDirector().run(4f, mid, 1f).morph - quiet))
    }

    @Test fun `sixteenth-note kicks flash under three times a second`() {
        val d = MorphDirector()
        val stepS = 15f / 140f          // a 16th at 140 bpm
        var t = 0f; var wasHigh = false
        val edges = mutableListOf<Float>()
        while (t < 10f) {
            val phase = (t % stepS) / stepS
            val kick = if (phase < 0.3f) 1f else 0f
            val f = d.update(dt, mid, 0.5f, kick, 0f).flicker
            val high = f > 0.3f
            if (high && !wasHigh) edges += t
            wasHigh = high
            t += dt
        }
        assertTrue(edges.size >= 2, "expected repeated flashes, got ${edges.size}")
        edges.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= MorphDirector.MIN_FLASH_GAP_S - dt, "flashes at $a and $b are too close")
        }
    }

    @Test fun `sixteenth-note snares glitch under three times a second`() {
        val d = MorphDirector()
        val stepS = 15f / 140f          // a 16th at 140 bpm
        var t = 0f; var wasHigh = false
        val edges = mutableListOf<Float>()
        while (t < 10f) {
            val phase = (t % stepS) / stepS
            val snare = if (phase < 0.3f) 1f else 0f
            val g = d.update(dt, mid, 0.5f, 0f, snare).glitch
            val high = g > 0.3f
            if (high && !wasHigh) edges += t
            wasHigh = high
            t += dt
        }
        assertTrue(edges.size >= 2, "expected repeated glitches, got ${edges.size}")
        edges.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= MorphDirector.MIN_FLASH_GAP_S - dt, "glitches at $a and $b are too close")
        }
    }

    @Test fun `flicker is capped`() {
        val d = MorphDirector()
        d.update(dt, mid, 0.5f, 0f, 0f)
        assertTrue(d.update(dt, mid, 0.5f, 1f, 0f).flicker <= MorphDirector.FLICKER_PEAK)
    }

    @Test fun `an untouched face does not flash`() {
        val d = MorphDirector()
        d.update(dt, 0f, 0f, 0f, 0f)
        assertEquals(0f, d.update(dt, 0f, 0f, 1f, 0f).flicker, 1e-6f)
    }

    @Test fun `a snare onset glitches and decays`() {
        val d = MorphDirector()
        d.update(dt, mid, 0.5f, 0f, 0f)
        val hit = d.update(dt, mid, 0.5f, 0f, 1f).glitch
        assertTrue(hit > 0.8f)
        var g = hit
        repeat(60) { g = d.update(dt, mid, 0.5f, 0f, 1f).glitch }  // held level is not an onset
        assertTrue(g < 0.05f, "glitch stuck at $g")
    }

    /** Drum level for a hit that rises over two frames and rings out, as the 60 Hz poll sees it. */
    private fun hit(peak: Float, framesIn: Int): Float = when (framesIn) {
        0 -> peak * 0.5f
        1 -> peak
        else -> peak * exp(-(framesIn - 1) * dt / 0.06f)
    }

    @Test fun `soft drums at the top of the song still glitch`() {
        val d = MorphDirector()
        var glitches = 0; var wasUp = false; var weakest = 1f
        // A quiet intro: hits peaking at 0.08, twice a second, on an untouched face.
        for (frame in 0 until 300) {
            val g = d.update(dt, 0f, 0.05f, 0f, hit(0.08f, frame % 30)).glitch
            val up = g > 0.3f
            if (up && !wasUp) { glitches++; weakest = min(weakest, g) }
            wasUp = up
        }
        assertTrue(glitches >= 8, "only $glitches glitches from 10 soft hits")
        assertTrue(weakest >= MorphDirector.GLITCH_FLOOR, "a soft hit showed at only $weakest")
    }

    @Test fun `a steady loud bed with ripple does not glitch`() {
        val d = MorphDirector()
        repeat(60) { d.update(dt, mid, 0.6f, 0f, 0.6f) }   // settle on the bed
        var worst = 0f
        for (frame in 0 until 300) {
            val ripple = 0.6f + 0.02f * sin(frame * 0.9f)
            worst = max(worst, d.update(dt, mid, 0.6f, 0f, ripple).glitch)
        }
        assertEquals(0f, worst, 1e-6f)
    }

    @Test fun `a soft hit under a loud passage is ignored but a loud one is not`() {
        val d = MorphDirector()
        // Loud hits set the scale; a 0.06 tick between them is hi-hat bleed, not a drum.
        var g = 0f
        for (frame in 0 until 120) g = max(g, d.update(dt, mid, 0.6f, 0f, hit(0.9f, frame % 40)).glitch)
        assertTrue(g > 0.8f)
        repeat(40) { d.update(dt, mid, 0.6f, 0f, 0f) }      // let the last glitch die
        var tick = 0f
        for (frame in 0 until 10) tick = max(tick, d.update(dt, mid, 0.6f, 0f, hit(0.06f, frame)).glitch)
        assertEquals(0f, tick, 1e-6f)
    }

    @Test fun `a new song resets the floor`() {
        val d = MorphDirector()
        d.run(1f, 0.9f, 0f)
        val fresh = d.run(1f, 0.02f, 0f).morph
        assertTrue(fresh < 0.5f, "floor survived a new song: $fresh")
    }

    /** Seconds of mono == 1 that a single kick onset buys at the given song position. */
    private fun glimpseSeconds(progress: Float): Float {
        val d = MorphDirector()
        d.run(0.5f, progress, 0f)
        var seconds = 0f
        var frame = d.update(dt, progress, 0f, 1f, 0f)
        while (frame.mono > 0.5f && seconds < 5f) {
            seconds += dt
            frame = d.update(dt, progress, 0f, 1f, 0f)  // held level is not a new onset
        }
        return seconds
    }

    @Test fun `an untouched face does not go black and white on a kick`() {
        val d = MorphDirector()
        d.update(dt, 0f, 0f, 0f, 0f)
        assertEquals(0f, d.update(dt, 0f, 0f, 1f, 0f).mono, 1e-6f)
    }

    @Test fun `a kick glimpses black and white and then returns to colour`() {
        val d = MorphDirector()
        val start = progressAt(0.3f)
        d.run(0.5f, start, 0f)
        assertEquals(0f, d.update(dt, start, 0f, 0f, 0f).mono, 1e-6f)
        assertEquals(1f, d.update(dt, start, 0f, 1f, 0f).mono, 1e-6f)
        val after = d.run(3f, start, 0f)
        assertEquals(0f, after.mono, 1e-6f)
    }

    @Test fun `glimpses hold longer later in the song`() {
        val early = glimpseSeconds(progressAt(0.2f))
        val later = glimpseSeconds(progressAt(0.5f))
        assertTrue(later > early + 0.1f, "glimpse did not grow: $early -> $later")
    }

    @Test fun `past two thirds the picture stays black and white without any kick`() {
        val d = MorphDirector()
        val late = progressAt(MorphDirector.MONO_PERMANENT + 0.02f)
        assertEquals(1f, d.run(1f, late, 0.8f).mono, 1e-6f)
        assertEquals(1f, d.run(5f, late, 0f).mono, 1e-6f)
    }

    @Test fun `a knob dithering across the threshold cannot flicker the picture`() {
        val d = MorphDirector()
        // Just under the threshold, so the offset alone decides which side of it we are on.
        val progress = progressAt(MorphDirector.MONO_PERMANENT - 0.01f)
        d.run(0.5f, progress, 0f)
        var wasHigh = false
        var rises = 0
        var fellAfterRising = false
        var t = 0f
        var step = 0
        while (t < 5f) {
            // A MIDI-bound knob landing one LSB either side of the threshold, every frame.
            val offset = if (step % 2 == 0) 0.02f else 0f
            val high = d.update(dt, progress, 0f, 0f, 0f, floorOffset = offset).mono > 0.5f
            if (high && !wasHigh) rises++
            if (!high && wasHigh) fellAfterRising = true
            wasHigh = high
            step++
            t += dt
        }
        assertEquals(1, rises, "the picture switched $rises times while the knob dithered")
        assertTrue(!fellAfterRising, "the picture came back to colour after latching")
    }

    @Test fun `turning the decay knob back down does not undo the latch`() {
        val d = MorphDirector()
        val progress = progressAt(MorphDirector.MONO_PERMANENT - 0.01f)
        d.run(0.5f, progress, 0f)
        assertEquals(1f, d.update(dt, progress, 0f, 0f, 0f, floorOffset = 0.05f).mono, 1e-6f)
        assertEquals(1f, d.run(3f, progress, 0f).mono, 1e-6f)
    }

    @Test fun `sixteenth-note kicks switch to black and white under three times a second`() {
        val d = MorphDirector()
        // Early in the turn the hold is shorter than the flash gap, so this is where mono can
        // actually switch repeatedly; later the holds overlap and it simply stays mono.
        val early = progressAt(0.12f)
        val stepS = 15f / 140f          // a 16th at 140 bpm
        var t = 0f; var wasHigh = false
        val edges = mutableListOf<Float>()
        while (t < 10f) {
            val phase = (t % stepS) / stepS
            val kick = if (phase < 0.3f) 1f else 0f
            val high = d.update(dt, early, 0.5f, kick, 0f).mono > 0.5f
            if (high && !wasHigh) edges += t
            wasHigh = high
            t += dt
        }
        assertTrue(edges.size >= 2, "expected repeated glimpses, got ${edges.size}")
        edges.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= MorphDirector.MIN_FLASH_GAP_S - dt, "glimpses at $a and $b are too close")
        }
    }

    @Test fun `a new song clears black and white`() {
        val d = MorphDirector()
        d.run(1f, 0.95f, 0f)
        assertEquals(1f, d.update(dt, 0.95f, 0f, 0f, 0f).mono, 1e-6f)
        assertEquals(0f, d.run(1f, 0.02f, 0f).mono, 1e-6f)
    }

    @Test fun `glitch reaches exact zero within one second of a single snare onset`() {
        val d = MorphDirector()
        d.update(dt, mid, 0.5f, 0f, 0f)
        d.update(dt, mid, 0.5f, 0f, 1f) // onset
        var frame = MorphFrame(0f, 0f, 1f, 0f, 0f)
        var t = 0f
        while (t < 1f && frame.glitch != 0f) {
            frame = d.update(dt, mid, 0.5f, 0f, 0f)
            t += dt
        }
        assertEquals(0f, frame.glitch, "glitch never snapped to exact zero within 1 s, at t=$t")
    }

    @Test fun `flicker reaches exact zero within one second of a single kick onset`() {
        val d = MorphDirector()
        d.update(dt, mid, 0.5f, 0f, 0f)
        d.update(dt, mid, 0.5f, 1f, 0f) // onset
        var frame = MorphFrame(0f, 1f, 0f, 0f, 0f)
        var t = 0f
        while (t < 1f && frame.flicker != 0f) {
            frame = d.update(dt, mid, 0.5f, 0f, 0f)
            t += dt
        }
        assertEquals(0f, frame.flicker, "flicker never snapped to exact zero within 1 s, at t=$t")
    }

    @Test fun `the decay knob offsets the floor without ratcheting`() {
        val d = MorphDirector()
        d.run(1f, mid, 0f)
        val up = d.update(dt, mid, 0f, 0f, 0f, floorOffset = 0.2f).morph
        val back = d.update(dt, mid, 0f, 0f, 0f, floorOffset = 0f).morph
        assertTrue(up > back, "offset had no effect")
    }
}
