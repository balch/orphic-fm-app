package org.balch.orpheus.tools.vibecodegen

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.TrackRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValueCodeBlockTest {

    @Test
    fun `null renders as the null literal`() {
        assertEquals("null", valueToCodeBlock(null).toString())
    }

    @Test
    fun `float renders with an f suffix`() {
        assertEquals("0.55f", valueToCodeBlock(0.55f).toString())
    }

    @Test
    fun `negative float renders with an f suffix`() {
        assertEquals("-1.0f", valueToCodeBlock(-1.0f).toString())
    }

    @Test
    fun `int and boolean render as raw literals`() {
        assertEquals("42", valueToCodeBlock(42).toString())
        assertEquals("true", valueToCodeBlock(true).toString())
    }

    @Test
    fun `string renders quoted`() {
        assertEquals("\"Lost in Space\"", valueToCodeBlock("Lost in Space").toString())
    }

    @Test
    fun `int range renders with the range operator`() {
        assertEquals("180..300", valueToCodeBlock(180..300).toString())
    }

    @Test
    fun `enum renders as a fully-qualified constant`() {
        assertEquals(
            "org.balch.orpheus.features.pulsar.models.ChordFollow.ROOT_ONLY",
            valueToCodeBlock(ChordFollow.ROOT_ONLY).toString(),
        )
    }

    @Test
    fun `empty list renders as emptyList`() {
        assertEquals("emptyList()", valueToCodeBlock(emptyList<Int>()).toString())
    }

    @Test
    fun `list renders as listOf with each element rendered`() {
        val rendered = valueToCodeBlock(listOf(1, 2, 3)).toString()
        assertTrue(rendered.startsWith("listOf("), rendered)
        assertTrue(rendered.contains("1,"), rendered)
        assertTrue(rendered.contains("2,"), rendered)
        assertTrue(rendered.contains("3,"), rendered)
    }

    @Test
    fun `empty map renders as emptyMap`() {
        assertEquals("emptyMap()", valueToCodeBlock(emptyMap<Int, Int>()).toString())
    }

    @Test
    fun `map renders as mapOf with to-pairs`() {
        val rendered = valueToCodeBlock(mapOf(1 to 2)).toString()
        assertTrue(rendered.startsWith("mapOf("), rendered)
        assertTrue(rendered.contains("1 to 2"), rendered)
    }

    @Test
    fun `data class renders as a named-arg constructor call`() {
        val rendered = valueToCodeBlock(MacroTarget(min = 0.2f, max = 0.8f)).toString()
        assertTrue(
            rendered.startsWith("org.balch.orpheus.features.pulsar.models.MacroTarget("),
            rendered,
        )
        assertTrue(rendered.contains("min = 0.2f"), rendered)
        assertTrue(rendered.contains("max = 0.8f"), rendered)
    }

    @Test
    fun `sealed object singleton renders as a bare type reference`() {
        assertEquals(
            "org.balch.orpheus.features.pulsar.models.LickMode.None",
            valueToCodeBlock(LickMode.None).toString(),
        )
    }

    @Test
    fun `sealed data class subtype renders with its own constructor args`() {
        val role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW, lickMode = LickMode.Fill)
        val rendered = valueToCodeBlock(role).toString()
        assertTrue(
            rendered.startsWith("org.balch.orpheus.features.pulsar.models.TrackRole.Melodic("),
            rendered,
        )
        assertTrue(
            rendered.contains("chordFollow = org.balch.orpheus.features.pulsar.models.ChordFollow.FOLLOW"),
            rendered,
        )
        assertTrue(
            rendered.contains("lickMode = org.balch.orpheus.features.pulsar.models.LickMode.Fill"),
            rendered,
        )
    }

    @Test
    fun `nested data class recurses into a full engine value`() {
        val engine = OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.6f)
        val rendered = valueToCodeBlock(engine).toString()
        assertTrue(
            rendered.contains("engineId = org.balch.orpheus.core.audio.OrpheusEngineId.BD"),
            rendered,
        )
        assertTrue(rendered.contains("volume = 0.6f"), rendered)
    }
}
