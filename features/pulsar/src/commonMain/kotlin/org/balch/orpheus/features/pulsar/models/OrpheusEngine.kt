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
     * FM depth is **[morph]** on OSC tracks, not a field of its own. Unpinned,
     * morph resolves from the SPACE macro through the track's `spaceDecay` range,
     * so the SPACE knob sweeps FM index live and the authored [morph] is inert.
     * Set [pinMorph] to make the authored value the depth. Accent never reaches
     * morph — the velocity boost applies to harmonics and timbre only.
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
    /**
     * Mist ([OrpheusEngineId.MST]), or any engine with [grainFx]; otherwise inert. On a Mist track
     * [harmonics] picks the wavetable bank, [timbre] the position in it, and [morph]
     * the grain length from a blip to four seconds. Each grain latches all of these
     * when it starts, so a sweep leaves a trail instead of moving the whole sound.
     */
    val grainMode: GrainMode = GrainMode.BURST,
    val grainScatterMode: GrainScatterMode = GrainScatterMode.CHORD,
    /** Which tone a scattered grain takes; ignored in [GrainScatterMode.RAW]. */
    val grainVoicing: GrainVoicing = GrainVoicing.EVEN,
    /** How far grains wander from the played note: 1 is two octaves either side. */
    val grainScatter: Float = 0f,
    /** Grain rate, up to 130 per second. See [GrainMode.CONTINUOUS] for its other meaning. */
    val grainRate: Float = 0.5f,
    /** Random grain spacing. Also widens the stereo spread. */
    val grainRandomTiming: Boolean = false,
    /** Grain envelope, from a click at 0 to a slow swell at 1. */
    val grainShape: Float = 0.5f,
    /** 0 is a steady wavetable oscillator (with [grainFx], the dry voice), 1 is all grains. */
    val grainBlend: Float = 1f,
    /**
     * Grain the track's own voice: its output is recorded and replayed as a Beads cloud, using the
     * grain* fields and [grainSize]. Inert on MST, which grains its wavetable.
     */
    val grainFx: Boolean = false,
    /**
     * Grain FX only: grain length on an exponential curve, from a blip at 0 through about 28 ms
     * at 0.25, 117 ms at 0.5 and 0.48 s at 0.75, to two seconds at 1.
     */
    val grainSize: Float = 0.5f,
    /** Peak pitch wobble in cents while a note holds. 0 is off. */
    val vibratoDepth: Float = 0f,
    /** Vibrato speed in Hz. Fades in over 0.3 s after each note-on. */
    val vibratoRate: Float = 5.5f,
    /** Cents each note climbs after its note-on, siren style, then holds. 0 is off. */
    val pitchRise: Float = 0f,
    /** Seconds the climb takes to reach the full [pitchRise]. */
    val pitchRiseTime: Float = 2f,
    /** Amount added to harmonics over each note, -1..1, then held. 0 is off. */
    val harmonicsRise: Float = 0f,
    /** Seconds the swell takes to reach the full [harmonicsRise]. */
    val harmonicsRiseTime: Float = 2f,
    /**
     * Amount added to morph over each note, -1..1, then held. 0 is off. On SPK morph picks the
     * vowel, so this sweeps it within each note; on VOWEL_FOF harmonics is the vowel and morph the
     * voice type. A note-on glides back over 80 ms. On OSC morph is the FM index, so a negative
     * rise is a pluck's FM decay, and there a note-on snaps back so each attack hits at full index.
     */
    val morphRise: Float = 0f,
    /** Seconds the sweep takes to reach the full [morphRise]. */
    val morphRiseTime: Float = 2f,
    /**
     * Renders the voice twice, this many cents apart (0..50). 0 is off. 8-15 thickens a lead or
     * singer, 20+ is a wide chorus. Costs a second engine on the track. Ignored on MST.
     */
    val doubleDetune: Float = 0f,
    /** How far apart the two doubled voices pan, 0..1. Only matters while [doubleDetune] is above 0. */
    val doubleSpread: Float = 0f,
) {
    init {
        require(fmRatio in 0f..16f) { "fmRatio must be 0..16, was $fmRatio" }
        require(fmShape in 0f..1f) { "fmShape must be 0..1, was $fmShape" }
        require(fmFreeHz in 0f..2000f) { "fmFreeHz must be 0..2000, was $fmFreeHz" }
        require(grainScatter in 0f..1f) { "grainScatter must be 0..1, was $grainScatter" }
        require(grainRate in 0f..1f) { "grainRate must be 0..1, was $grainRate" }
        require(grainShape in 0f..1f) { "grainShape must be 0..1, was $grainShape" }
        require(grainBlend in 0f..1f) { "grainBlend must be 0..1, was $grainBlend" }
        require(grainSize in 0f..1f) { "grainSize must be 0..1, was $grainSize" }
        require(vibratoDepth in 0f..100f) { "vibratoDepth must be 0..100 cents, was $vibratoDepth" }
        require(vibratoRate in 0.5f..12f) { "vibratoRate must be 0.5..12 Hz, was $vibratoRate" }
        require(pitchRise in 0f..1200f) { "pitchRise must be 0..1200 cents, was $pitchRise" }
        require(pitchRiseTime in 0.1f..8f) { "pitchRiseTime must be 0.1..8 s, was $pitchRiseTime" }
        require(harmonicsRise in -1f..1f) { "harmonicsRise must be -1..1, was $harmonicsRise" }
        require(harmonicsRiseTime in 0.1f..8f) { "harmonicsRiseTime must be 0.1..8 s, was $harmonicsRiseTime" }
        require(morphRise in -1f..1f) { "morphRise must be -1..1, was $morphRise" }
        require(morphRiseTime in 0.1f..8f) { "morphRiseTime must be 0.1..8 s, was $morphRiseTime" }
        require(doubleDetune in 0f..50f) { "doubleDetune must be 0..50 cents, was $doubleDetune" }
        require(doubleSpread in 0f..1f) { "doubleSpread must be 0..1, was $doubleSpread" }
    }
}
