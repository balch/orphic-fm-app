package org.balch.orpheus.features.presets

import kotlinx.coroutines.test.runTest
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.duoModSourceLegacyKey
import org.balch.orpheus.core.audio.patch.duoPatchKey
import org.balch.orpheus.core.audio.patch.legacyDuoPatch
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.presets.SynthPreset
import org.balch.orpheus.features.presets.patches.CleanPatch
import org.balch.orpheus.features.presets.patches.Funk491Patch
import org.balch.orpheus.features.presets.patches.OrpheusPatch
import org.balch.orpheus.features.presets.patches.PinkPatch
import org.balch.orpheus.features.presets.patches.SixSevenPatch
import org.balch.orpheus.features.presets.patches.SwirlyDreamsPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShippedPresetPatchTest {

    private suspend fun presets(): Map<String, SynthPreset> = mapOf(
        "pink" to PinkPatch().loadPreset(),
        "clean" to CleanPatch().loadPreset(),
        "six_seven" to SixSevenPatch().loadPreset(),
        "orpheus" to OrpheusPatch().loadPreset(),
        "funk491" to Funk491Patch().loadPreset(),
        "swirly_dreams" to SwirlyDreamsPatch().loadPreset(),
    )

    @Test
    fun everyShippedPresetHasSixPatchesAndNoLegacySource() = runTest {
        for ((id, preset) in presets()) {
            for (duo in 0 until 6) {
                val value = preset.portValues[duoPatchKey(duo)]
                assertNotNull(value as? PortValue.StringValue, "$id duo $duo has no patch string")
                assertNotNull(DuoPatch.decode(value.value), "$id duo $duo patch does not decode")
                assertNull(preset.portValues[duoModSourceLegacyKey(duo)], "$id duo $duo kept duo_mod_source")
            }
        }
    }

    @Test
    fun shippedPatchesMatchLegacyConversion() = runTest {
        for ((id, preset) in presets()) {
            val sources = LEGACY_SOURCES.getValue(id)
            for (duo in 0 until 6) {
                val engine = preset.portValues["org.balch.orpheus.plugins.voice:duo_engine_$duo"]
                    ?.asInt()?.let { OrpheusEngineId.fromId(it) }
                val onDx = engine?.forcePinHarmonics == true
                val expected = legacyDuoPatch(LegacyModSource.entries[sources[duo]], onDx, duo)
                val actual = DuoPatch.decode((preset.portValues[duoPatchKey(duo)] as PortValue.StringValue).value)
                assertEquals(expected, actual, "$id duo $duo")
            }
        }
    }

    private companion object {
        // Legacy duo_mod_source values copied from the JSON before the rewrite (LegacyModSource ordinals).
        val LEGACY_SOURCES = mapOf(
            "pink" to listOf(2, 1, 3, 2, 1, 1),
            "clean" to listOf(2, 2, 2, 1, 1, 1),
            "six_seven" to listOf(3, 0, 2, 1, 1, 1),
            "orpheus" to listOf(0, 2, 2, 1, 1, 1),
            "funk491" to listOf(4, 4, 2, 2, 2, 4),
            "swirly_dreams" to listOf(2, 2, 2, 2, 2, 2),
        )
    }
}
