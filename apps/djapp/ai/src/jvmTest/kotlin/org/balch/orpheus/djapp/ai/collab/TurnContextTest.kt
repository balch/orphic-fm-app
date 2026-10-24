package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import org.balch.orpheus.features.pulsar.bench.BenchMeters

class TurnContextTest {
    private val labels: (MixControl) -> String = { it.label { t -> BenchMeters.CHANNEL_NAMES[t] } }

    private fun move(origin: EditOrigin, control: MixControl, before: String, after: String, atMs: Long) =
        MixMove(origin, control, before, after, atMs)

    @Test
    fun formatsMovesOldestFirst() {
        val moves = listOf(
            move(EditOrigin.USER, MixControl.Send(5, reverb = true), "0.20", "0.40", atMs = 300),
            move(EditOrigin.USER, MixControl.Level(3), "-2.0 dB", "-5.0 dB", atMs = 100),
        )
        val text = turnContext(moves, mixerOpen = true, pinnedNames = listOf("BASS", "PAD"), labels = labels)
        assertEquals(
            """
            [Mixer: open. Pinned: BASS, PAD]
            [User moves since your last turn, oldest first]
            - BASS level: -2.0 dB -> -5.0 dB
            - PAD reverb: 0.20 -> 0.40
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun omitsMovesWhenNone() {
        assertEquals("[Mixer: closed. Pinned: none]", turnContext(emptyList(), mixerOpen = false, pinnedNames = emptyList(), labels = labels))
    }

    @Test
    fun marksClaudeCodeMoves() {
        val moves = listOf(
            move(EditOrigin.USER, MixControl.Mute(0), "off", "on", atMs = 1),
            move(EditOrigin.MCP, MixControl.Pan(4), "C", "L30", atMs = 2),
        )
        val lines = turnContext(moves, mixerOpen = true, pinnedNames = emptyList(), labels = labels).lines()
        assertEquals("- KICK mute: off -> on", lines[2])
        assertEquals("- (Claude Code) KEYS pan: C -> L30", lines[3])
    }
}
