package org.balch.orpheus.djapp.vibeinfo

import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.math.floor

/**
 * SixOp FM (DX / DX2 / DX3) patch-bank names.
 *
 * Ported from the vibe-creator reference chart
 * (`.claude/skills/vibe-creator/references/fm_patches.md`), itself from MI's
 * `eurorack/plaits/resources/fm_patches.py`. The three engines share one 6-op FM voice
 * but each loads a different 32-patch bank (`voice.cc`: bank = engine.id - 2).
 *
 * On a SixOp engine the **`harmonics` value is a patch selector** (not a tone control): it is
 * quantized to one of 32 patches per bank by `stmlib::HysteresisQuantizer2`, initialised at
 * `six_op_engine.cc:76` as `Init(32, 0.005f, false)`. That final `false` (`symmetric`) sets
 * `offset_ = -0.5`, cancelling stmlib's usual `+ 0.5` rounding step, so the map **floors**:
 *
 *     patch = floor(harmonics * 1.02 * 32) = floor(harmonics * 32.64), clamped to [0, 31]
 *
 * Bucket N therefore spans `harmonics ∈ [N / 32.64, (N + 1) / 32.64)` and its centre — the value
 * to write when authoring — is `(N + 0.5) / 32.64`. Rounding instead of flooring names the patch
 * one index too high for roughly half of all inputs.
 *
 * The real quantizer is stateful: its 0.005f hysteresis nudges the result by one when a value sits
 * within 0.005/32.64 (~0.00015) of a bucket edge, depending on the previously loaded patch. This
 * lookup is stateless and reproduces the *from-below* branch — what a freshly loaded vibe gets —
 * by subtracting the hysteresis, which is why the `- 0.005f` below is not decoration. Without it
 * the lookup names the patch one too high for edge values just above a boundary.
 */
internal object FmPatchNames {

    // bank index = engineId.id - 2  →  DX(2)=0, DX2(3)=1, DX3(4)=2
    private val BANKS: Array<Array<String>> = arrayOf(
        // ── Bank 0 — DX (basses + analog synths) ──
        arrayOf(
            "Solid bass", "Mooger Low", "LeaderTape", "Morhol TB1", "Bass 3", "Bill bass",
            "Bass 1", "Elec Bass", "S.Bas 27.7", "Resonances", "Syn-bass 2", "Prc synth1",
            "Croma 2", "Analog 4", "Analog A", "Analog 6", "CS-80", "Insert 1", "Spiral",
            "Dx-Trott bass", "GasHaus", "Ring ding", "Papagayo", "Wineglass", "Amytal",
            "Fairlight", "PPG Vol 1", "PPG Vol 2", "Fairl. 3", "Vocoder 2", "Sequence", "Bounce 4",
        ),
        // ── Bank 1 — DX2 (keys, plucked, chroma percussion, drums) ──
        arrayOf(
            "E piano 1", "Fender 1", "WintrRhodes", "RS-EP C", "Mark III", "Clav E pno",
            "Syn Clav", "Clavinet", "Piano 5", "Grd Piano", "Steinway", "Guit acous", "Sitar",
            "Koto", "Harpsich", "Clav 3", "Xylophone", "Marimba", "Vibe 1", "Glockenspl", "Bell C",
            "Bells", "Tub Bells", "Gong 2", "Kettle", "Mid drum 3", "Ori Drum", "Wood 6",
            "Latin Drum", "Cimbal", "SYNDM 25.8", "B Drm-Snar",
        ),
        // ── Bank 2 — DX3 (organs, pipes, pads, strings, brass) ──
        arrayOf(
            "Click 124", "Hammond", "E organ 3", "60s organ", "Optic 28", "Pipes 1", "Pipes 3",
            "Pipes 2", "JX-33-P", "Soundtrack", "Ice pad 2", "M1 PADS", "CARLOS 2", "Soft touch",
            "Planets", "Cirrus", "ENTRIX", "Mal Poly", "Textures 6", "Etherial5a", "Airy",
            "Boron A", "Vangelis 1", "Strings C", "Strings 3", "Strings 2", "Strings 7",
            "Full strin", "Syn orch", "Brass 1", "Brass 6 BC", "Br trumpet",
        ),
    )

    /**
     * The name of patch [patchIndex] in [engineId]'s bank, or `null` when [engineId] is not a
     * DX-family engine or [patchIndex] is outside `[0, 31]`. Use when the index is already known;
     * [patchNameFor] when starting from a `harmonics` value.
     */
    fun patchNameAt(engineId: OrpheusEngineId, patchIndex: Int): String? {
        val bank = engineId.id - 2
        if (bank !in BANKS.indices || patchIndex !in 0..31) return null
        return BANKS[bank][patchIndex]
    }

    /**
     * The specific SixOp patch name for [engineId] at [harmonics], or `null` when [engineId] is
     * not a DX-family engine (caller should fall back to `engineId.displayName`).
     */
    fun patchNameFor(engineId: OrpheusEngineId, harmonics: Float): String? {
        val bank = engineId.id - 2
        if (bank !in BANKS.indices) return null
        // Mirrors the engine's own multiply order; the -0.005f is the from-below hysteresis.
        val idx = floor(harmonics * 1.02f * 32f - 0.005f).toInt().coerceIn(0, 31)
        return BANKS[bank][idx]
    }
}
