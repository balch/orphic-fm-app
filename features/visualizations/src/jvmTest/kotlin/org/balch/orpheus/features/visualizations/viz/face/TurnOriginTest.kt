package org.balch.orpheus.features.visualizations.viz.face

import org.balch.orpheus.core.media.PlaybackProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnOriginTest {
    /** Feeds [progress] to [origin] in small steps until [totalSeconds] have elapsed. */
    private fun advance(
        origin: TurnOrigin,
        progress: PlaybackProgress?,
        totalSeconds: Float,
        dtStep: Float = 0.1f,
    ): Float {
        var result = 0f
        val steps = (totalSeconds / dtStep).toInt()
        repeat(steps) { result = origin.fraction(progress, dtStep) }
        return result
    }

    @Test fun `activation at 0point6 opens human then rises to one at song end`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 600, durationMs = 1000))

        assertEquals(0f, origin.fraction(PlaybackProgress(positionMs = 600, durationMs = 1000), 0.016f))
        assertEquals(
            1f,
            origin.fraction(PlaybackProgress(positionMs = 1000, durationMs = 1000), 0.016f),
            1e-4f,
        )
    }

    @Test fun `activation at 0point6, 0point8 of the song maps to 0point5`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 600, durationMs = 1000))

        val f = origin.fraction(PlaybackProgress(positionMs = 800, durationMs = 1000), 0.016f)
        assertEquals(0.5f, f, 1e-4f)
    }

    @Test fun `activation past 0point95 remaining uses the fallback from zero`() {
        val origin = TurnOrigin()
        val sliver = PlaybackProgress(positionMs = 970, durationMs = 1000)
        origin.onActivate(sliver)

        assertEquals(0f, origin.fraction(sliver, 0f))
        assertEquals(0.5f, advance(origin, sliver, 120f), 1e-4f)
    }

    @Test fun `activation with null progress uses the fallback from zero`() {
        val origin = TurnOrigin()
        origin.onActivate(null)

        assertEquals(0f, origin.fraction(null, 0f))
        assertEquals(0.5f, advance(origin, null, 120f), 1e-4f)
    }

    @Test fun `song change holds at zero while the stale value is still reported`() {
        val origin = TurnOrigin()
        val oldSong = PlaybackProgress(positionMs = 200, durationMs = 1000)
        origin.onActivate(oldSong)
        origin.onSongChange(oldSong)

        assertEquals(0f, origin.fraction(oldSong, 0.05f))
        assertEquals(0f, origin.fraction(oldSong, 0.05f))
        assertEquals(0f, origin.fraction(oldSong, 0.05f))
    }

    @Test fun `song change follows the new song from its own zero once progress moves`() {
        val origin = TurnOrigin()
        val oldSong = PlaybackProgress(positionMs = 200, durationMs = 1000)
        origin.onActivate(oldSong)
        origin.onSongChange(oldSong)
        origin.fraction(oldSong, 0.05f)

        val newSong = PlaybackProgress(positionMs = 1, durationMs = 1000)
        val atHandoff = origin.fraction(newSong, 0.05f)
        assertEquals(0f, atHandoff, 1e-3f)

        val later = origin.fraction(PlaybackProgress(positionMs = 500, durationMs = 1000), 0.05f)
        assertTrue(later > 0f, "later = $later")
    }

    // Was: "song change gives up waiting after one second and rebases anyway", asserting the
    // final read equalled (0.6 - 0.2) / (1 - 0.2) = 0.5, i.e. that the timeout rebased directly
    // onto the stale 0.2 reading it had just declared unusable. That is exactly the 1b bug
    // (rebasing onto the reading you just gave up on); the fix runs the fallback from zero on
    // timeout instead, so the later read must stay continuous with that fallback clock, not jump
    // to the old value's origin. Renamed and re-asserted below.
    @Test fun `song change gives up waiting after one second and falls back from zero`() {
        val origin = TurnOrigin()
        val oldSong = PlaybackProgress(positionMs = 200, durationMs = 1000)
        origin.onSongChange(oldSong)

        repeat(19) { origin.fraction(oldSong, 0.05f) } // 0.95 s, still the stale value
        assertEquals(0f, origin.fraction(oldSong, 0.049f)) // ~0.999 s, still under the timeout

        // Crossing 1.0 s runs the fallback from zero instead of rebasing onto the stale value.
        val afterTimeout = origin.fraction(oldSong, 0.01f)
        assertEquals(0f, afterTimeout, 1e-4f)

        // A different reading arrives: re-adopted without a jump, so it stays close to the
        // fallback clock's own value rather than snapping onto the new song position.
        val onArrival = origin.fraction(PlaybackProgress(positionMs = 600, durationMs = 1000), 0.05f)
        assertTrue(onArrival >= afterTimeout - 1e-6f, "dropped from $afterTimeout to $onArrival")
        assertTrue(onArrival < 0.1f, "expected continuity with the near-zero fallback, got $onArrival")

        // Once re-adopted, further song progress catches the face up to the song's own ending.
        val later = origin.fraction(PlaybackProgress(positionMs = 900, durationMs = 1000), 0.05f)
        assertTrue(later > onArrival, "did not advance after re-adopting: $onArrival -> $later")
        val atEnd = origin.fraction(PlaybackProgress(positionMs = 1000, durationMs = 1000), 0.05f)
        assertEquals(1f, atEnd, 1e-4f)
    }

    @Test fun `song change to a section-less vibe runs the fallback from zero`() {
        val origin = TurnOrigin()
        val oldSong = PlaybackProgress(positionMs = 200, durationMs = 1000)
        origin.onActivate(oldSong)
        origin.onSongChange(oldSong)

        // Null differs from the stale non-null reading, so there is nothing to wait out.
        assertEquals(0f, origin.fraction(null, 0f))
        assertEquals(0.5f, advance(origin, null, 120f), 1e-4f)
    }

    @Test fun `song change with an already-null stale value skips the wait`() {
        val origin = TurnOrigin()
        origin.onActivate(null)
        origin.onSongChange(null)

        assertEquals(0f, origin.fraction(null, 0f))
        assertEquals(0.5f, advance(origin, null, 120f), 1e-4f)
    }

    // Was: "duration re-estimate stepping backwards never goes negative", asserting the backward
    // step landed on exactly 0 -- i.e. that the turn itself fell, just not below zero. That is
    // exactly the bug the ratchet (below) fixes: the turn must never run backward mid-song, so a
    // step below the origin now holds at the last value instead of dropping to 0. Renamed and
    // re-asserted below; see "the ratchet never lets a duration re-estimate run the turn backward".
    @Test fun `duration re-estimate stepping backwards is finite and never negative`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 300, durationMs = 1000)) // origin 0.3

        val forward = origin.fraction(PlaybackProgress(positionMs = 350, durationMs = 1000), 0.05f)
        assertTrue(forward >= 0f && forward.isFinite())

        // A duration re-estimate nudges the reported fraction back below the origin.
        val backwards = origin.fraction(PlaybackProgress(positionMs = 290, durationMs = 1050), 0.05f)
        assertTrue(backwards.isFinite())
        assertTrue(backwards >= 0f)
    }

    @Test fun `zero duration mid-song is safe and falls back`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 300, durationMs = 1000))

        val f = origin.fraction(PlaybackProgress(positionMs = 10, durationMs = 0), 0.05f)
        assertTrue(f.isFinite())
        assertTrue(f in 0f..1f)
    }

    @Test fun `non-finite dt is safe`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 300, durationMs = 1000))

        val f = origin.fraction(PlaybackProgress(positionMs = 300, durationMs = 1000), Float.NaN)
        assertTrue(f.isFinite())
    }

    // ─── 1a: a null reading mid-song must never fake a song change ────────────────────────────

    @Test fun `a null reading mid-song holds at the last fraction during the grace window`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 300, durationMs = 1000)) // origin 0.3
        val held = origin.fraction(PlaybackProgress(positionMs = 500, durationMs = 1000), 0.05f)
        assertTrue(held > 0f)

        assertEquals(held, origin.fraction(null, 0.5f), 1e-6f)
        assertEquals(held, origin.fraction(null, 0.4f), 1e-6f) // still under the 1 s grace
    }

    @Test fun `grace expiry seeds the fallback from the last fraction and never wraps`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 300, durationMs = 1000)) // origin 0.3
        val held = origin.fraction(PlaybackProgress(positionMs = 500, durationMs = 1000), 0.05f)

        var last = held
        repeat(30) { last = origin.fraction(null, 0.05f) } // 1.5 s of nulls: grace expires partway
        assertTrue(last >= held - 1e-6f, "dropped from $held to $last")

        // Continues rising on the seeded clock instead of snapping back toward 0, and never
        // wraps back down even long after a normal fallback cycle (FALLBACK_TURN_S) would.
        var muchLater = last
        repeat(3000) { muchLater = origin.fraction(null, 0.1f) } // +300 s of continued silence
        assertEquals(1f, muchLater, 1e-4f, "a dropped-out turn must hold at 1, not wrap")
    }

    @Test fun `a reading that catches back up during grace resumes following without a jump`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 200, durationMs = 1000)) // origin 0.2
        val held = origin.fraction(PlaybackProgress(positionMs = 400, durationMs = 1000), 0.05f)

        origin.fraction(null, 0.2f) // brief dropout, still within the grace window

        // The reading that returns is at/beyond the song position the held fraction implies.
        val onReturn = origin.fraction(PlaybackProgress(positionMs = 450, durationMs = 1000), 0.05f)
        assertEquals(held, onReturn, 1e-4f, "re-adopting jumped instead of staying continuous")

        val later = origin.fraction(PlaybackProgress(positionMs = 900, durationMs = 1000), 0.05f)
        assertTrue(later > onReturn, "did not resume rising after re-adopting: $onReturn -> $later")
    }

    @Test fun `a reading behind the last fraction holds until the song catches up`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 500, durationMs = 1000)) // origin 0.5
        val held = origin.fraction(PlaybackProgress(positionMs = 800, durationMs = 1000), 0.05f) // 0.6

        origin.fraction(null, 0.2f) // dropout, holding at 0.6

        // These readings are behind where the face already turned to (frac < held).
        val behind = origin.fraction(PlaybackProgress(positionMs = 550, durationMs = 1000), 0.05f)
        assertEquals(held, behind, 1e-6f, "face moved instead of holding for the song to catch up")
        val stillBehind = origin.fraction(PlaybackProgress(positionMs = 590, durationMs = 1000), 0.05f)
        assertEquals(held, stillBehind, 1e-6f)

        // Once the song reaches the held fraction's song position, following resumes.
        val caughtUp = origin.fraction(PlaybackProgress(positionMs = 650, durationMs = 1000), 0.05f)
        assertEquals(held, caughtUp, 1e-4f, "resuming should be continuous, not a jump")

        val later = origin.fraction(PlaybackProgress(positionMs = 900, durationMs = 1000), 0.05f)
        assertTrue(later > caughtUp, "did not advance after catching up: $caughtUp -> $later")
    }

    @Test fun `a reading returning after the fallback has taken over re-adopts without a jump`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 200, durationMs = 1000)) // origin 0.2
        origin.fraction(PlaybackProgress(positionMs = 400, durationMs = 1000), 0.05f)

        // Drive well past the 1 s grace so the dropped-out fallback clock takes over.
        var held = 0f
        repeat(30) { held = origin.fraction(null, 0.05f) } // 1.5 s of nulls

        // A real reading returns, ahead of where the fallback clock has reached.
        val onReturn = origin.fraction(PlaybackProgress(positionMs = 990, durationMs = 1000), 0.05f)
        assertTrue(onReturn >= held - 1e-6f, "dropped from $held to $onReturn on recovery")

        val later = origin.fraction(PlaybackProgress(positionMs = 1000, durationMs = 1000), 0.05f)
        assertEquals(1f, later, 1e-4f)
    }

    // ─── Integration: TurnOrigin feeding a real MorphDirector (they were only ever tested apart)

    @Test fun `a mid-song dropout feeding a real MorphDirector never decreases morph or unlatches mono`() {
        // Bisect the turn fraction (TurnOrigin's own output, fed to MorphDirector as `progress`)
        // at which the mono latch engages, the same way MorphDirectorTest's `mid` bisects shape()
        // thresholds: MorphCurves.kt is tuned by its owner and must never be hardcoded against.
        var lo = 0f
        var hi = 1f
        repeat(30) {
            val m = (lo + hi) / 2f
            if (shape(m) < MorphDirector.MONO_PERMANENT) lo = m else hi = m
        }
        val followTo = (hi + 0.05f).coerceAtMost(0.95f)

        val turnOrigin = TurnOrigin()
        val director = MorphDirector()
        val durationMs = 100_000L
        val dt = 1f / 60f
        val stepMs = 17L

        turnOrigin.onActivate(PlaybackProgress(0L, durationMs))

        var elapsedMs = 0L
        var lastMorph = 0f
        var frame = director.update(dt, 0f, 0f, 0f, 0f)
        while (elapsedMs.toFloat() / durationMs < followTo) {
            elapsedMs += stepMs
            val turn = turnOrigin.fraction(PlaybackProgress(elapsedMs, durationMs), dt)
            frame = director.update(dt, turn, 0f, 0f, 0f)
            assertTrue(frame.morph >= lastMorph - 1e-6f, "morph fell: $lastMorph -> ${frame.morph}")
            lastMorph = frame.morph
        }
        assertEquals(1f, frame.mono, "mono did not latch before the dropout")

        fun step(progress: PlaybackProgress?) {
            val turn = turnOrigin.fraction(progress, dt)
            val f = director.update(dt, turn, 0f, 0f, 0f)
            assertTrue(f.morph >= lastMorph - 1e-6f, "morph fell around the dropout: $lastMorph -> ${f.morph}")
            assertEquals(1f, f.mono, "mono came back to colour around the dropout")
            lastMorph = f.morph
        }

        // One null frame, then 0.5 s of nulls, then 3 s of nulls: crosses both the mid-song grace
        // window and the fallback promotion, all while progress keeps reporting nothing.
        step(null)
        var t = 0f
        while (t < 0.5f) { step(null); t += dt }
        t = 0f
        while (t < 3f) { step(null); t += dt }

        // Readings resume, further along the song than where the dropout began.
        elapsedMs = ((followTo + 0.1f).coerceAtMost(0.98f) * durationMs).toLong()
        repeat(120) {
            elapsedMs += stepMs
            step(PlaybackProgress(elapsedMs.coerceAtMost(durationMs), durationMs))
        }

        assertTrue(lastMorph > 0f)
    }

    // ─── Fix 2: FOLLOWING has no ratchet, so a real backward step in the raw fraction (not a
    // null/dropout, which the states above already cover) used to turn the face backward too.

    @Test fun `the ratchet never lets a duration re-estimate run the turn backward`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 300, durationMs = 1000)) // origin 0.3

        // Follow to the raw fraction's own 1.0 under the estimated duration.
        val atEstimateEnd = origin.fraction(PlaybackProgress(positionMs = 1000, durationMs = 1000), 0.05f)
        assertEquals(1f, atEstimateEnd, 1e-4f)

        // SongProgressTracker switches durationMs from estimatedMs to maxMs once position crosses
        // the estimate: the raw fraction falls (1000/1000 = 1.0 -> 1000/1250 = 0.8, a 0.2 drop).
        val afterSwitch = origin.fraction(PlaybackProgress(positionMs = 1000, durationMs = 1250), 0.05f)
        assertTrue(afterSwitch >= atEstimateEnd - 1e-6f, "dropped from $atEstimateEnd to $afterSwitch")
    }

    @Test fun `a duration re-estimate feeding a real MorphDirector does not reset morph or unlatch mono`() {
        // Bisect the turn fraction at which MorphDirector's mono latch engages, the same way
        // MorphDirectorTest's mid/progressAt do: MorphCurves.kt is owner-tuned and never
        // hardcoded against.
        var lo = 0f
        var hi = 1f
        repeat(30) {
            val m = (lo + hi) / 2f
            if (shape(m) < MorphDirector.MONO_PERMANENT) lo = m else hi = m
        }
        val latchAt = hi

        val origin = TurnOrigin()
        val director = MorphDirector()
        val dt = 1f / 60f
        val estimatedMs = 100_000L
        val stepMs = 17L

        origin.onActivate(PlaybackProgress(positionMs = 30_000, durationMs = estimatedMs)) // origin 0.30

        var elapsedMs = 30_000L
        var lastMorph = 0f
        var frame = director.update(dt, 0f, 0f, 0f, 0f)
        var latched = false
        while (elapsedMs < estimatedMs) {
            elapsedMs += stepMs
            val turn = origin.fraction(PlaybackProgress(elapsedMs.coerceAtMost(estimatedMs), estimatedMs), dt)
            frame = director.update(dt, turn, 0f, 0f, 0f)
            assertTrue(frame.morph >= lastMorph - 1e-6f, "morph fell: $lastMorph -> ${frame.morph}")
            lastMorph = frame.morph
            if (turn >= latchAt) latched = true
        }
        assertTrue(latched, "turn never reached the mono-latch threshold before the estimate's raw end")
        assertEquals(1f, frame.mono, "mono did not latch by the estimated duration's raw end")

        // SongProgressTracker widens durationMs once position crosses the estimate: the raw
        // fraction falls by up to just under 0.25, which TurnOrigin's own division (dividing by
        // 1 - origin) can turn into a bigger drop still -- exactly the false "new song" that
        // MorphDirector.NEW_SONG_DROP guards against. The ratchet must absorb it before it ever
        // reaches MorphDirector.
        val newDurationMs = (estimatedMs * 1.25f).toLong()
        repeat(30) {
            val turn = origin.fraction(PlaybackProgress(estimatedMs, newDurationMs), dt)
            frame = director.update(dt, turn, 0f, 0f, 0f)
            assertTrue(
                frame.morph >= lastMorph - 1e-6f,
                "morph fell around the duration switch: $lastMorph -> ${frame.morph}",
            )
            assertEquals(1f, frame.mono, "mono came back to colour around the duration switch")
            lastMorph = frame.morph
        }
    }

    @Test fun `a small backward jitter every few frames never decreases the output over ten seconds`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 100, durationMs = 1000)) // origin 0.1

        var last = 0f
        var t = 0f
        var frameIndex = 0
        var posMs = 100L
        val dtStep = 0.05f
        while (t < 10f) {
            // Advance, but every third frame apply a small backward jitter (a live tempo
            // re-measurement stepping the position estimate back inside a section).
            posMs = (posMs + if (frameIndex % 3 == 2) -3L else 5L).coerceAtLeast(0L)
            val v = origin.fraction(PlaybackProgress(posMs, durationMs = 1000), dtStep)
            assertTrue(v >= last - 1e-6f, "decreased from $last to $v at t=$t")
            last = v
            t += dtStep
            frameIndex++
        }
    }

    @Test fun `onSongChange still restarts the ratchet after it has climbed high`() {
        val origin = TurnOrigin()
        origin.onActivate(PlaybackProgress(positionMs = 0, durationMs = 1000))

        val high = origin.fraction(PlaybackProgress(positionMs = 900, durationMs = 1000), 0.05f)
        assertTrue(high >= 0.9f, "did not reach 0.9: $high")

        origin.onSongChange(null) // nothing stale to wait out
        val afterChange = origin.fraction(PlaybackProgress(positionMs = 5, durationMs = 1000), 0.05f)
        assertTrue(afterChange < 0.1f, "the ratchet survived the song change: $afterChange")
    }

    @Test fun `the never-had-progress fallback still wraps past a full cycle`() {
        val origin = TurnOrigin()
        origin.onActivate(null) // never had progress: the classic wrapping fallback

        val justBeforeWrap = advance(origin, null, FALLBACK_TURN_S - 1f)
        assertTrue(justBeforeWrap > 0.9f, "expected near the top of the cycle: $justBeforeWrap")

        // fraction() clamps dt to 0.1s per call, so advance the extra 2s in small steps too.
        val justAfterWrap = advance(origin, null, 2f)
        assertTrue(justAfterWrap < 0.1f, "the fallback failed to wrap back down: $justAfterWrap")
    }
}
