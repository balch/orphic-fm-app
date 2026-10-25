package org.balch.orpheus.features.pulsar.bench

import org.balch.orpheus.features.pulsar.bench.LayerField.SectionDelay
import org.balch.orpheus.features.pulsar.bench.LayerField.SectionDelayFx
import org.balch.orpheus.features.pulsar.bench.LayerField.SectionLevel
import org.balch.orpheus.features.pulsar.bench.LayerField.SectionMacro
import org.balch.orpheus.features.pulsar.bench.LayerField.SectionReverb
import org.balch.orpheus.features.pulsar.bench.LayerField.VibeDelay
import org.balch.orpheus.features.pulsar.bench.LayerField.VibeDelayFx
import org.balch.orpheus.features.pulsar.bench.LayerField.VibeLevel
import org.balch.orpheus.features.pulsar.bench.LayerField.VibeMacro
import org.balch.orpheus.features.pulsar.bench.LayerField.VibePan
import org.balch.orpheus.features.pulsar.bench.LayerField.VibeReverb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayerFieldsTest {
    private val oneOfEach = listOf(
        VibeLevel(1), VibePan(2), VibeReverb(3), VibeDelay(4), VibeMacro(Macro.MOOD),
        SectionLevel(1, 3), SectionReverb(2, 4), SectionDelay(0, 5), SectionMacro(1, Macro.SPACE),
        VibeDelayFx(DelayFx.TIME_A), SectionDelayFx(2, DelayFx.FEEDBACK),
    )

    @Test
    fun withThenValueOfRoundTrips() {
        for (f in oneOfEach) {
            val set = BenchLayers().with(f, 0.3f)
            assertEquals(0.3f, set.valueOf(f), "$f")
            assertNull(set.with(f, null).valueOf(f), "$f cleared")
        }
    }

    @Test
    fun defaultsReadAsNull() {
        assertNull(BenchLayers().valueOf(VibeLevel(2)))
        assertNull(BenchLayers().valueOf(VibeReverb(2)))
    }

    @Test
    fun clearingTheLastSectionValueDropsTheSection() {
        val l = BenchLayers().with(SectionLevel(1, 3), -2f).with(SectionLevel(1, 3), null)
        assertTrue(l.sections.isEmpty())
    }

    @Test
    fun diffListsDelaySettings() {
        val after = BenchLayers().with(VibeDelayFx(DelayFx.TIME_B), 0.4f).with(SectionDelayFx(3, DelayFx.FEEDBACK), 0.7f)
        assertEquals(
            mapOf(
                VibeDelayFx(DelayFx.TIME_B) to LayerWrite(null, 0.4f),
                SectionDelayFx(3, DelayFx.FEEDBACK) to LayerWrite(null, 0.7f),
            ),
            BenchLayers().diff(after),
        )
    }

    @Test
    fun diffListsOnlyChangedFields() {
        val before = BenchLayers().with(VibeLevel(2), -2f)
        val after = BenchLayers().with(VibeLevel(2), -5f).with(SectionReverb(1, 4), 0.6f)
        assertEquals(
            mapOf(
                VibeLevel(2) to LayerWrite(-2f, -5f),
                SectionReverb(1, 4) to LayerWrite(null, 0.6f),
            ),
            before.diff(after),
        )
    }
}
