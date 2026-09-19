package org.balch.orpheus.features.pulsar.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArrangementPlayOnceTest {

    // A graph that wanders: intro can skip to the drop, the verse loops back, the outro is terminal.
    private val wandering = listOf(
        Section(
            name = "intro",
            transitions = listOf(
                SectionTransition(targetIndex = 2, weight = 1f, transitionBars = 2),
                SectionTransition(
                    targetIndex = 1, weight = 3f, transitionBars = 3,
                    effects = listOf(TapeStopEffect(ms = 650)),
                ),
            ),
        ),
        Section(
            name = "verse",
            transitions = listOf(
                SectionTransition(targetIndex = 0, weight = 1f, transitionBars = 2),
                SectionTransition(targetIndex = 3, weight = 1f, transitionBars = 4),
            ),
        ),
        Section(name = "drop", transitions = listOf(SectionTransition(targetIndex = 1, weight = 1f))),
        Section(name = "outro"),
    )

    private fun playOnce(sections: List<Section> = wandering, intro: Int? = 0, outro: Int? = 3) =
        Arrangement(sections = sections, introIndex = intro, outroIndex = outro, playOnce = true)

    @Test
    fun `a play-once pass walks each section once in list order and stops on the outro`() {
        val arr = playOnce()
        assertEquals(listOf(1 to 1f), arr.walkTransitions(0).map { it.targetIndex to it.weight })
        assertEquals(listOf(2 to 1f), arr.walkTransitions(1).map { it.targetIndex to it.weight })
        assertEquals(listOf(3 to 1f), arr.walkTransitions(2).map { it.targetIndex to it.weight })
        assertEquals(emptyList(), arr.walkTransitions(3), "the outro ends the pass")
    }

    @Test
    fun `a pass edge keeps an authored crossfade, else takes its section's longest`() {
        val arr = playOnce()
        assertEquals(3, arr.walkTransitions(0).single().transitionBars, "intro -> verse is authored at 3")
        assertEquals(4, arr.walkTransitions(1).single().transitionBars, "verse -> drop: verse authors 2 and 4")
        assertEquals(0, arr.walkTransitions(2).single().transitionBars, "drop -> outro: drop authors only a cut")
    }

    @Test
    fun `a pass edge that was authored keeps its effects, and a new one has none`() {
        val arr = playOnce()
        assertEquals(listOf(TapeStopEffect(ms = 650)), arr.walkTransitions(0).single().effects)
        assertEquals(emptyList(), arr.walkTransitions(1).single().effects)
    }

    @Test
    fun `the pass starts at the intro and wraps past the end of the list`() {
        // intro 2, outro 1: 2, 3, 4, 0, then the outro.
        val arr = playOnce(sections = List(5) { Section(name = "s$it") }, intro = 2, outro = 1)
        assertEquals(listOf(3), arr.walkTransitions(2).map { it.targetIndex })
        assertEquals(listOf(4), arr.walkTransitions(3).map { it.targetIndex })
        assertEquals(listOf(0), arr.walkTransitions(4).map { it.targetIndex })
        assertEquals(listOf(1), arr.walkTransitions(0).map { it.targetIndex })
        assertEquals(emptyList(), arr.walkTransitions(1))
        assertEquals(0, arr.walkTransitions(2).single().transitionBars, "a section with no edges cuts")
    }

    @Test
    fun `an arrangement that is not play-once walks its authored edges`() {
        val arr = Arrangement(sections = wandering, outroIndex = 3)
        assertEquals(wandering[0].transitions, arr.walkTransitions(0))
        assertEquals(wandering[1].transitions, arr.walkTransitions(1))
    }

    @Test
    fun `play-once needs an outro to end on`() {
        assertFailsWith<IllegalArgumentException> { playOnce(outro = null) }
    }

    @Test
    fun `play-once needs an intro to start from`() {
        assertFailsWith<IllegalArgumentException> { playOnce(intro = null) }
    }

    @Test
    fun `play-once rejects a pass flip that stages more effects than one flip holds`() {
        // Authored, a only reaches c (3 + 0 + 0 effects). The pass adds a -> b: 3 + 0 + 2 = 5 > 4.
        val sections = listOf(
            Section(
                name = "a",
                exitEffects = List(3) { TapeStopEffect() },
                transitions = listOf(SectionTransition(targetIndex = 2, weight = 1f)),
            ),
            Section(name = "b", entryEffects = List(2) { ScratchEffect() }),
            Section(name = "c"),
        )
        Arrangement(sections = sections, introIndex = 0, outroIndex = 2)
        assertFailsWith<IllegalArgumentException> { playOnce(sections = sections, intro = 0, outro = 2) }
    }
}
