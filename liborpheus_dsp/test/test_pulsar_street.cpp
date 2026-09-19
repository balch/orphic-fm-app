// Street voice: beat-locked footsteps, the steam train, and their section bed in the host.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "../src/pulsar_street.h"
#include <cmath>
#include <cstring>
#include <vector>

// First frame of each burst: |x| crosses `threshold` after at least `quiet_frames` below it.
static std::vector<int> find_onsets(const std::vector<float>& x, float threshold, int quiet_frames) {
    std::vector<int> out;
    int quiet = quiet_frames;
    for (int i = 0; i < static_cast<int>(x.size()); i++) {
        if (std::fabs(x[i]) >= threshold) {
            if (quiet >= quiet_frames) out.push_back(i);
            quiet = 0;
        } else {
            quiet++;
        }
    }
    return out;
}

// Boundaries every `spacing` frames with steps 0..(count-1), rendered in 512-frame blocks.
static std::vector<float> render_steps(street::StreetVoice& v, int spacing, int count, int frames) {
    std::vector<float> l(frames, 0.0f), r(frames, 0.0f), tl(frames, 0.0f), tr(frames, 0.0f);
    int next = 0, step = 0;
    for (int start = 0; start < frames; start += 512) {
        const int n = std::min(512, frames - start);
        while (step < count && next < start + n) {
            v.OnStepBoundary(next - start, step++);
            next += spacing;
        }
        v.Process(l.data() + start, r.data() + start, tl.data() + start, tr.data() + start, n);
    }
    return l;
}

static float rms(const std::vector<float>& x, int from, int to) {
    double s = 0.0;
    for (int i = from; i < to; i++) s += static_cast<double>(x[i]) * x[i];
    return static_cast<float>(std::sqrt(s / std::max(1, to - from)));
}

static bool test_walk_steps_land_on_every_fourth_boundary() {
    printf("\n=== Test: WALK footsteps start on every 4th step boundary, nowhere else ===\n");
    street::StreetVoice v; v.Init(7, 48000.0f);
    v.set_bed(1.0f, false, 0.0f, 0.0f);
    const std::vector<float> l = render_steps(v, 3000, 8, 24000);   // steps at 0, 3000, ... 21000
    const std::vector<int> on = find_onsets(l, 0.02f, 600);
    const bool ok = on.size() == 2 && on[0] < 48 && std::abs(on[1] - 12000) < 48;
    printf("  onsets=%zu first=%d second=%d -- %s\n", on.size(),
           on.empty() ? -1 : on[0], on.size() > 1 ? on[1] : -1, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_run_steps_land_on_every_second_boundary() {
    printf("\n=== Test: RUN footsteps start on every 2nd step boundary ===\n");
    street::StreetVoice v; v.Init(7, 48000.0f);
    v.set_bed(1.0f, true, 0.0f, 0.0f);
    const std::vector<float> l = render_steps(v, 3000, 8, 24000);   // steps 0, 2, 4, 6
    const std::vector<int> on = find_onsets(l, 0.02f, 600);
    bool ok = on.size() == 4;
    for (size_t k = 0; ok && k < on.size(); k++) ok = std::abs(on[k] - static_cast<int>(k) * 6000) < 48;
    printf("  onsets=%zu -- %s\n", on.size(), ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_silent_bed_writes_nothing() {
    printf("\n=== Test: a zero bed ignores boundaries and adds nothing ===\n");
    street::StreetVoice v; v.Init(7, 48000.0f);
    v.set_bed(0.0f, false, 0.0f, 0.0f);
    const std::vector<float> l = render_steps(v, 3000, 8, 24000);
    bool zero = true;
    for (float s : l) zero = zero && s == 0.0f;
    const bool ok = zero && v.idle();
    printf("  zero=%d idle=%d -- %s\n", (int)zero, (int)v.idle(), ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_train_recedes_across_progress() {
    printf("\n=== Test: the train is far quieter late in its section than early ===\n");
    auto train_rms = [](float progress) {
        street::StreetVoice v; v.Init(11, 48000.0f);
        v.set_bed(0.0f, false, 1.0f, progress);
        std::vector<float> fl(48000, 0.0f), fr(48000, 0.0f), tl(48000, 0.0f), tr(48000, 0.0f);
        for (int start = 0; start < 48000; start += 512) {
            const int n = std::min(512, 48000 - start);
            v.Process(fl.data() + start, fr.data() + start, tl.data() + start, tr.data() + start, n);
        }
        return rms(tl, 0, 48000);
    };
    const float early = train_rms(0.1f), late = train_rms(0.9f);
    const bool ok = early > 0.001f && late < early * 0.3f;
    printf("  early=%.4f late=%.4f -- %s\n", early, late, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_whistle_blows_once_on_section_entry() {
    printf("\n=== Test: a train section's entry whistles; without entry, no whistle ===\n");
    auto whistle_band = [](bool enter, int seconds) {
        street::StreetVoice v; v.Init(11, 48000.0f);
        v.set_bed(0.0f, false, 1.0f, 0.0f);
        if (enter) v.OnSectionEntry(1.0f);
        const int frames = 48000 * seconds;
        std::vector<float> fl(frames, 0.0f), fr(frames, 0.0f), tl(frames, 0.0f), tr(frames, 0.0f);
        for (int start = 0; start < frames; start += 512) {
            const int n = std::min(512, frames - start);
            v.Process(fl.data() + start, fr.data() + start, tl.data() + start, tr.data() + start, n);
        }
        // Band around the two whistle tones.
        stmlib::Svf bp; bp.Init();
        bp.set_f_q<stmlib::FREQUENCY_FAST>(605.0f / 48000.0f, 4.0f);
        std::vector<float> y(frames);
        for (int i = 0; i < frames; i++) y[i] = bp.Process<stmlib::FILTER_MODE_BAND_PASS>(tl[i]);
        return std::make_pair(rms(y, 4800, 48000), rms(y, 48000 * (seconds - 1), 48000 * seconds));
    };
    const auto with = whistle_band(true, 3);
    const auto without = whistle_band(false, 3);
    const bool ok = with.first > without.first * 3.0f && with.second < with.first / 3.0f;
    printf("  entered=%.4f plain=%.4f entered-later=%.4f -- %s\n",
           with.first, without.first, with.second, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_street_is_deterministic_and_bounded() {
    printf("\n=== Test: same seed renders identically; everything at max stays finite ===\n");
    auto render = [](uint32_t seed) {
        street::StreetVoice v; v.Init(seed, 48000.0f);
        v.set_bed(1.0f, true, 1.0f, 0.0f);
        v.OnSectionEntry(1.0f);
        return render_steps(v, 3000, 64, 48000 * 4);
    };
    const std::vector<float> a = render(3), b = render(3), c = render(4);
    bool finite = true;
    float peak = 0.0f;
    for (float s : a) { finite = finite && std::isfinite(s); peak = std::max(peak, std::fabs(s)); }
    const bool ok = a == b && a != c && finite && peak < 2.0f;
    printf("  same=%d differs=%d finite=%d peak=%.3f -- %s\n",
           (int)(a == b), (int)(a != c), (int)finite, peak, ok ? "PASS" : "FAIL");
    return ok;
}

struct StreetTestBed { float footsteps = 0.0f; float run = 0.0f; float echo = 0.0f; float train = 0.0f; };

// Two sections with fixed lengths whose only variables are their street beds and the 0 -> 1
// pre-roll; section 0 opens. Mirrors push_storm_weather_arrangement in test_pulsar_storm.cpp.
static void push_street_arrangement(OrpheusEngine* engine, const StreetTestBed& s0,
                                    const StreetTestBed& s1, int trans_bars, int bars) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    std::vector<float> sd(kMaxSections * kSectionDataFields, 0.0f);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kSectionDataFields;
        sd[b + 5] = sd[b + 6] = sd[b + 7] = sd[b + 8] = -1.0f;
        sd[b + 18] = sd[b + 19] = sd[b + 20] = -1.0f;
    }
    const StreetTestBed* bed[2] = { &s0, &s1 };
    for (int s = 0; s < 2; s++) {
        const int b = s * kSectionDataFields;
        sd[b + 0] = static_cast<float>(bars); sd[b + 1] = static_cast<float>(bars);
        sd[b + 2] = 1.0f; sd[b + 3] = 0.8f; sd[b + 4] = 1.0f;
        sd[b + 27] = bed[s]->footsteps;
        sd[b + 28] = bed[s]->run;
        sd[b + 29] = bed[s]->echo;
        sd[b + 30] = bed[s]->train;
    }
    for (int i = 0; i < kMaxSections * kSectionDataFields; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);
    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[0] = 1.0f; tr[1] = 1.0f; tr[2] = static_cast<float>(trans_bars);
    const int t1 = kMaxSectionTransitions * 3;
    tr[t1 + 0] = 0.0f; tr[t1 + 1] = 1.0f; tr[t1 + 2] = 0.0f;
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// Playing, unity mix, every track silent, both RNGs pinned, 120 BPM, 16 steps.
static OrpheusEngine* make_muted_street_engine(float swing) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    solo_track(engine, -1);
    pin_pulsar_rngs(engine);
    engine->pulsar_genre_swing.store(swing, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    // energy=1 disables elastic-tempo wander (max_drift = (1-energy)*0.05), same as
    // test_pulsar_timing.cpp, so onsets land on an exact grid for spacing checks.
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    return engine;
}

static std::vector<float> render_unit(OrpheusEngine* engine, int blocks) {
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    std::vector<float> out;
    out.reserve(static_cast<size_t>(blocks) * 512);
    for (int b = 0; b < blocks; b++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        out.insert(out.end(), engine->pulsar_out_l, engine->pulsar_out_l + 512);
    }
    return out;
}

static bool test_host_footsteps_follow_the_beat_under_swing() {
    printf("\n=== Test: host footsteps land a beat apart (WALK) and an 8th apart (RUN) at swing 0.3 ===\n");
    bool ok = true;
    for (int run = 0; run <= 1; run++) {
        OrpheusEngine* engine = make_muted_street_engine(0.3f);
        StreetTestBed walk; walk.footsteps = 1.0f; walk.run = static_cast<float>(run);
        push_street_arrangement(engine, walk, walk, 0, 16);
        trigger_vibe_load(engine);
        const std::vector<float> out = render_unit(engine, 250);   // 128000 frames
        float peak = 0.0f;
        for (float v : out) peak = std::max(peak, std::fabs(v));
        const std::vector<int> on = find_onsets(out, 0.2f * peak, 1500);
        const int spacing = run ? 12000 : 24000;                     // 6000 samples per step
        bool spaced = on.size() >= 3;
        for (size_t k = 1; spaced && k < on.size(); k++) spaced = std::abs(on[k] - on[k - 1] - spacing) < 96;
        // Even spacing alone can't tell step 0,4,8... from 2,6,10...: both keep the
        // spacing. Pin the first onset to the vibe-load boundary at frame 0.
        const bool starts_on_zero = !on.empty() && on[0] < 100;
        printf("  run=%d onsets=%zu spaced=%d onset0=%d\n", run, on.size(), (int)spaced,
               on.empty() ? -1 : on[0]);
        ok = ok && peak > 0.0f && spaced && starts_on_zero;
        orpheus_engine_destroy(engine);
    }
    printf("  -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_street_absent_is_silent() {
    printf("\n=== Test: a vibe with no street renders exactly nothing from the street voice ===\n");
    OrpheusEngine* engine = make_muted_street_engine(0.0f);
    StreetTestBed none;
    push_street_arrangement(engine, none, none, 0, 4);
    trigger_vibe_load(engine);
    const std::vector<float> out = render_unit(engine, 200);
    float peak = 0.0f;
    for (float v : out) peak = std::max(peak, std::fabs(v));
    const bool ok = peak < 1e-6f;
    printf("  peak=%.2e -- %s\n", peak, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// trans_bars=1 stages and flips a section's pre-roll ramp in the same advance_section
// call (pulsar_section.h), so no block ever renders mid-ramp -- see the N=2 pinned
// trace in test_pulsar_sections.cpp:356-377. trans_bars=2 is the smallest edge that
// renders a real pre-roll bar: bars_remaining==1 stages the ramp and is rendered in
// full before the next bar's advance_section call flips the section.
static bool test_footsteps_fade_across_the_pre_roll_before_the_flip() {
    printf("\n=== Test: footsteps fade across the pre-roll ramp bar, before the section flips ===\n");
    OrpheusEngine* engine = make_muted_street_engine(0.0f);
    StreetTestBed on; on.footsteps = 1.0f;
    StreetTestBed off;
    // 3-bar section, trans_bars=2: bars 1-2 are plain, bar 3 (frames [192000,288000))
    // is the one rendered ramp bar; the flip to section 1 (silent) happens at 288000.
    push_street_arrangement(engine, on, off, 2, 3);
    trigger_vibe_load(engine);
    const std::vector<float> out = render_unit(engine, 600);   // 307200 frames, past the flip
    const float ramp_first_half = rms(out, 192000, 240000);
    const float ramp_second_half = rms(out, 240000, 288000);
    const bool ok = ramp_first_half > 0.001f && ramp_second_half < ramp_first_half * 0.6f;
    printf("  ramp_first=%.4f ramp_second=%.4f -- %s\n",
           ramp_first_half, ramp_second_half, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// An incoming train (absent from the current section, present in the staged
// destination) must sound at its entry character throughout the pre-roll: the host
// pins train_progress to 0 while blending in (orpheus_unit_pulsar.cpp, "arrives close
// and slow"), rather than reusing the current section's own bar-progress, which would
// make it sound like it is already mid-recede. Chuff rate is progress-driven and
// independent of the volume blend, so its onset spacing is a clean, level-independent
// probe: kChuffRateStart (1.5/s = 32000 samples) if the pin holds, drifting toward
// kChuffRateEnd (6/s = 8000 samples) if it does not.
static bool test_incoming_train_sounds_fresh_during_the_pre_roll() {
    printf("\n=== Test: an incoming train's pre-roll chuffs at its entry rate, not mid-recede ===\n");
    OrpheusEngine* engine = make_muted_street_engine(0.0f);
    StreetTestBed quiet;                                // section 0: no train
    StreetTestBed arriving; arriving.train = 1.0f;      // section 1: train
    push_street_arrangement(engine, quiet, arriving, 2, 3);   // ramp bar: [192000, 288000)
    trigger_vibe_load(engine);
    const std::vector<float> out = render_unit(engine, 600);   // 307200 frames, past the flip
    // The whole ramp bar: at 32000 samples/chuff this holds ~3 onsets, enough to
    // measure spacing even though the train's own volume is still blending in.
    const std::vector<float> window(out.begin() + 192000, out.begin() + 288000);
    float peak = 0.0f;
    for (float v : window) peak = std::max(peak, std::fabs(v));
    const std::vector<int> on = find_onsets(window, 0.25f * peak, 4000);
    bool spaced = on.size() >= 2;
    for (size_t k = 1; spaced && k < on.size(); k++)
        spaced = std::abs(on[k] - on[k - 1] - 32000) < 4000;
    printf("  onsets=%zu peak=%.4f -- %s\n", on.size(), peak, spaced ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return spaced;
}

static bool test_host_train_recedes_through_its_section() {
    printf("\n=== Test: the host train is louder in its section's first cycle than its last ===\n");
    OrpheusEngine* engine = make_muted_street_engine(0.0f);
    StreetTestBed train; train.train = 1.0f;
    push_street_arrangement(engine, train, train, 0, 4);   // 4 cycles = 384000 frames
    trigger_vibe_load(engine);
    const std::vector<float> out = render_unit(engine, 750);   // 384000 frames: all 4 cycles
    // kWhistleSeconds is 1.2s = 57600 frames; start well clear of it (60000) so this
    // window reads the chuffs alone, not a mix still dominated by the whistle tail.
    const float first = rms(out, 60000, 96000);
    const float last = rms(out, 300000, 380000);
    const bool ok = first > 0.001f && last < first * 0.33f;
    printf("  first=%.4f last=%.4f -- %s\n", first, last, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_street_tests() {
    printf("\n=== Pulsar Street Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_walk_steps_land_on_every_fourth_boundary());
    tally(test_run_steps_land_on_every_second_boundary());
    tally(test_silent_bed_writes_nothing());
    tally(test_train_recedes_across_progress());
    tally(test_whistle_blows_once_on_section_entry());
    tally(test_street_is_deterministic_and_bounded());
    tally(test_host_footsteps_follow_the_beat_under_swing());
    tally(test_street_absent_is_silent());
    tally(test_footsteps_fade_across_the_pre_roll_before_the_flip());
    tally(test_incoming_train_sounds_fresh_during_the_pre_roll());
    tally(test_host_train_recedes_through_its_section());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
