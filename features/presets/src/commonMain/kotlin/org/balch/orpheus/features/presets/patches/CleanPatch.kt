package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset

/**
 * Clean Patch - Factory reset. All engine 0, plain sounds.
 * Good starting point for REPL and live coding.
 * All ports not specified here reset to plugin defaults via PortRegistry.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class CleanPatch : SynthPatch {
    override val id = "clean"
    override val name = "Clean"
    override val preset = SynthPreset(
        name = "Clean",
        portValues = buildMap {
            // Spread tunes across a comfortable range for direct playing
            val tunes = listOf(
                0.30f, 0.35f, 0.40f, 0.45f,
                0.50f, 0.55f, 0.60f, 0.65f,
                0.50f, 0.50f, 0.50f, 0.50f
            )
            tunes.forEachIndexed { i, v ->
                put("org.balch.orpheus.plugins.voice:tune_$i", PortValue.FloatValue(v))
            }

            // Fast envelopes (0 = fastest) for immediate feedback
            repeat(12) { i ->
                put("org.balch.orpheus.plugins.voice:env_speed_$i", PortValue.FloatValue(0.0f))
            }
        },
        createdAt = 0L
    )
}
