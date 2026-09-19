// Vibe speech: cue wire rows, cue timing math, the clip player, and the host's cue planning.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "../src/pulsar_speech.h"
#include <cmath>
#include <cstring>
#include <vector>

static bool test_cue_row_round_trip() {
    printf("\n=== Test: cue rows decode from the wire, all-zero rows are padding ===\n");
    const float wire[kSpeechCueRowFields] = {3.0f, 2.0f, -1.0f, 1.0f, 2.0f, 1.0f, 0.5f, 0.7f};
    const SpeechCueRow r = speech_cue_row_from_wire(wire);
    const float zero[kSpeechCueRowFields] = {};
    const SpeechCueRow pad = speech_cue_row_from_wire(zero);
    const bool ok = r.section == 3 && r.phrase == 1 && r.beat < 0.0f && r.align_end
        && r.every_loops == 2 && r.loop_phase == 1
        && std::fabs(r.chance - 0.5f) < 1e-6f && std::fabs(r.level - 0.7f) < 1e-6f
        && pad.phrase == -1 && pad.every_loops == 1;
    printf("  phrase=%d every=%d phase=%d pad.phrase=%d -- %s\n",
           r.phrase, r.every_loops, r.loop_phase, pad.phrase, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_cue_eligibility() {
    printf("\n=== Test: everyLoops/loopPhase pick cycles, padding never fires ===\n");
    SpeechCueRow even; even.phrase = 0; even.every_loops = 2; even.loop_phase = 0;
    SpeechCueRow odd = even; odd.loop_phase = 1;
    SpeechCueRow pad;   // phrase -1
    const bool ok = speech_cue_eligible(even, 0) && !speech_cue_eligible(even, 1)
        && speech_cue_eligible(even, 2) && !speech_cue_eligible(odd, 0)
        && speech_cue_eligible(odd, 1) && speech_cue_eligible(odd, 3)
        && !speech_cue_eligible(pad, 0);
    printf("  -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_start_frame_alignments() {
    printf("\n=== Test: start frames for end-aligned, start-aligned and too-long clips ===\n");
    const double S = pulsar_samples_per_step(80.0f);   // 9000 samples per step at 48 kHz
    SpeechCueRow loop_end; loop_end.phrase = 0;       // beat -1, align_end
    SpeechCueRow at_3 = loop_end; at_3.beat = 3.0f; at_3.align_end = false;
    SpeechCueRow end_at_8 = loop_end; end_at_8.beat = 8.0f;
    const double clip = 101280.0;                      // 2.11 s at 48 kHz
    const double a = speech_cue_start_frame(loop_end, 32, S, clip);      // 288000 - 101280
    const double b = speech_cue_start_frame(at_3, 32, S, clip);          // 3 * 4 * 9000
    const double c = speech_cue_start_frame(loop_end, 32, S, 400000.0);  // longer than the loop
    const bool ok = std::fabs(a - 186720.0) < 1e-6 && std::fabs(b - 108000.0) < 1e-6 && c == 0.0
        && speech_cue_ends_on_downbeat(loop_end, 32) && speech_cue_ends_on_downbeat(end_at_8, 32)
        && !speech_cue_ends_on_downbeat(at_3, 32);
    printf("  a=%.0f b=%.0f c=%.0f -- %s\n", a, b, c, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_player_places_clip_exactly() {
    printf("\n=== Test: a clip starts on its delay frame and ends on its last source frame ===\n");
    std::vector<float> clip(1000, 0.5f);
    speech::ClipPlayer p; p.Init(48000.0f);
    p.Schedule(clip.data(), 1000, 48000, 48000.0f, 1.0f, 700);
    std::vector<float> out(2048, 0.0f);
    p.Render(out.data(), 2048);
    bool silent_before = true;
    for (int i = 0; i < 700; i++) silent_before = silent_before && out[i] == 0.0f;
    bool full_middle = true;
    for (int i = 950; i < 1450; i++) full_middle = full_middle && std::fabs(out[i] - 0.5f) < 1e-3f;
    bool silent_after = true;
    for (int i = 1700; i < 2048; i++) silent_after = silent_after && out[i] == 0.0f;
    const bool ok = silent_before && out[701] > 0.0f && full_middle && silent_after && !p.active();
    printf("  before=%d middle=%d after=%d -- %s\n",
           (int)silent_before, (int)full_middle, (int)silent_after, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_player_resamples_source_rate() {
    printf("\n=== Test: a 24 kHz clip plays twice as long at 48 kHz ===\n");
    std::vector<float> clip(1000, 0.5f);
    speech::ClipPlayer p; p.Init(48000.0f);
    p.Schedule(clip.data(), 1000, 24000, 48000.0f, 1.0f, 0);
    std::vector<float> out(4096, 0.0f);
    p.Render(out.data(), 4096);
    bool silent_after = true;
    for (int i = 2000; i < 4096; i++) silent_after = silent_after && out[i] == 0.0f;
    const bool ok = std::fabs(out[1500] - 0.5f) < 1e-3f && silent_after;
    printf("  out[1500]=%.3f after=%d -- %s\n", out[1500], (int)silent_after, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_new_clip_fades_old_instead_of_cutting() {
    printf("\n=== Test: a phrase replacing a sounding one crossfades over 5 ms, no spike ===\n");
    std::vector<float> a(48000, 0.5f), b(48000, 0.25f);
    speech::ClipPlayer p; p.Init(48000.0f);
    p.Schedule(a.data(), 48000, 48000, 48000.0f, 1.0f, 0);
    std::vector<float> warm(1000, 0.0f);
    p.Render(warm.data(), 1000);
    p.Schedule(b.data(), 48000, 48000, 48000.0f, 1.0f, 100);
    std::vector<float> out(1000, 0.0f);
    p.Render(out.data(), 1000);
    float peak = 0.0f;
    for (float v : out) peak = std::max(peak, std::fabs(v));
    const bool ok = std::fabs(out[50] - 0.5f) < 1e-3f && std::fabs(out[600] - 0.25f) < 1e-3f
        && peak <= 0.5f + 1e-3f;
    printf("  out[50]=%.3f out[600]=%.3f peak=%.3f -- %s\n", out[50], out[600], peak, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_empty_clip_never_schedules() {
    printf("\n=== Test: a missing or 1-frame clip leaves the player idle ===\n");
    speech::ClipPlayer p; p.Init(48000.0f);
    const float one = 0.5f;
    p.Schedule(nullptr, 1000, 48000, 48000.0f, 1.0f, 0);
    p.Schedule(&one, 1, 48000, 48000.0f, 1.0f, 0);
    const bool ok = !p.active();
    printf("  -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_load_pulsar_clip_publishes_and_clears() {
    printf("\n=== Test: clip slots publish, clamp, clear, and ignore bad slots ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    std::vector<float> clip(1000);
    for (int i = 0; i < 1000; i++) clip[i] = static_cast<float>(i) / 1000.0f;

    const bool empty_at_start = engine->speech_clip_buffer == nullptr
        && engine->speech_clip_length[2].load(std::memory_order_acquire) == 0;
    orpheus_engine_load_pulsar_clip(engine, 2, clip.data(), 1000, 44100);
    const bool loaded = engine->speech_clip_buffer != nullptr
        && engine->speech_clip_length[2].load(std::memory_order_acquire) == 1000
        && engine->speech_clip_source_rate[2].load(std::memory_order_relaxed) == 44100
        && engine->speech_clip_buffer[2 * kMaxSpeechClipFrames + 10] == clip[10];

    orpheus_engine_load_pulsar_clip(engine, 4, clip.data(), 1000, 44100);    // out of range
    orpheus_engine_load_pulsar_clip(engine, -1, clip.data(), 1000, 44100);   // out of range
    const bool others_untouched = engine->speech_clip_length[0].load(std::memory_order_acquire) == 0
        && engine->speech_clip_length[3].load(std::memory_order_acquire) == 0;

    std::vector<float> longer(kMaxSpeechClipFrames + 5, 0.25f);
    orpheus_engine_load_pulsar_clip(engine, 1, longer.data(), static_cast<int>(longer.size()), 48000);
    const bool clamped = engine->speech_clip_length[1].load(std::memory_order_acquire) == kMaxSpeechClipFrames;

    orpheus_engine_load_pulsar_clip(engine, 2, nullptr, 0, 0);
    const bool cleared = engine->speech_clip_length[2].load(std::memory_order_acquire) == 0;

    const bool ok = empty_at_start && loaded && others_untouched && clamped && cleared;
    printf("  empty=%d loaded=%d untouched=%d clamped=%d cleared=%d -- %s\n",
           (int)empty_at_start, (int)loaded, (int)others_untouched, (int)clamped, (int)cleared,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Mirrors test_trans_fx_bank_routing in test_pulsar_transition_fx.cpp.
static bool test_speech_cue_bank_routing() {
    printf("\n=== Test: speech_cue_data_$i routes to the engine bank and bounds-checks ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const char* uri = "org.balch.orpheus.plugins.pulsar";
    orpheus_engine_set_port(engine, uri, "speech_cue_data_0", 2.0f);
    orpheus_engine_set_port(engine, uri, "speech_cue_data_191", 0.7f);
    orpheus_engine_set_port(engine, uri, "speech_cue_data_192", 9.0f);   // out of range, dropped
    const float first = engine->pulsar_speech_cue_data[0].load(std::memory_order_relaxed);
    const float last = engine->pulsar_speech_cue_data[kSpeechCueBankSize - 1].load(std::memory_order_relaxed);
    const bool ok = first == 2.0f && std::fabs(last - 0.7f) < 1e-6f && kSpeechCueBankSize == 192;
    printf("  first=%.2f last=%.2f -- %s\n", first, last, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Playing, unity mix, every track silent, a one-section arrangement (so every loop-cycle
// is cycle 0), both RNGs pinned. 120 BPM: 6000 samples per step.
static OrpheusEngine* make_speech_engine(int step_count) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    solo_track(engine, -1);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(step_count, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kSectionDataFields;
        for (int f = 0; f < kSectionDataFields; f++) {
            float v = 0.0f;
            if (f >= 5 && f <= 8) v = -1.0f;
            if (f >= 18 && f <= 20) v = -1.0f;
            engine->pulsar_section_data[b + f].store(v, std::memory_order_relaxed);
        }
    }
    engine->pulsar_section_data[0].store(4.0f, std::memory_order_relaxed);   // bars_min
    engine->pulsar_section_data[1].store(4.0f, std::memory_order_relaxed);   // bars_max
    engine->pulsar_section_data[2].store(1.0f, std::memory_order_relaxed);   // bar_step
    engine->pulsar_section_data[3].store(1.0f, std::memory_order_relaxed);   // recency_decay
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
    return engine;
}

static void push_cue_row(OrpheusEngine* engine, int row, const float fields[kSpeechCueRowFields]) {
    for (int i = 0; i < kSpeechCueRowFields; i++)
        engine->pulsar_speech_cue_data[row * kSpeechCueRowFields + i].store(fields[i], std::memory_order_relaxed);
}

static std::vector<float> sine_clip(int frames, float hz, float sample_rate = 48000.0f) {
    std::vector<float> c(frames);
    for (int i = 0; i < frames; i++) c[i] = 0.5f * std::sin(2.0f * 3.14159265f * hz * i / sample_rate);
    return c;
}

// Renders `blocks` x 512 frames and returns pulsar_out_l concatenated.
static std::vector<float> render_out(OrpheusEngine* engine, GraphUnit* unit, int blocks) {
    std::vector<float> out;
    out.reserve(static_cast<size_t>(blocks) * 512);
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(unit, engine, 512, 48000.0f);
        out.insert(out.end(), engine->pulsar_out_l, engine->pulsar_out_l + 512);
    }
    return out;
}

static bool test_end_aligned_phrase_ends_on_the_next_downbeat() {
    printf("\n=== Test: an end-aligned loop-end phrase finishes on the next downbeat ===\n");
    OrpheusEngine* engine = make_speech_engine(16);            // cycle = 16 * 6000 = 96000 frames
    // Loaded at HALF the engine rate: 6000 source frames is still 0.25s, i.e. 12000 engine
    // frames, so a dropped or inverted rate ratio in plan_speech_cues would move the window.
    const std::vector<float> clip = sine_clip(6000, 1000.0f, 24000.0f);
    orpheus_engine_load_pulsar_clip(engine, 0, clip.data(), 6000, 24000);
    const float row[kSpeechCueRowFields] = {0.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f};
    push_cue_row(engine, 0, row);
    trigger_vibe_load(engine);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    const std::vector<float> out = render_out(engine, &unit, 200);   // 102400 frames
    float peak = 0.0f;
    for (int i = 0; i < 100000; i++) peak = std::max(peak, std::fabs(out[i]));
    const float thr = 0.2f * peak;
    int onset = -1, offset = -1;
    for (int i = 0; i < 100000; i++) {
        if (std::fabs(out[i]) > thr) { if (onset < 0) onset = i; offset = i; }
    }
    // Expected start 96000 - 12000 = 84000; fades and the output high-pass shift the
    // threshold crossings by well under 2.5 ms.
    const bool ok = peak > 0.0f && onset >= 84000 && onset <= 84120 && offset >= 95800 && offset <= 96000;
    printf("  peak=%.3f onset=%d offset=%d -- %s\n", peak, onset, offset, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Same fixture as make_speech_engine, but pins energy=1 to disable elastic-tempo
// drift (test_pulsar_timing.cpp, make_muted_street_engine). Above, energy is left at
// its 0.5 default, so drift is live: 4900 blocks hold 26 nominal cycles but only 25
// wraps, and checking a phrase's end against the NOMINAL downbeat only proves it
// ended where it was planned, not where the loop actually wrapped.
static OrpheusEngine* make_speech_engine_no_drift(int step_count) {
    OrpheusEngine* engine = make_speech_engine(step_count);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    return engine;
}

// Same loop-end cue as test_end_aligned_phrase_ends_on_the_next_downbeat, on a
// no-drift engine.
static OrpheusEngine* build_downbeat_probe_engine(GraphUnit& unit) {
    OrpheusEngine* engine = make_speech_engine_no_drift(16);   // cycle = 96000 frames
    const std::vector<float> clip = sine_clip(6000, 1000.0f, 24000.0f);
    orpheus_engine_load_pulsar_clip(engine, 0, clip.data(), 6000, 24000);
    const float row[kSpeechCueRowFields] = {0.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f};
    push_cue_row(engine, 0, row);
    trigger_vibe_load(engine);
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    return engine;
}

static bool test_end_aligned_phrase_ends_within_a_frame_of_the_measured_downbeat() {
    printf("\n=== Test: an end-aligned phrase ends within a frame of the MEASURED downbeat ===\n");

    // Pass 1: coarse 512-frame render, both for the phrase's audio (same 0.2*peak
    // threshold as the test above) and to find which 512-frame block wraps the loop
    // (track 0's playhead returning to step 0 -- PulsarState::loop_count).
    GraphUnit unit1;
    OrpheusEngine* engine1 = build_downbeat_probe_engine(unit1);
    std::vector<float> out;
    out.reserve(102400);
    int wrap_block = -1;
    int prev_loop = 0;
    for (int b = 0; b < 200 && wrap_block < 0; b++) {
        unit_process_pulsar(&unit1, engine1, 512, 48000.0f);
        out.insert(out.end(), engine1->pulsar_out_l, engine1->pulsar_out_l + 512);
        PulsarState* st = engine1->pulsar_state;
        if (st && st->loop_count > prev_loop) { wrap_block = b; prev_loop = st->loop_count; }
    }
    orpheus_engine_destroy(engine1);

    float peak = 0.0f;
    const size_t scan = std::min<size_t>(out.size(), 100000);
    for (size_t i = 0; i < scan; i++) peak = std::max(peak, std::fabs(out[i]));
    const float thr = 0.2f * peak;
    int offset = -1;
    for (size_t i = 0; i < scan; i++)
        if (std::fabs(out[i]) > thr) offset = static_cast<int>(i);

    // Pass 2: a fresh, identically configured engine, replayed in the same 512-frame
    // blocks up to (not including) the wrap block, then switched to 1-frame steps
    // inside it. Per-sample state carries across calls regardless of chunk size, so
    // this measures the exact wrap frame instead of assuming it.
    GraphUnit unit2;
    OrpheusEngine* engine2 = build_downbeat_probe_engine(unit2);
    for (int b = 0; b < wrap_block; b++) unit_process_pulsar(&unit2, engine2, 512, 48000.0f);
    const int base_loop = engine2->pulsar_state ? engine2->pulsar_state->loop_count : 0;
    long actual_downbeat = -1;
    for (int f = 0; wrap_block >= 0 && f < 512; f++) {
        unit_process_pulsar(&unit2, engine2, 1, 48000.0f);
        PulsarState* st = engine2->pulsar_state;
        if (st && st->loop_count > base_loop) {
            actual_downbeat = static_cast<long>(wrap_block) * 512 + f;
            break;
        }
    }
    orpheus_engine_destroy(engine2);

    // The test above already established that the clip's 5 ms fades and the output
    // high-pass shift its threshold crossings by well under 2.5 ms (120 frames); use
    // the same margin here, now anchored to the MEASURED downbeat rather than a
    // hardcoded nominal one.
    const bool ok = wrap_block >= 0 && actual_downbeat >= 0 && peak > 0.0f
        && std::abs(static_cast<int>(actual_downbeat) - offset) <= 200;
    printf("  offset=%d actual_downbeat=%ld wrap_block=%d -- %s\n",
           offset, actual_downbeat, wrap_block, ok ? "PASS" : "FAIL");
    return ok;
}

// speech_kicks_forced now increments only when the step truly fires, so this counts real
// fires, not flag consumptions. Track 0 fires naturally ~94.6% of the time here, so 3 wraps
// (the old window) can't tell forced from lucky; 25 wraps hits two pinned-RNG natural misses.
static bool test_loop_end_phrase_forces_the_kick() {
    printf("\n=== Test: end-aligned loop-end phrases force track 0's downbeat; start-aligned do not ===\n");

    // Control: guards this test's premise. No cue pushed, so speech_kick_now never fires;
    // track 0's downbeat runs on step_hash's deterministic roll alone (pulsar_rng.h, not a
    // carried RNG stream) -- the same sequence the cued runs below see.
    // The step rolls follow the seed, and about one seed in four never misses inside 25
    // wraps. This one does; re-pick it if the control below reports zero misses.
    constexpr uint32_t kKickSeed = 0xBEAF;
    int control_misses = 0;
    {
        OrpheusEngine* control = make_speech_engine(16);
        pin_pulsar_rngs(control, kKickSeed);
        trigger_vibe_load(control);
        GraphUnit control_unit; std::memset(&control_unit, 0, sizeof(control_unit));
        control_unit.type = UNIT_PULSAR; control_unit.enabled = true;
        int wraps_seen = 0, prev_loop_count = 0;
        while (wraps_seen < 25) {
            unit_process_pulsar(&control_unit, control, 512, 48000.0f);
            PulsarState* cs = control->pulsar_state;
            if (cs && cs->loop_count > prev_loop_count) {
                prev_loop_count = cs->loop_count;
                wraps_seen++;
                if (!cs->tracks[0].prev_step_gated) control_misses++;
            }
        }
        orpheus_engine_destroy(control);
    }

    // Tempo drift is seed-dependent, so a fixed render holds 25 or 26 wraps. Compare the
    // forced count to the wraps this run actually saw, not to a nominal 25.
    int forced[2] = {-1, -1}, wraps[2] = {-1, -1};
    for (int c = 0; c < 2; c++) {
        OrpheusEngine* engine = make_speech_engine(16);
        const std::vector<float> clip = sine_clip(12000, 1000.0f);
        orpheus_engine_load_pulsar_clip(engine, 0, clip.data(), 12000, 48000);
        const float align_end = (c == 0) ? 1.0f : 0.0f;
        const float row[kSpeechCueRowFields] = {0.0f, 1.0f, -1.0f, align_end, 1.0f, 0.0f, 1.0f, 1.0f};
        push_cue_row(engine, 0, row);
        pin_pulsar_rngs(engine, kKickSeed);
        trigger_vibe_load(engine);
        GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR; unit.enabled = true;
        render_out(engine, &unit, 4900);                          // just past the 25th wrap
        forced[c] = engine->pulsar_state ? engine->pulsar_state->speech_kicks_forced : -1;
        wraps[c] = engine->pulsar_state ? engine->pulsar_state->loop_count : -1;
        orpheus_engine_destroy(engine);
    }
    const bool ok = control_misses >= 1 && wraps[0] >= 25 && forced[0] == wraps[0] && forced[1] == 0;
    printf("  control misses=%d/25 end-aligned forced=%d of %d wraps start-aligned forced=%d -- %s\n",
           control_misses, forced[0], wraps[0], forced[1], ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_cue_without_a_clip_is_silent() {
    printf("\n=== Test: a cue whose slot was never loaded plays nothing and forces nothing ===\n");
    OrpheusEngine* engine = make_speech_engine(16);
    // Load a different slot first, so speech_clip_buffer is allocated and non-null; the
    // real case after the Kotlin side clears a slot is a live buffer with length 0, not
    // a missing block.
    const std::vector<float> other = sine_clip(1000, 500.0f);
    orpheus_engine_load_pulsar_clip(engine, 1, other.data(), 1000, 48000);
    const float row[kSpeechCueRowFields] = {0.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f};  // phrase 0, unloaded
    push_cue_row(engine, 0, row);
    trigger_vibe_load(engine);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    const std::vector<float> out = render_out(engine, &unit, 200);
    float peak = 0.0f;
    for (float v : out) peak = std::max(peak, std::fabs(v));
    const int forced = engine->pulsar_state ? engine->pulsar_state->speech_kicks_forced : -1;
    const bool ok = peak < 1e-6f && forced == 0;
    printf("  peak=%.2e forced=%d -- %s\n", peak, forced, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Fix 1 regression: two eligible cues planned in the same cycle must both play, not just
// the last one Scheduled. A start-aligned cue at beat 1 and an end-aligned loop-end cue
// are both planned at the vibe-load boundary; both must sound, and the kick forces once.
static bool test_two_cues_in_one_cycle_both_play_and_kick_forces_once() {
    printf("\n=== Test: two cues planned in the same cycle both play; the kick forces once ===\n");
    OrpheusEngine* engine = make_speech_engine(16);              // cycle = 96000 frames
    const std::vector<float> clip0 = sine_clip(6000, 800.0f);    // start-aligned at beat 1
    const std::vector<float> clip1 = sine_clip(12000, 1000.0f);  // end-aligned loop-end
    orpheus_engine_load_pulsar_clip(engine, 0, clip0.data(), 6000, 48000);
    orpheus_engine_load_pulsar_clip(engine, 1, clip1.data(), 12000, 48000);
    const float row0[kSpeechCueRowFields] = {0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f};   // phrase 0, beat 1
    const float row1[kSpeechCueRowFields] = {0.0f, 2.0f, -1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f};  // phrase 1, loop end
    push_cue_row(engine, 0, row0);
    push_cue_row(engine, 1, row1);
    trigger_vibe_load(engine);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    const std::vector<float> out = render_out(engine, &unit, 200);   // 102400 frames, past the first wrap
    // clip0: expected window ~[24000, 30000). clip1: expected window ~[84000, 96000).
    float peak0 = 0.0f;
    for (int i = 23000; i < 31000; i++) peak0 = std::max(peak0, std::fabs(out[i]));
    float peak1 = 0.0f;
    for (int i = 83000; i < 96000; i++) peak1 = std::max(peak1, std::fabs(out[i]));
    const int forced = engine->pulsar_state ? engine->pulsar_state->speech_kicks_forced : -1;
    const bool ok = peak0 > 0.05f && peak1 > 0.05f && forced == 1;
    printf("  peak0=%.3f peak1=%.3f forced=%d -- %s\n", peak0, peak1, forced, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_reload_during_playback_is_safe() {
    printf("\n=== Test: reloading a slot while its phrase plays stays finite and bounded ===\n");
    OrpheusEngine* engine = make_speech_engine(16);
    const std::vector<float> clip = sine_clip(24000, 700.0f);
    orpheus_engine_load_pulsar_clip(engine, 0, clip.data(), 24000, 48000);
    const float row[kSpeechCueRowFields] = {0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f};  // starts on the downbeat
    push_cue_row(engine, 0, row);
    trigger_vibe_load(engine);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    render_out(engine, &unit, 10);                               // phrase is sounding
    const std::vector<float> shorter = sine_clip(6000, 300.0f);
    orpheus_engine_load_pulsar_clip(engine, 0, shorter.data(), 6000, 48000);
    const std::vector<float> out = render_out(engine, &unit, 40);
    bool ok = true;
    for (float v : out) ok = ok && std::isfinite(v) && std::fabs(v) < 10.0f;
    printf("  -- %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_speech_tests() {
    printf("\n=== Pulsar Speech Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_cue_row_round_trip());
    tally(test_cue_eligibility());
    tally(test_start_frame_alignments());
    tally(test_player_places_clip_exactly());
    tally(test_player_resamples_source_rate());
    tally(test_new_clip_fades_old_instead_of_cutting());
    tally(test_empty_clip_never_schedules());
    tally(test_load_pulsar_clip_publishes_and_clears());
    tally(test_speech_cue_bank_routing());
    tally(test_end_aligned_phrase_ends_on_the_next_downbeat());
    tally(test_end_aligned_phrase_ends_within_a_frame_of_the_measured_downbeat());
    tally(test_loop_end_phrase_forces_the_kick());
    tally(test_cue_without_a_clip_is_silent());
    tally(test_two_cues_in_one_cycle_both_play_and_kick_forces_once());
    tally(test_reload_during_playback_is_safe());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
