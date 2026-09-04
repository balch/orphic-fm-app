package org.balch.orpheus.features.pulsar

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.vibes.BlackCatVibe
import org.balch.orpheus.features.pulsar.vibes.FireSkyVibe
import org.balch.orpheus.features.pulsar.vibes.OdysseusLoreVibe
import org.balch.orpheus.features.pulsar.vibes.RustBeltVibe
import org.balch.orpheus.features.pulsar.vibes.VibeCatalogScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A static engine value that its gate never lets through is authoring that reads as intent
 * and does nothing. Swept over the whole catalog off the classpath, because a hand-maintained
 * list enforces nothing about the vibe someone adds tomorrow.
 *
 * Traced in `orpheus_unit_pulsar.cpp` / `orpheus_voice.h`:
 *  - `harmonics` at `:2231`, `:3740`, and the DX patch walk at `:3759` — all gated on
 *    `pin_harmonics`. DX/DX2/DX3 are FORCE-pinned at load, so their statics are live.
 *  - `timbre` at `:2234`, gated on `pin_timbre`. `morph` at `:2237`, gated on `pin_morph`.
 *  - `lpgDecay`/`lpgColour`: `kOrpheusLpgDefault[]` (orpheus_voice.h:118) is all LPG_BYPASS,
 *    so a track that does not set `lpgMode` skips the whole block at `:377`.
 *  - `harmonicsModulation` is only read once pinned harmonics resolves true.
 *
 * Works from constructed objects, not source text: `.copy()` inherits pin flags and engine
 * ids, so a text scan resolves the wrong thing. FireSky had a `harmonics = 0.031f` on a VA
 * engine carrying the same value as a live DX3 one two tracks away.
 *
 * To lock a tone value on an unpinned track, use a degenerate `MacroTarget(x, x)` — that
 * holds it while keeping the macro lerp, tension sweep, accent boost and mod-LFO alive.
 */
class DeadStaticToneAudit {

    private companion object {
        /** Total dead values across the catalog. Only ever lower this. */
        const val DEAD_STATIC_BASELINE = 1202
    }

    /** Vibes swept clean, held at zero so the deletions cannot creep back. */
    private val KNOWN_CLEAN = listOf(
        "BlackCat" to BlackCatVibe().vibe,
        "RustBelt" to RustBeltVibe().vibe,
        "FireSky" to FireSkyVibe().vibe,
        "OdysseusLore" to OdysseusLoreVibe().vibe,
    )

    // engineId is required but irrelevant here: these defaults are compile-time constants
    // on OrpheusEngine, not per-engine values.
    private val defaults = OrpheusEngine(engineId = OrpheusEngineId.VA)

    private fun deadStatics(vibe: Vibe): List<String> =
        vibe.tracks.flatMapIndexed { i, tv ->
            listOf("t$i.edm" to tv.engineEdm, "t$i.space" to tv.engineSpace)
        }.flatMap { (slot, e) ->
            // forcePinHarmonics is the production expression (PulsarViewModel reads the same
            // property); a local DX list would go stale on the next patch-selector engine.
            val harmonicsLive = e.pinHarmonics || e.engineId.forcePinHarmonics
            val lpgLive = e.lpgMode != LpgMode.ENGINE_DEFAULT && e.lpgMode != LpgMode.BYPASS
            listOf(
                Triple("harmonics", e.harmonics to defaults.harmonics, harmonicsLive),
                Triple("timbre", e.timbre to defaults.timbre, e.pinTimbre),
                Triple("morph", e.morph to defaults.morph, e.pinMorph),
                Triple("lpgDecay", e.lpgDecay to defaults.lpgDecay, lpgLive),
                Triple("lpgColour", e.lpgColour to defaults.lpgColour, lpgLive),
                Triple(
                    "harmonicsModulation",
                    e.harmonicsModulation to defaults.harmonicsModulation,
                    harmonicsLive,
                ),
            ).mapNotNull { (param, values, live) ->
                val (value, default) = values
                if (!live && value != default) "$slot ${e.engineId} $param = $value" else null
            }
        }

    @Test
    fun `the cleaned vibes do not regrow dead statics`() {
        KNOWN_CLEAN.forEach { (name, vibe) ->
            val dead = deadStatics(vibe)
            assertTrue(
                dead.isEmpty(),
                "$name writes ${dead.size} static engine value(s) that never reach the voice — " +
                    "pin them, set lpgMode, use a degenerate MacroTarget(x, x), or delete them:\n  " +
                    dead.joinToString("\n  "),
            )
        }
    }

    /**
     * Catalog-wide ratchet. Dead authoring is endemic (55 of 57 vibes at baseline), so this
     * cannot assert zero without a 1200-value sweep of the catalog nobody has ear-tested.
     * It asserts the count never *grows* instead, which is what stops a new vibe from adding
     * more. Lower [DEAD_STATIC_BASELINE] whenever a vibe gets cleaned.
     */
    @Test
    fun `catalog-wide dead static count does not grow`() {
        val perVibe = VibeCatalogScan.allProviders()
            .map { it.name to deadStatics(it.vibe) }
            .filter { it.second.isNotEmpty() }
        val total = perVibe.sumOf { it.second.size }

        assertTrue(
            total <= DEAD_STATIC_BASELINE,
            "dead static values rose from $DEAD_STATIC_BASELINE to $total across " +
                "${perVibe.size} vibes. A new vibe is authoring engine values its gate never " +
                "lets through. Worst offenders: " +
                perVibe.sortedByDescending { it.second.size }.take(5)
                    .joinToString { "${it.first}=${it.second.size}" },
        )
        if (total < DEAD_STATIC_BASELINE) {
            println("DeadStaticToneAudit: down to $total (baseline $DEAD_STATIC_BASELINE) — lower the baseline.")
        }
    }

    /**
     * The DX-family statics ARE live (force-pinned as a 32-step patch selector), and deleting
     * the dead ones around them must not have disturbed them. Asserted as a property of every
     * provider rather than golden lists, so a legitimate patch retune does not fail the build.
     */
    @Test
    fun `the live DX patch selectors survive the audit`() {
        val dxEngines = listOf<VibeProvider>(BlackCatVibe(), RustBeltVibe(), FireSkyVibe())
            .flatMap { it.vibe.tracks }
            .flatMap { listOf(it.engineEdm, it.engineSpace) }
            .filter { it.engineId.forcePinHarmonics }

        assertTrue(dxEngines.isNotEmpty(), "no DX-family engines found, so this guard is vacuous")
        assertEquals(
            emptyList(),
            dxEngines.filter { it.harmonics == defaults.harmonics }.map { it.engineId },
            "a DX patch selector fell back to the default harmonics, which selects patch 0 — " +
                "the deleted dead statics took a live one with them",
        )
    }
}
