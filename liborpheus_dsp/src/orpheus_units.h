#pragma once
#include "orpheus_graph.h"

// Undefine Plaits resource macros that collide with Braids
// (some TUs load plaits/resources.h before braids/resources.h)
#undef LUT_FM_FREQUENCY_QUANTIZER
#undef LUT_FM_FREQUENCY_QUANTIZER_SIZE
#include "braids/macro_oscillator.h"

// Forward decl for engine-aware unit processors
struct OrpheusEngine;

// Per-type process functions. Each processes num_frames samples.
void unit_process_triangle_osc(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_square_osc(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_multiply(GraphUnit* u, int num_frames);
void unit_process_add(GraphUnit* u, int num_frames);
void unit_process_multiply_add(GraphUnit* u, int num_frames);
void unit_process_envelope(GraphUnit* u, int num_frames);
void unit_process_linear_ramp(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_pass_through(GraphUnit* u, int num_frames);
void unit_process_peak_follower(GraphUnit* u, int num_frames);
void unit_process_hard_clip(GraphUnit* u, int num_frames);
void unit_process_limiter(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_delay_line(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_master_out(GraphUnit* u, OrpheusEngine* engine, float* output_buffer, int num_frames, float sample_rate);

// MI module wrappers
// duo_mod_signal: pre-computed cross-mod signal for Plaits timbre mod in duo voice rendering.
// Negative (<0) = use internal mid-point sample (default for standalone rendering).
// Non-negative = use this value (RMS of partner's audio, supplied by unit_process_duo_voice).
void unit_process_plaits(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate,
                         float duo_mod_signal = -1.0f);
void unit_process_clouds(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_rings(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_warps(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_dual_delay(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_hyper_lfo(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_reverb(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_clock(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
#ifdef ORPHEUS_WITH_GRIDS
void unit_process_grids(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
#endif
void unit_process_marbles(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_looper(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_per_string_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_duo_voice(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_lorenz(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_poly_lfo(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_bass_voice(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_overdrive(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_compressor(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_horn(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_tides(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_pulsar(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_pulsar_delay(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr);
void unit_process_pulsar_reverb(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr);

// Render a Braids voice into out_buffer.
// `osc` is the per-voice/per-track MacroOscillator instance (caller owns it).
// `src_phase` is the per-voice fractional source-sample residue; the function
//   reads/updates it so the 96k→host resampler stays phase-continuous across
//   host blocks at non-48k host rates. Reset to 0 when `osc` is re-Init'd.
//   At exactly 48 kHz host rate the residue is unused (fast path is phase-locked).
// `engine_id` is the canonical OrpheusEngineId.id (must be in [100, 200)).
// `pitch_midi` is the MIDI note number.
// `harmonics`/`morph` are 0..1 (Braids parameter_[0]/[1]).
// `timbre` is currently ignored (Braids only has 2 macro params).
// `sample_rate` is the host output rate; the unit oversamples and resamples.
//   Audio quality degrades below ~24 kHz host (the staging buffer clamps).
void unit_process_braids(braids::MacroOscillator& osc,
                         float& src_phase,
                         int engine_id,
                         float pitch_midi,
                         float harmonics, float timbre, float morph,
                         float sample_rate,
                         float* out_buffer, int num_frames);

// Initialize unit state from descriptor params
void unit_init(GraphUnit* u, float sample_rate);

// Prepare input port buffer (fill from sources or constant with smoothing)
void port_prepare(GraphPort* p, int num_frames, float sample_rate);
