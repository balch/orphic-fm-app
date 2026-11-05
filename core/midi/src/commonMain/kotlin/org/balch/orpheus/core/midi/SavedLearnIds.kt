package org.balch.orpheus.core.midi

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.symbols.BassSymbol
import org.balch.orpheus.core.plugin.symbols.BeatsSymbol
import org.balch.orpheus.core.plugin.symbols.DjSymbol
import org.balch.orpheus.core.plugin.symbols.DrumSymbol
import org.balch.orpheus.core.plugin.symbols.GrainsSymbol
import org.balch.orpheus.core.plugin.symbols.ResonatorSymbol
import org.balch.orpheus.core.plugin.symbols.WarpsSymbol

/** Ids that panels learned before they used port keys. Saved mappings still hold them. */
private val SAVED_LEARN_IDS: Map<String, PortSymbol> = mapOf(
    "bass_clock_division" to BassSymbol.CLOCK_DIV,
    "bass_step_count" to BassSymbol.STEP_COUNT,
    "bass_jitter" to BassSymbol.JITTER,
    "bass_accent_amount" to BassSymbol.ACCENT_AMOUNT,
    "bass_lfo_mix" to BassSymbol.LFO_MIX,
    "bass_mutation" to BassSymbol.MUTATION,
    "bass_cutoff" to BassSymbol.CUTOFF,
    "bass_resonance" to BassSymbol.RESONANCE,
    "bass_envelope" to BassSymbol.ENVELOPE,
    "bass_overdrive" to BassSymbol.OVERDRIVE,
    "bass_compressor" to BassSymbol.COMPRESSOR,
    "bass_fx_send" to BassSymbol.FX_SEND,
    "bass_mix" to BassSymbol.MIX,

    "resonator_mode" to ResonatorSymbol.MODE,
    "resonator_structure" to ResonatorSymbol.STRUCTURE,
    "resonator_brightness" to ResonatorSymbol.BRIGHTNESS,
    "resonator_damping" to ResonatorSymbol.DAMPING,
    "resonator_position" to ResonatorSymbol.POSITION,
    "resonator_mix" to ResonatorSymbol.MIX,
    "resonator_target_mix" to ResonatorSymbol.TARGET_MIX,
    "resonator_snap_back" to ResonatorSymbol.SNAP_BACK,

    "drums_bd_tune" to DrumSymbol.BD_FREQ,
    "drums_bd_tone" to DrumSymbol.BD_TONE,
    "drums_bd_decay" to DrumSymbol.BD_DECAY,
    "drums_bd_mod" to DrumSymbol.BD_P4,
    "drums_sd_tune" to DrumSymbol.SD_FREQ,
    "drums_sd_tone" to DrumSymbol.SD_TONE,
    "drums_sd_decay" to DrumSymbol.SD_DECAY,
    "drums_sd_mod" to DrumSymbol.SD_P4,
    "drums_hh_tune" to DrumSymbol.HH_FREQ,
    "drums_hh_tone" to DrumSymbol.HH_TONE,
    "drums_hh_decay" to DrumSymbol.HH_DECAY,
    "drums_hh_mod" to DrumSymbol.HH_P4,
    "drum_mix" to DrumSymbol.MIX,
    "drums_bd_trig" to DrumSymbol.BD_TRIGGER,
    "drums_sd_trig" to DrumSymbol.SD_TRIGGER,
    "drums_hh_trig" to DrumSymbol.HH_TRIGGER,

    "beats_run" to BeatsSymbol.RUN,
    "beats_mode" to BeatsSymbol.MODE,
    "beats_euclid_len_0" to BeatsSymbol.EUCLIDEAN_0,
    "beats_euclid_len_1" to BeatsSymbol.EUCLIDEAN_1,
    "beats_euclid_len_2" to BeatsSymbol.EUCLIDEAN_2,
    "beats_bd_density" to BeatsSymbol.DENSITY_0,
    "beats_sd_density" to BeatsSymbol.DENSITY_1,
    "beats_hh_density" to BeatsSymbol.DENSITY_2,
    "beats_randomness" to BeatsSymbol.RANDOMNESS,
    "beats_swing" to BeatsSymbol.SWING,
    "beats_bpm" to BeatsSymbol.BPM,
    "beats_mix" to DrumSymbol.MIX,

    "clouds_mode" to GrainsSymbol.MODE,
    "clouds_position" to GrainsSymbol.POSITION,
    "clouds_size" to GrainsSymbol.SIZE,
    "clouds_pitch" to GrainsSymbol.PITCH,
    "clouds_density" to GrainsSymbol.DENSITY,
    "clouds_texture" to GrainsSymbol.TEXTURE,
    "clouds_feedback" to GrainsSymbol.FEEDBACK,
    "clouds_reverb" to GrainsSymbol.REVERB,
    "clouds_mix" to GrainsSymbol.DRY_WET,
    "clouds_trigger" to GrainsSymbol.TRIGGER,
    "clouds_freeze" to GrainsSymbol.FREEZE,

    "warps_carrier_level" to WarpsSymbol.LEVEL1,
    "warps_modulator_level" to WarpsSymbol.LEVEL2,
    "warps_algo" to WarpsSymbol.ALGORITHM,
    "warps_timbre" to WarpsSymbol.TIMBRE,
    "warps_mix" to WarpsSymbol.MIX,

    "dj_delay_send" to DjSymbol.DELAY_SEND,
    "dj_reverb_send" to DjSymbol.REVERB_SEND,
)

private fun portKeyFor(controlId: String): String =
    SAVED_LEARN_IDS[controlId]?.controlId?.key ?: controlId

/** Points a saved mapping's old panel ids at the ports those panels learn now. */
internal fun MidiMappingState.withPortKeys(): MidiMappingState = copy(
    ccMappings = ccMappings.mapValues { (_, controlId) -> portKeyFor(controlId) },
    noteControlMappings = noteControlMappings.mapValues { (_, controlId) -> portKeyFor(controlId) },
)
