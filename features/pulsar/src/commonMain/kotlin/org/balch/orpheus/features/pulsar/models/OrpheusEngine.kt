package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.OrpheusEngineId

@Serializable
data class OrpheusEngine(
    val engineId: OrpheusEngineId,
    val volume: Float = .8f,
    val harmonics: Float = 0.5f,
    val timbre: Float = 0.5f,
    val morph: Float = 0.5f,
    val modLfoRate: Float = 0.2f,
    val modLfoDepth: Float = 0.0f,
    val modLfoShape: Float = 0.3f,
    val modLfoCoupling: Float = 0.2f,
    val holdProbability: Float = 0.0f,
    val holdLengthMin: Int = 2,
    val holdLengthMax: Int = 8,
    val delaySend: Float = 0.0f,
    val reverbSend: Float = 0.0f,
    val noteRangeLow: Int = 0,
    val noteRangeHigh: Int = 0,
    val reverbBrightness: Float = 0.5f,
    val delayFeedback: Float? = null,
    val glideRate: Float = 0.0f,
    val lpgMode: LpgMode = LpgMode.ENGINE_DEFAULT,
    val lpgDecay: Float = 0.5f,
    val lpgColour: Float = 0.5f,
    /**
     * When true, [harmonics] is used verbatim at render time — bypasses the macro
     * map's `moodHarmonics` range, evolution drift, accent boost, and slow-LFO
     * modulation. Use to lock a specific tone color from vibe code (paste-from-Orpheus
     * workflow). Per-engine playability floor still applies.
     *
     * Forced to true at load time for DX/DX2/DX3 engines (their harmonics is a
     * 32-step quantized patch selector — interpolation is musically meaningless).
     */
    val pinHarmonics: Boolean = false,
    /**
     * When true, [timbre] is used verbatim at render time — see [pinHarmonics].
     * Unlike [pinHarmonics], this is never forced on by engine type.
     */
    val pinTimbre: Boolean = false,
    /**
     * When true, [morph] is used verbatim at render time — see [pinHarmonics].
     * Unlike [pinHarmonics], this is never forced on by engine type.
     */
    val pinMorph: Boolean = false,
    /**
     * Bounded LFO swing on harmonics **even when pinned**. Default `0.0f` =
     * fully pinned (no harmonic motion at all). Non-zero opens a controlled
     * walk around [harmonics] driven by the slow LFO at depth
     * `harmonicsModulation × modLfoDepth × texture_curve`.
     *
     * Use sparingly on DX-family pads where you want patch-walking back as a
     * texture-evolution effect — keep the value small (0.03–0.10) so the walk
     * stays within a neighborhood of the chosen patch instead of flickering
     * across the whole bank.
     *
     * Only active when [pinHarmonics] resolves true (either explicit opt-in
     * or engine-enforced via `OrpheusEngineId.forcePinHarmonics`). Ignored
     * on non-pinned tracks (their harmonics already gets LFO modulation
     * through the standard macroMap+apply_mod path).
     */
    val harmonicsModulation: Float = 0.0f,
    /**
     * Which live macro drives the user-knob-driven DX patch walk. See
     * [harmonicsMacroRange]. Default [MacroSource.MOOD] matches the pre-pin
     * behavior where the mood knob shifted DX patches as the user tweaked it.
     */
    val harmonicsMacroSource: MacroSource = MacroSource.MOOD,
    /**
     * User-knob-driven patch walk on **DX-family** auto-pinned harmonics.
     * Default `0.0f` = no walk (pinned base patch only). When non-zero, the
     * macro selected by [harmonicsMacroSource] (default `mood`) modulates
     * harmonics across `[base − range, base + range]`. The DX engine's
     * built-in patch quantizer turns the smooth macro sweep into discrete
     * patch steps — small knob tweaks → different voices, same rhythm.
     *
     * Effective **only on DX/DX2/DX3 engines** (where harmonics is a
     * quantized patch selector). Independent of [harmonicsModulation] —
     * both can be active; their offsets sum.
     *
     * Typical values: `0.03f` ≈ ±1 patch, `0.05f` ≈ ±2 patches,
     * `0.10f` ≈ ±3 patches. Above `0.20f` the walk crosses the whole
     * bank and stops feeling like the same voice family.
     *
     * At the default knob position (`mood = 0.5f`) the walk is zero — the
     * pinned base patch plays. This means default behavior of a vibe is
     * unaffected; the walk only kicks in when the user moves the knob.
     */
    val harmonicsMacroRange: Float = 0.0f,
    /**
     * Modulator frequency as a multiple of the carrier. `0f` = FM off.
     * Integer values (1, 2, 3) are harmonic; fractional (1.5, 2.7) go clangy.
     * Effective only on [OrpheusEngineId.OSC].
     *
     * FM depth is **[morph]** on OSC tracks, not a field of its own, so it
     * inherits section-energy evolution, tension, accent and [pinMorph].
     */
    val fmRatio: Float = 0f,
    /** Modulator waveform: 0 sine, 0.5 triangle, 1 square. Higher = brighter. */
    val fmShape: Float = 0f,
    /**
     * Non-zero overrides [fmRatio] with a fixed free-running rate in Hz,
     * reproducing the Orpheus panel's +-200Hz behavior for pasted presets.
     * Timbre then changes with every note, which is why ratio mode is default.
     */
    val fmFreeHz: Float = 0f,
) {
    init {
        require(fmRatio in 0f..16f) { "fmRatio must be 0..16, was $fmRatio" }
        require(fmShape in 0f..1f) { "fmShape must be 0..1, was $fmShape" }
        require(fmFreeHz in 0f..2000f) { "fmFreeHz must be 0..2000, was $fmFreeHz" }
    }
}
