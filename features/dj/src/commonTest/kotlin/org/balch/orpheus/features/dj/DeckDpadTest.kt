package org.balch.orpheus.features.dj

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.balch.orpheus.core.plugin.symbols.DjDrop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records every action the routine takes, so the sequence can be asserted. */
private class RecordingDeck {
    val drags = mutableListOf<Pair<Int, Float>>()
    val releases = mutableListOf<Int>()
    val dragActive = mutableListOf<Pair<Int, Boolean>>()
    val drops = mutableListOf<Pair<Int, DjDrop>>()

    val actions = DjPanelActions.EMPTY.copy(
        setPlatterDrag = { deck, v -> drags += deck to v },
        setPlatterRelease = { deck -> releases += deck },
        setDragActive = { deck, active -> dragActive += deck to active },
        setDrop = { deck, drop -> drops += deck to drop },
    )
}

private val AllZones = listOf(
    DjDrop.FILTER, DjDrop.BRAKE, DjDrop.STUTTER, DjDrop.FREEZE,
    DjDrop.OCTAVE, DjDrop.PHASER, DjDrop.ECHO, DjDrop.RING,
)

class DeckDpadTest {

    @Test
    fun dropGrabsTheDeckThenAlwaysReleasesIt() = runTest {
        val deck = RecordingDeck()
        performRandomDrop(1, deck.actions, AllZones, beatMillis = 500L, random = Random(7))

        assertEquals(1 to true, deck.dragActive.first(), "must take the platter before scratching")
        assertEquals(1 to false, deck.dragActive.last(), "must hand the platter back")
        assertEquals(listOf(1), deck.releases, "exactly one release")
        assertEquals(0f, deck.drags.last().second, "settles at rest before releasing")
    }

    @Test
    fun dropLatchesAnEffectAndClearsIt() = runTest {
        val deck = RecordingDeck()
        performRandomDrop(0, deck.actions, AllZones, beatMillis = 500L, random = Random(3))

        assertTrue(deck.drops.size >= 2, "one to latch, one to clear")
        assertTrue(deck.drops.first().second != DjDrop.NONE, "a real drop is chosen")
        assertEquals(DjDrop.NONE, deck.drops.last().second, "the effect is cleared afterwards")
    }

    @Test
    fun cancellationStillReleasesTheDeck() = runTest {
        // The routine holds the platter and latches an effect; if a cancelled run skipped its
        // cleanup the deck would be stranded frozen with the effect still on.
        val deck = RecordingDeck()
        runCatching {
            withTimeout(120) {
                performRandomDrop(0, deck.actions, AllZones, beatMillis = 4_000L, random = Random(1))
            }
        }

        assertEquals(listOf(0), deck.releases, "a cancelled drop still releases the platter")
        assertEquals(0 to false, deck.dragActive.last(), "and gives the platter back")
        assertEquals(DjDrop.NONE, deck.drops.last().second, "and clears the effect")
    }

    @Test
    fun scratchStaysWithinPlatterVelocityRange() = runTest {
        val deck = RecordingDeck()
        performRandomDrop(0, deck.actions, AllZones, beatMillis = 500L, random = Random(99))
        deck.drags.forEach { (_, v) ->
            assertTrue(isPlatterVelocitySane(v), "velocity $v is outside the platter's range")
        }
    }

    @Test
    fun scratchAlternatesDirection() = runTest {
        val deck = RecordingDeck()
        performRandomDrop(0, deck.actions, AllZones, beatMillis = 500L, random = Random(5))
        // Drop the leading grab (0f) and the trailing settle (0f); the rest must zig-zag.
        val moves = deck.drags.map { it.second }.filter { it != 0f }
        assertTrue(moves.size >= 4, "expected several scratch moves, got ${moves.size}")
        moves.zipWithNext { a, b ->
            assertTrue(a > 0f != b > 0f, "consecutive moves must reverse: $a then $b")
        }
    }

    @Test
    fun scratchDecaysTowardTheRelease() = runTest {
        val deck = RecordingDeck()
        performRandomDrop(0, deck.actions, AllZones, beatMillis = 500L, random = Random(11))
        val magnitudes = deck.drags.map { it.second }.filter { it != 0f }.map { if (it < 0) -it else it }
        assertTrue(
            magnitudes.last() < magnitudes.first(),
            "run should resolve: ended at ${magnitudes.last()}, started ${magnitudes.first()}",
        )
    }

    @Test
    fun weightedPickNeverReturnsNone() {
        repeat(200) { seed ->
            assertTrue(weightedDrop(AllZones, Random(seed)) != DjDrop.NONE)
        }
    }

    @Test
    fun weightedPickFavoursTheHigherWeightedDrops() {
        // BRAKE and FREEZE carry weight 1 against 3 for the rest, so they should be clearly
        // rarer. Asserting the direction rather than an exact ratio keeps this non-flaky.
        val counts = mutableMapOf<DjDrop, Int>()
        repeat(4_000) { seed ->
            val drop = weightedDrop(AllZones, Random(seed))
            counts[drop] = (counts[drop] ?: 0) + 1
        }
        val rare = (counts[DjDrop.BRAKE] ?: 0) + (counts[DjDrop.FREEZE] ?: 0)
        val common = (counts[DjDrop.FILTER] ?: 0) + (counts[DjDrop.STUTTER] ?: 0)
        assertTrue(common > rare, "weighted picks should favour common drops: $common vs $rare")
    }

    @Test
    fun emptyZoneOrderYieldsNoDrop() {
        assertEquals(DjDrop.NONE, weightedDrop(emptyList(), Random(0)))
        assertEquals(DjDrop.NONE, weightedDrop(listOf(DjDrop.NONE), Random(0)))
    }

    // --- Key routing -------------------------------------------------------------------
    // The deck used to consume up/down whenever it held focus, which left a remote unable to
    // leave the platter vertically: the panel below it was simply unreachable. Every other
    // D-pad control gates value changes behind select, so the deck does now too.

    @Test
    fun verticalPassesThroughUntilTheDeckIsGrabbed() {
        // The regression: unconsumed is what lets Compose move focus on to the next control.
        assertEquals(
            DeckAction.Pass,
            decideDeckKey(DeckKey.Down, adjusting = false, doubleVertical = false, doubleSelect = false),
            "down must fall through so focus can leave the platter",
        )
        assertEquals(
            DeckAction.Pass,
            decideDeckKey(DeckKey.Up, adjusting = false, doubleVertical = true, doubleSelect = false),
            "a double press outside adjust mode is still just focus movement",
        )
    }

    @Test
    fun verticalDrivesThePlatterOnceGrabbed() {
        val nudge = decideDeckKey(DeckKey.Up, adjusting = true, doubleVertical = false, doubleSelect = false)
        assertEquals(DeckAction.Spin(1f, NudgeVelocity), nudge, "one press nudges forward")

        val fling = decideDeckKey(DeckKey.Down, adjusting = true, doubleVertical = true, doubleSelect = false)
        assertEquals(DeckAction.Spin(-1f, FlingVelocity), fling, "two quick presses fling back")
    }

    @Test
    fun selectGrabsAndReleasesThePlatter() {
        assertEquals(
            DeckAction.EnterAdjust,
            decideDeckKey(DeckKey.Select, adjusting = false, doubleVertical = false, doubleSelect = false),
        )
        assertEquals(
            DeckAction.ExitAdjust,
            decideDeckKey(DeckKey.Select, adjusting = true, doubleVertical = false, doubleSelect = false),
            "a later press hands the platter back",
        )
    }

    @Test
    fun doubleSelectStillRunsTheDrop() {
        assertEquals(
            DeckAction.RandomDrop,
            decideDeckKey(DeckKey.Select, adjusting = true, doubleVertical = false, doubleSelect = true),
            "grab then a quick second press is the auto-scratch",
        )
    }

    @Test
    fun backLeavesAdjustModeButNeverTrapsTheRemote() {
        assertEquals(
            DeckAction.ExitAdjust,
            decideDeckKey(DeckKey.Back, adjusting = true, doubleVertical = false, doubleSelect = false),
        )
        assertEquals(
            DeckAction.Pass,
            decideDeckKey(DeckKey.Back, adjusting = false, doubleVertical = false, doubleSelect = false),
            "back must still exit the screen when the deck is not grabbed",
        )
    }

    @Test
    fun unrelatedKeysAreNeverConsumed() {
        assertEquals(
            DeckAction.Pass,
            decideDeckKey(DeckKey.Other, adjusting = true, doubleVertical = false, doubleSelect = false),
        )
    }
}
