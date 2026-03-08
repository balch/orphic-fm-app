#pragma once
#include "orpheus_graph.h"

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
void unit_process_limiter(GraphUnit* u, int num_frames);
void unit_process_delay_line(GraphUnit* u, int num_frames, float sample_rate);
void unit_process_master_out(GraphUnit* u, OrpheusEngine* engine, float* output_buffer, int num_frames);

// MI module wrappers
void unit_process_plaits(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_clouds(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_rings(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_warps(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_dual_delay(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_hyper_lfo(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_reverb(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_clock(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_grids(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_marbles(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_looper(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);
void unit_process_per_string_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate);

// Initialize unit state from descriptor params
void unit_init(GraphUnit* u, float sample_rate);

// Prepare input port buffer (fill from sources or constant with smoothing)
void port_prepare(GraphPort* p, int num_frames, float sample_rate);
