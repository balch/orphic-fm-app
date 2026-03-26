#include "orpheus_graph.h"
#include "orpheus_units.h"
#include "orpheus_engine.h"
#include "orpheus_turntable.h"
#include <cstring>
#include <cmath>
#include <algorithm>

// Forward declaration -- called from orpheus_graph_load
static void orpheus_graph_sort(OrpheusGraph* graph);

// -- Little-endian read helpers ---------------------------
static uint16_t read_u16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}
static float read_f32(const uint8_t* p) {
    float f;
    std::memcpy(&f, p, 4);
    return f;
}

int orpheus_graph_load(OrpheusGraph* graph, const uint8_t* data,
                       size_t length, float sample_rate) {
    if (length < 12) return -1;

    // Header
    if (data[0] != 'O' || data[1] != 'D' || data[2] != 'W' || data[3] != 'G')
        return -2; // bad magic
    uint16_t version = read_u16(data + 4);
    if (version != 1) return -3;

    uint16_t unit_count = read_u16(data + 6);
    uint16_t conn_count = read_u16(data + 8);
    uint16_t total_params = read_u16(data + 10);
    (void)total_params;

    if (unit_count > kMaxUnits) return -4;
    if (conn_count > kMaxConnections) return -5;

    graph->unit_count = unit_count;
    graph->sample_rate = sample_rate;
    graph->master_out_index = -1;

    // Parse units
    size_t pos = 12;
    for (int i = 0; i < unit_count; i++) {
        if (pos + 6 > length) return -6;
        GraphUnit* u = &graph->units[i];
        u->type = read_u16(data + pos);
        u->id   = read_u16(data + pos + 2);
        uint16_t param_count = read_u16(data + pos + 4);
        pos += 6;

        unit_init(u, sample_rate);

        // Apply descriptor params
        for (int p = 0; p < param_count; p++) {
            if (pos + 6 > length) return -7;
            uint16_t key = read_u16(data + pos);
            float val = read_f32(data + pos + 2);
            pos += 6;

            switch (key) {
                case PARAM_FREQUENCY:
                    u->inputs[IPORT_FREQUENCY].constant = val;
                    u->inputs[IPORT_FREQUENCY].smoothed = val;
                    break;
                case PARAM_AMPLITUDE:
                    u->inputs[IPORT_AMPLITUDE].constant = val;
                    u->inputs[IPORT_AMPLITUDE].smoothed = val;
                    break;
                case PARAM_ATTACK:
                    u->state.env.attack_rate = 1.0f / (val * sample_rate);
                    break;
                case PARAM_DECAY:
                    u->state.env.decay_coeff =
                        std::exp(-1.0f / (val * sample_rate));
                    break;
                case PARAM_SUSTAIN:
                    u->state.env.sustain_level = val;
                    break;
                case PARAM_RELEASE:
                    u->state.env.release_coeff =
                        std::exp(-1.0f / (val * sample_rate));
                    break;
                case PARAM_TIME:
                    u->inputs[IPORT_TIME].constant = val;
                    u->inputs[IPORT_TIME].smoothed = val;
                    break;
                case PARAM_HALF_LIFE:
                    u->state.peak.decay_coeff =
                        std::exp(std::log(0.5f) / (val * sample_rate));
                    break;
                case PARAM_MAX_DELAY: {
                    int samples = static_cast<int>(val * sample_rate);
                    u->state.delay.buffer_size = samples;
                    u->state.delay.buffer = new float[samples]();
                    break;
                }
                case PARAM_DRIVE:
                    u->inputs[IPORT_DRIVE].constant = val;
                    u->inputs[IPORT_DRIVE].smoothed = val;
                    break;
                case PARAM_INPUT_A:
                    u->inputs[IPORT_INPUT_A].constant = val;
                    u->inputs[IPORT_INPUT_A].smoothed = val;
                    break;
                case PARAM_INPUT_B:
                    u->inputs[IPORT_INPUT_B].constant = val;
                    u->inputs[IPORT_INPUT_B].smoothed = val;
                    break;
                case PARAM_INPUT_C:
                    u->inputs[IPORT_INPUT_C].constant = val;
                    u->inputs[IPORT_INPUT_C].smoothed = val;
                    break;
                case PARAM_MODULE_INDEX:
                    u->state.module.index = static_cast<int>(val);
                    break;
                case PARAM_DUCK_SOURCE:
                    u->duck_source = static_cast<uint8_t>(val);
                    break;
            }
        }

        if (u->type == UNIT_MASTER_OUT)
            graph->master_out_index = i;
    }

    // Parse connections
    for (int c = 0; c < conn_count; c++) {
        if (pos + 8 > length) return -8;
        uint16_t src_id   = read_u16(data + pos);
        uint16_t src_port = read_u16(data + pos + 2);
        uint16_t dst_id   = read_u16(data + pos + 4);
        uint16_t dst_port = read_u16(data + pos + 6);
        pos += 8;

        if (src_id >= unit_count || dst_id >= unit_count) return -9;
        if (src_port >= OPORT_COUNT || dst_port >= IPORT_COUNT) return -10;

        GraphUnit* src = &graph->units[src_id];
        GraphUnit* dst = &graph->units[dst_id];
        GraphPort* inp = &dst->inputs[dst_port];

        if (inp->num_sources < 8) {
            inp->sources[inp->num_sources] = src->output_buffers[src_port];
            inp->num_sources++;
        }
    }

    // Parse port map
    if (pos + 2 <= length) {
        uint16_t map_count = read_u16(data + pos);
        pos += 2;
        graph->port_map_count = std::min(static_cast<int>(map_count),
                                         kMaxPortMapEntries);
        for (int m = 0; m < graph->port_map_count; m++) {
            if (pos + 8 > length) return -11;
            graph->port_map[m].uri_hash    = read_u16(data + pos);
            graph->port_map[m].symbol_hash = read_u16(data + pos + 2);
            graph->port_map[m].unit_id     = read_u16(data + pos + 4);
            graph->port_map[m].port        = read_u16(data + pos + 6);
            pos += 8;
        }
    }

    // Topological sort via Tarjan SCC
    orpheus_graph_sort(graph);

    return 0;
}

// -- Tarjan SCC + Topological Sort ------------------------

static int tarjan_disc[kMaxUnits];
static int tarjan_low[kMaxUnits];
static bool tarjan_on_stack[kMaxUnits];
static int tarjan_stack[kMaxUnits];
static int tarjan_stack_top;
static int tarjan_timer;
static int tarjan_scc_id[kMaxUnits];
static int tarjan_scc_count;

static int adj[kMaxUnits][8];
static int adj_count[kMaxUnits];

static void tarjan_dfs(int u) {
    tarjan_disc[u] = tarjan_low[u] = tarjan_timer++;
    tarjan_stack[tarjan_stack_top++] = u;
    tarjan_on_stack[u] = true;

    for (int j = 0; j < adj_count[u]; j++) {
        int v = adj[u][j];
        if (tarjan_disc[v] == -1) {
            tarjan_dfs(v);
            tarjan_low[u] = std::min(tarjan_low[u], tarjan_low[v]);
        } else if (tarjan_on_stack[v]) {
            tarjan_low[u] = std::min(tarjan_low[u], tarjan_disc[v]);
        }
    }

    if (tarjan_low[u] == tarjan_disc[u]) {
        int scc = tarjan_scc_count++;
        while (true) {
            int w = tarjan_stack[--tarjan_stack_top];
            tarjan_on_stack[w] = false;
            tarjan_scc_id[w] = scc;
            if (w == u) break;
        }
    }
}

static void orpheus_graph_sort(OrpheusGraph* graph) {
    int n = graph->unit_count;

    // Build adjacency from connections
    std::memset(adj_count, 0, sizeof(adj_count));
    for (int u = 0; u < n; u++) {
        for (int p = 0; p < kMaxInputPorts; p++) {
            GraphPort* inp = &graph->units[u].inputs[p];
            for (int s = 0; s < inp->num_sources; s++) {
                for (int src = 0; src < n; src++) {
                    for (int op = 0; op < kMaxOutputPorts; op++) {
                        if (graph->units[src].output_buffers[op] ==
                            inp->sources[s]) {
                            if (adj_count[src] < 8)
                                adj[src][adj_count[src]++] = u;
                            goto next_source;
                        }
                    }
                }
                next_source:;
            }
        }
    }

    // Tarjan SCC
    std::memset(tarjan_disc, -1, sizeof(tarjan_disc));
    tarjan_stack_top = 0;
    tarjan_timer = 0;
    tarjan_scc_count = 0;
    for (int i = 0; i < n; i++) {
        if (tarjan_disc[i] == -1)
            tarjan_dfs(i);
    }

    // Kahn's toposort on SCC DAG
    int scc_in_degree[kMaxUnits] = {};
    int scc_adj[kMaxUnits][8] = {};
    int scc_adj_count[kMaxUnits] = {};

    for (int u = 0; u < n; u++) {
        int su = tarjan_scc_id[u];
        for (int j = 0; j < adj_count[u]; j++) {
            int sv = tarjan_scc_id[adj[u][j]];
            if (su != sv) {
                bool dup = false;
                for (int k = 0; k < scc_adj_count[su]; k++) {
                    if (scc_adj[su][k] == sv) { dup = true; break; }
                }
                if (!dup && scc_adj_count[su] < 8) {
                    scc_adj[su][scc_adj_count[su]++] = sv;
                    scc_in_degree[sv]++;
                }
            }
        }
    }

    int queue[kMaxUnits];
    int q_head = 0, q_tail = 0;
    for (int s = 0; s < tarjan_scc_count; s++) {
        if (scc_in_degree[s] == 0)
            queue[q_tail++] = s;
    }

    int scc_order[kMaxUnits];
    int scc_order_count = 0;
    while (q_head < q_tail) {
        int s = queue[q_head++];
        scc_order[scc_order_count++] = s;
        for (int j = 0; j < scc_adj_count[s]; j++) {
            int dep = scc_adj[s][j];
            if (--scc_in_degree[dep] == 0)
                queue[q_tail++] = dep;
        }
    }

    // Expand SCCs into unit execution order
    graph->exec_count = 0;
    for (int si = 0; si < scc_order_count; si++) {
        int scc = scc_order[si];
        for (int u = 0; u < n; u++) {
            if (tarjan_scc_id[u] == scc)
                graph->exec_order[graph->exec_count++] = u;
        }
    }
}

// -- Dump execution order for debugging ----
void orpheus_graph_dump_exec_order(OrpheusGraph* graph) {
    static const char* type_names[] = {
        "TRI_OSC", "SQ_OSC", "MULTIPLY", "ADD", "MUL_ADD",
        "ENV", "LIN_RAMP", "PASS", "PEAK_FOLLOW", "HARD_CLIP",
        "LIMITER", "DELAY_LINE", "CLOUDS", "RINGS", "WARPS",
        "DUAL_DELAY", "REVERB", "LFO", "MASTER_OUT", "PLAITS",
        "CLOCK", "GRIDS", "MARBLES", "LOOPER", "BENDER",
        "PER_STR_BEND", "DUO_VOICE", "LORENZ", "POLY_LFO",
        "BASS_VOICE", "OVERDRIVE", "COMPRESSOR", "HORN", "TURNTABLE"
    };
    static_assert(sizeof(type_names) / sizeof(type_names[0]) == UNIT_TYPE_COUNT,
                  "type_names[] out of sync with UnitType enum");
    printf("  Graph execution order (%d units):\n", graph->exec_count);
    for (int ei = 0; ei < graph->exec_count; ei++) {
        int idx = graph->exec_order[ei];
        GraphUnit* u = &graph->units[idx];
        const char* name = (u->type >= 0 && u->type < UNIT_TYPE_COUNT)
            ? type_names[u->type] : "???";
        printf("    [%2d] unit %2d  %-14s  %s  (module.index=%d)\n",
               ei, idx, name,
               u->enabled ? "ON " : "off",
               u->state.module.index);
    }
}

// -- Graph process: run all units in topological order ----

void orpheus_graph_process(OrpheusGraph* graph, OrpheusEngine* engine,
                           float* output_buffer, int num_frames) {
    if (num_frames > kMaxFrames) num_frames = kMaxFrames;
    float sr = graph->sample_rate;

    // Double-buffer for Warps: copy PREVIOUS frame's completed source data
    // to read buffers BEFORE zeroing for the current frame's accumulation.
    // This ensures Warps always reads a full buffer regardless of execution order.
    std::memcpy(engine->warps_synth_read, engine->warps_source_buffers[0],
                num_frames * sizeof(float));
    std::memcpy(engine->warps_drums_read, engine->warps_source_buffers[1],
                num_frames * sizeof(float));
    // Viz: drum bus peak (from previous frame's completed buffer)
    {
        float peak = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            float a = std::fabs(engine->warps_drums_read[i]);
            if (a > peak) peak = a;
        }
        engine->viz_rings[VIZ_DRUM_OUT].write(peak);
    }
    std::memcpy(engine->warps_repl_read, engine->warps_source_buffers[2],
                num_frames * sizeof(float));
    std::memcpy(engine->warps_bass_read, engine->warps_source_buffers[9],
                num_frames * sizeof(float));

    // Now zero for this frame's voice accumulation
    std::memset(engine->warps_source_buffers[0], 0, num_frames * sizeof(float)); // SYNTH
    std::memset(engine->warps_source_buffers[1], 0, num_frames * sizeof(float)); // DRUMS
    std::memset(engine->warps_source_buffers[2], 0, num_frames * sizeof(float)); // REPL
    std::memset(engine->warps_source_buffers[9], 0, num_frames * sizeof(float)); // BASS

    // Smooth warps_mix here (before any voice runs) so warps_dry_scale()
    // reads a consistent value regardless of execution order.
    {
        float mix_target = engine->warps_bypass.load(std::memory_order_relaxed)
            ? 0.0f : engine->warps_mix.load(std::memory_order_relaxed);
        float sc = 1.0f - std::exp(-1.0f / (0.005f * sr));  // ~5ms ramp
        engine->warps_smooth_mix += sc * (mix_target - engine->warps_smooth_mix);
        if (engine->warps_smooth_mix < 0.0001f) engine->warps_smooth_mix = 0.0f;

        // Smooth bass FX send (~5ms) — read by delay, reverb, and clouds units
        float fx_target = engine->bass_fx_send.load(std::memory_order_relaxed);
        engine->bass_smooth_fx_send += sc * (fx_target - engine->bass_smooth_fx_send);
        if (engine->bass_smooth_fx_send < 0.0001f) engine->bass_smooth_fx_send = 0.0f;
    }

    // Turntable duck gains: attenuate dry source paths when DJ faders are up.
    // Each deck's wet level ducks its selected source.  Two decks on the same
    // source multiply: duck = (1 - wet_a_if_src) * (1 - wet_b_if_src).
    {
        float wa = engine->turntable_wet_a.load(std::memory_order_relaxed);
        float wb = engine->turntable_wet_b.load(std::memory_order_relaxed);
        int sa = engine->turntable_source_a.load(std::memory_order_relaxed);
        int sb = engine->turntable_source_b.load(std::memory_order_relaxed);
        float ds = 1.0f, dd = 1.0f, db = 1.0f;
        if (sa == TT_SOURCE_SYNTH)  ds *= (1.0f - wa);
        if (sa == TT_SOURCE_DRUMS)  dd *= (1.0f - wa);
        if (sa == TT_SOURCE_BASS)   db *= (1.0f - wa);
        if (sb == TT_SOURCE_SYNTH)  ds *= (1.0f - wb);
        if (sb == TT_SOURCE_DRUMS)  dd *= (1.0f - wb);
        if (sb == TT_SOURCE_BASS)   db *= (1.0f - wb);
        engine->turntable_duck_synth = ds;
        engine->turntable_duck_drums = dd;
        engine->turntable_duck_bass  = db;
    }

    for (int ei = 0; ei < graph->exec_count; ei++) {
        int idx = graph->exec_order[ei];
        GraphUnit* u = &graph->units[idx];
        if (!u->enabled) continue;

        // Prepare all input ports
        for (int p = 0; p < kMaxInputPorts; p++) {
            if (u->inputs[p].num_sources > 0 ||
                u->inputs[p].is_smoothed ||
                u->inputs[p].constant != 0.0f) {
                port_prepare(&u->inputs[p], num_frames, sr);
            }
        }

        // Dispatch to type-specific process
        switch (u->type) {
            case UNIT_TRIANGLE_OSC:
                unit_process_triangle_osc(u, num_frames, sr); break;
            case UNIT_SQUARE_OSC:
                unit_process_square_osc(u, num_frames, sr); break;
            case UNIT_MULTIPLY:
                unit_process_multiply(u, num_frames); break;
            case UNIT_ADD:
                unit_process_add(u, num_frames); break;
            case UNIT_MULTIPLY_ADD:
                unit_process_multiply_add(u, num_frames); break;
            case UNIT_ENVELOPE:
                unit_process_envelope(u, num_frames); break;
            case UNIT_LINEAR_RAMP:
                unit_process_linear_ramp(u, num_frames, sr); break;
            case UNIT_PASS_THROUGH:
                unit_process_pass_through(u, num_frames); break;
            case UNIT_PEAK_FOLLOWER:
                unit_process_peak_follower(u, num_frames); break;
            case UNIT_HARD_CLIP:
                unit_process_hard_clip(u, num_frames); break;
            case UNIT_LIMITER:
                unit_process_limiter(u, engine, num_frames, sr); break;
            case UNIT_DELAY_LINE:
                unit_process_delay_line(u, num_frames, sr); break;
            case UNIT_MASTER_OUT:
                unit_process_master_out(u, engine, output_buffer, num_frames, sr); break;
            case UNIT_PLAITS:
                unit_process_plaits(u, engine, num_frames, sr); break;
            case UNIT_CLOUDS:
                unit_process_clouds(u, engine, num_frames, sr); break;
            case UNIT_RINGS:
                unit_process_rings(u, engine, num_frames, sr); break;
            case UNIT_WARPS:
                unit_process_warps(u, engine, num_frames, sr); break;
            case UNIT_DUAL_DELAY:
                unit_process_dual_delay(u, engine, num_frames, sr); break;
            case UNIT_HYPER_LFO:
                unit_process_hyper_lfo(u, engine, num_frames, sr); break;
            case UNIT_REVERB:
                unit_process_reverb(u, engine, num_frames, sr); break;
            case UNIT_CLOCK:
                unit_process_clock(u, engine, num_frames, sr); break;
            case UNIT_GRIDS:
                unit_process_grids(u, engine, num_frames, sr); break;
            case UNIT_MARBLES:
                unit_process_marbles(u, engine, num_frames, sr); break;
            case UNIT_LOOPER:
                unit_process_looper(u, engine, num_frames, sr); break;
            case UNIT_BENDER:
                unit_process_bender(u, engine, num_frames, sr); break;
            case UNIT_PER_STRING_BENDER:
                unit_process_per_string_bender(u, engine, num_frames, sr); break;
            case UNIT_DUO_VOICE:
                unit_process_duo_voice(u, engine, num_frames, sr); break;
            case UNIT_LORENZ:
                unit_process_lorenz(u, engine, num_frames, sr); break;
            case UNIT_POLY_LFO:
                unit_process_poly_lfo(u, engine, num_frames, sr); break;
            case UNIT_BASS_VOICE:
                unit_process_bass_voice(u, engine, num_frames, sr); break;
            case UNIT_OVERDRIVE:
                unit_process_overdrive(u, engine, num_frames, sr); break;
            case UNIT_COMPRESSOR:
                unit_process_compressor(u, engine, num_frames, sr); break;
            case UNIT_HORN:
                unit_process_horn(u, engine, num_frames, sr); break;
            case UNIT_TURNTABLE:
                unit_process_turntable(u, engine, num_frames, sr); break;
            default: break;
        }

        // Turntable duck: attenuate this unit's output if tagged as a source
        if (u->duck_source != DUCK_NONE) {
            float duck = 1.0f;
            switch (u->duck_source) {
                case DUCK_SYNTH: duck = engine->turntable_duck_synth; break;
                case DUCK_DRUMS: duck = engine->turntable_duck_drums; break;
                case DUCK_BASS:  duck = engine->turntable_duck_bass;  break;
                default: break;
            }
            if (duck < 0.999f) {
                for (int p = 0; p < kMaxOutputPorts; p++) {
                    for (int i = 0; i < num_frames; i++)
                        u->output_buffers[p][i] *= duck;
                }
            }
        }
    }

    // ── LFO source mux: copy active modulation source into lfo_output_buffer ──
    // Voices always read from lfo_output_buffer regardless of which source generated it.
    int lfo_src = engine->lfo_source.load(std::memory_order_relaxed);
    if (lfo_src == 1) {
        // PolyLFO: ch0→timbre, ch1→morph, ch2→harmonics, ch3→pitch
        std::memcpy(engine->lfo_output_buffer, engine->poly_lfo_output[0],
                     num_frames * sizeof(float));
        std::memcpy(engine->lfo_morph_buffer, engine->poly_lfo_output[1],
                     num_frames * sizeof(float));
        std::memcpy(engine->lfo_harmonics_buffer, engine->poly_lfo_output[2],
                     num_frames * sizeof(float));
        std::memcpy(engine->lfo_pitch_buffer, engine->poly_lfo_output[3],
                     num_frames * sizeof(float));
        engine->lfo_output_value = engine->poly_lfo_value[0];
    } else if (lfo_src == 2) {
        // Lorenz: use blended X/Z output
        std::memcpy(engine->lfo_output_buffer, engine->lorenz_x_buffer,
                     num_frames * sizeof(float));
        // Remap from 0..1 to -1..+1 for bipolar modulation
        for (int i = 0; i < num_frames; i++) {
            engine->lfo_output_buffer[i] = engine->lfo_output_buffer[i] * 2.0f - 1.0f;
        }
        engine->lfo_output_value = engine->lfo_output_buffer[num_frames - 1];
        // Zero extra channels — Lorenz only uses ch0
        std::memset(engine->lfo_morph_buffer, 0, num_frames * sizeof(float));
        std::memset(engine->lfo_harmonics_buffer, 0, num_frames * sizeof(float));
        std::memset(engine->lfo_pitch_buffer, 0, num_frames * sizeof(float));
    } else {
        // DuoLFO: already wrote to lfo_output_buffer, zero extra channels
        std::memset(engine->lfo_morph_buffer, 0, num_frames * sizeof(float));
        std::memset(engine->lfo_harmonics_buffer, 0, num_frames * sizeof(float));
        std::memset(engine->lfo_pitch_buffer, 0, num_frames * sizeof(float));
    }

    // Apply feedback AM for PolyLFO/Lorenz (DuoLFO handles its own feedback FM internally).
    // Master peak × feedback amount scales the LFO output — louder sound = stronger modulation.
    if (lfo_src != 0) {
        float fb_target = engine->total_feedback.load(std::memory_order_relaxed);
        engine->smooth_total_feedback += (1.0f - std::exp(-1.0f / (0.005f * sr)))
            * (fb_target - engine->smooth_total_feedback);
        float fb = engine->smooth_total_feedback;
        if (fb > 0.001f) {
            float master_peak = std::max(
                engine->peak_left.load(std::memory_order_relaxed),
                engine->peak_right.load(std::memory_order_relaxed));
            float am = 1.0f + master_peak * fb * 3.0f;  // boost up to 4x when loud
            for (int i = 0; i < num_frames; i++) {
                engine->lfo_output_buffer[i] *= am;
            }
            engine->lfo_output_value *= am;
        }
    }

    // Skip attenuation when DuoLFO is OFF — output is zero, don't add offset
    bool lfo_off = (lfo_src == 0 && engine->lfo_mode.load(std::memory_order_relaxed) == 1);

    // Apply range min/max attenuation (shared across all sources)
    {
        float rng_min = engine->lfo_range_min.load(std::memory_order_relaxed);
        float rng_max = engine->lfo_range_max.load(std::memory_order_relaxed);
        if (!lfo_off && (rng_min != -1.0f || rng_max != 1.0f)) {
            float half_range = (rng_max - rng_min) * 0.5f;
            float center = (rng_max + rng_min) * 0.5f;
            for (int i = 0; i < num_frames; i++) {
                engine->lfo_output_buffer[i] =
                    engine->lfo_output_buffer[i] * half_range + center;
            }
            engine->lfo_output_value =
                engine->lfo_output_value * half_range + center;
        }
    }

    // Warps LFO source (3): copy final muxed+attenuated output
    std::memcpy(engine->warps_source_buffers[3], engine->lfo_output_buffer,
                num_frames * sizeof(float));

    // Viz: write final LFO output (after mux + range)
    engine->viz_rings[VIZ_LFO_OUTPUT].write(engine->lfo_output_value);
    // Viz: PolyLFO ch1-3 (write last sample; zero when not in DRIFT mode)
    engine->viz_rings[VIZ_LFO_CH1].write(engine->lfo_morph_buffer[num_frames - 1]);
    engine->viz_rings[VIZ_LFO_CH2].write(engine->lfo_harmonics_buffer[num_frames - 1]);
    engine->viz_rings[VIZ_LFO_CH3].write(engine->lfo_pitch_buffer[num_frames - 1]);
}

// -- Port routing via hash table --------------------------

static uint16_t hash16(const char* str) {
    uint16_t h = 0;
    while (*str) {
        h = h * 31 + static_cast<uint16_t>(*str);
        str++;
    }
    return h;
}

void orpheus_graph_set_port(OrpheusGraph* graph,
                            uint16_t uri_hash, uint16_t symbol_hash,
                            float value) {
    for (int i = 0; i < graph->port_map_count; i++) {
        auto& e = graph->port_map[i];
        if (e.uri_hash == uri_hash && e.symbol_hash == symbol_hash) {
            if (e.unit_id < graph->unit_count && e.port < kMaxInputPorts) {
                graph->units[e.unit_id].inputs[e.port].constant = value;
            }
        }
    }
}

void orpheus_graph_set_unit_port(OrpheusGraph* graph,
                                 uint16_t unit_id, uint16_t port,
                                 float value) {
    if (unit_id < graph->unit_count && port < kMaxInputPorts) {
        graph->units[unit_id].inputs[port].constant = value;
    }
}
