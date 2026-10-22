// Stem capture: the armable ring of 13 interleaved stereo stems beside the mix bench.
//
//   liborpheus_dsp/build-desktop/orpheus_dsp_test stem_capture
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_dsp.h"
#include "orpheus_engine.h"
#include "mix_bench.h"
#include "stem_capture.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

namespace {

constexpr int kSr = 48000;
constexpr int kBlock = 256;
constexpr int kFpf = ORPHEUS_STEM_FLOATS_PER_FRAME;
using Taps = OrpheusEngine::PulsarMeasureTaps;

bool check(bool ok, const char* what) {
    printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    return ok;
}

// Same fixture as test_mix_bench.cpp: DJ graph, baseline Pulsar tracks forced dense.
OrpheusEngine* dj_engine(bool play) {
    OrpheusEngine* e = orpheus_engine_create(static_cast<float>(kSr));
    if (!load_dj_graph(e)) { orpheus_engine_destroy(e); return nullptr; }
    if (!play) return e;
    e->pulsar_playing.store(1, std::memory_order_relaxed);
    e->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(e);
    for (int t = 0; t < 8; t++) e->pulsar_track_density_override[t].store(0.7f, std::memory_order_relaxed);
    pin_pulsar_rngs(e);
    trigger_vibe_load(e);
    e->clock_bpm.store(128.0f, std::memory_order_relaxed);
    return e;
}

// The value a filled tap holds for stem float `slot` (0-25) of frame `frame`: unique per slot and
// frame, positive on L and negative on R. Exact in float for frames below 2^19.
float slot_value(int frame, int slot) {
    const float v = static_cast<float>(frame * 32 + slot + 1);
    return slot % 2 == 0 ? v : -v;
}

// Fills every tap and out so tap index i holds frame first + i, in the stem layout's slots.
void fill_taps(Taps& t, std::vector<float>& out, int first) {
    out.resize(static_cast<size_t>(kMaxFrames) * 2);
    for (int i = 0; i < kMaxFrames; i++) {
        const int f = first + i;
        for (int c = 0; c < 8; c++) { t.track_l[c][i] = slot_value(f, 2 * c); t.track_r[c][i] = slot_value(f, 2 * c + 1); }
        for (int s = 0; s < Taps::kMeasureSources; s++) {
            t.source_l[s][i] = slot_value(f, 16 + 2 * s);
            t.source_r[s][i] = slot_value(f, 17 + 2 * s);
        }
        t.pre_limit_l[i] = slot_value(f, 22);
        t.pre_limit_r[i] = slot_value(f, 23);
        out[2 * i] = slot_value(f, 24);
        out[2 * i + 1] = slot_value(f, 25);
    }
}

// True when frames [0, n) of d are frames first.. first + n - 1 in the stem layout.
bool frames_match(const float* d, int n, int first) {
    for (int f = 0; f < n; f++)
        for (int k = 0; k < kFpf; k++)
            if (d[static_cast<size_t>(f) * kFpf + k] != slot_value(first + f, k)) return false;
    return true;
}

}  // namespace

bool run_stem_capture_tests() {
    printf("\n=== Stem capture ===\n");
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };
    std::vector<float> out;
    auto taps = std::make_unique<Taps>();
    fill_taps(*taps, out, 0);

    // disarmedWritesNothing
    {
        StemCapture sc(1 << 10);
        sc.on_block(*taps, out.data(), 512);
        std::vector<float> d(static_cast<size_t>(1024) * kFpf);
        tally(check(sc.drain(d.data(), 1024) == 0, "disarmedWritesNothing: on_block before arm records nothing"));
    }

    // armRecordsExactlyThenStops
    {
        StemCapture sc(1 << 10);
        const bool armed = sc.arm(1000);
        for (int b = 0; b < 4; b++) sc.on_block(*taps, out.data(), 512);
        std::vector<float> d(static_cast<size_t>(2048) * kFpf);
        const int n = sc.drain(d.data(), 2048);
        printf("  armed %d, drained %d, remaining %d\n", armed, n, sc.remaining());
        tally(check(armed && n == 1000, "armRecordsExactlyThenStops: drain returns exactly the armed 1000 frames"));
        tally(check(sc.remaining() == 0, "armRecordsExactlyThenStops: remaining is 0 afterwards"));
        sc.on_block(*taps, out.data(), 512);
        tally(check(sc.drain(d.data(), 2048) == 0, "armRecordsExactlyThenStops: a fifth block adds nothing"));
    }

    // layoutOrder
    {
        StemCapture sc(1 << 10);
        sc.arm(4);
        sc.on_block(*taps, out.data(), 4);
        std::vector<float> d(static_cast<size_t>(4) * kFpf);
        const int n = sc.drain(d.data(), 4);
        tally(check(n == 4 && frames_match(d.data(), n, 0),
                    "layoutOrder: tracks, storm, street, speech, bus, out, L then R, per frame"));
    }

    // wrapsAround: 300-frame blocks drained after each one push both indices past the 1024
    // capacity, so writes wrap and drain takes its second copy.
    {
        StemCapture sc(1 << 10);
        auto wt = std::make_unique<Taps>();
        std::vector<float> wout, d(static_cast<size_t>(1024) * kFpf);
        constexpr int kTotal = 3000, kWrapBlock = 300;
        sc.arm(kTotal);
        int drained = 0;
        bool in_order = true;
        for (int first = 0; first < kTotal; first += kWrapBlock) {
            fill_taps(*wt, wout, first);
            sc.on_block(*wt, wout.data(), kWrapBlock);
            const int n = sc.drain(d.data(), 1024);
            in_order = in_order && n == kWrapBlock && frames_match(d.data(), n, drained);
            drained += n;
        }
        printf("  drained %d, dropped %llu\n", drained, static_cast<unsigned long long>(sc.dropped()));
        tally(check(in_order && drained == kTotal && sc.dropped() == 0,
                    "wrapsAround: 3000 frames through a 1024 ring come out in order and intact"));
    }

    // overflowCountsDropsAndKeepsCounting
    {
        StemCapture sc(1 << 10);
        sc.arm(4096);
        for (int b = 0; b < 8; b++) sc.on_block(*taps, out.data(), 512);
        std::vector<float> d(static_cast<size_t>(4096) * kFpf);
        printf("  dropped %llu, remaining %d\n", static_cast<unsigned long long>(sc.dropped()), sc.remaining());
        tally(check(sc.dropped() == 3072, "overflowCountsDropsAndKeepsCounting: 3072 frames dropped"));
        tally(check(sc.remaining() == 0, "overflowCountsDropsAndKeepsCounting: dropped frames count against remaining"));
        tally(check(sc.drain(d.data(), 4096) == 1024, "overflowCountsDropsAndKeepsCounting: drain returns the ring's 1024"));
    }

    // armWhileArmedIsRefused
    {
        StemCapture sc(1 << 10);
        const bool first = sc.arm(600);
        const bool second = sc.arm(600);
        sc.on_block(*taps, out.data(), 512);
        const bool third = sc.arm(600);
        sc.on_block(*taps, out.data(), 512);
        const bool after = sc.arm(600);
        tally(check(first && !second && !third && after,
                    "armWhileArmedIsRefused: arm is refused until remaining reaches 0"));
    }

    // engineIntegration
    {
        OrpheusEngine* e = dj_engine(true);
        tally(check(e != nullptr, "engineIntegration: the DJ graph loads"));
        if (!e) TEST_SUITE_RETURN(pass, fail);
        orpheus_engine_set_mix_bench(e, 1);
        orpheus_engine_set_stem_capture(e, 1);
        constexpr int kFrames = 48000;
        tally(check(orpheus_engine_arm_stem_capture(e, kFrames) == 1, "engineIntegration: arm succeeds"));
        const int blocks = (kSr * 11 / 10 + kBlock - 1) / kBlock;
        std::vector<float> buf(kBlock * 2), rendered, stems, chunk(static_cast<size_t>(1024) * kFpf);
        float bench[ORPHEUS_MIX_BENCH_SUMMARY_FLOATS * 64];
        for (int b = 0; b < blocks; b++) {
            orpheus_engine_process(e, buf.data(), kBlock);
            rendered.insert(rendered.end(), buf.begin(), buf.end());
            int n;
            while ((n = orpheus_engine_drain_stem_capture(e, chunk.data(), 1024)) > 0)
                stems.insert(stems.end(), chunk.begin(), chunk.begin() + static_cast<long>(n) * kFpf);
            orpheus_engine_drain_mix_bench(e, bench, 64);
        }
        const int got = static_cast<int>(stems.size() / kFpf);
        const long long dropped = orpheus_engine_stem_capture_dropped(e);
        printf("  drained %d frames, dropped %lld, remaining %d\n", got, dropped, orpheus_engine_stem_capture_remaining(e));
        tally(check(got == kFrames && dropped == 0, "engineIntegration: all 48000 frames drained, none dropped"));
        bool out_exact = got == kFrames;
        for (int f = 0; out_exact && f < got; f++)
            out_exact = stems[static_cast<size_t>(f) * kFpf + 24] == rendered[2 * static_cast<size_t>(f)] &&
                        stems[static_cast<size_t>(f) * kFpf + 25] == rendered[2 * static_cast<size_t>(f) + 1];
        tally(check(out_exact, "engineIntegration: the out stem equals the output buffer, bit-exact"));
        double best = 0.0;
        for (int t = 0; t < 8; t++) {
            double sum = 0.0;
            for (int f = 0; f < got; f++) {
                const double l = stems[static_cast<size_t>(f) * kFpf + 2 * t];
                const double r = stems[static_cast<size_t>(f) * kFpf + 2 * t + 1];
                sum += l * l + r * r;
            }
            const double rms = got > 0 ? std::sqrt(sum / (2.0 * got)) : 0.0;
            printf("    track%d rms %.4g\n", t, rms);
            best = std::max(best, rms);
        }
        tally(check(best > 1e-4, "engineIntegration: at least one track stem has RMS > 1e-4"));
        orpheus_engine_set_stem_capture(e, 0);
        orpheus_engine_process(e, buf.data(), kBlock);
        tally(check(e->stem_capture.load() == nullptr && orpheus_engine_drain_stem_capture(e, chunk.data(), 1024) == 0,
                    "engineIntegration: after disable a block runs and drain returns 0"));
        orpheus_engine_destroy(e);
    }

    // stemCaptureWithoutBenchRecordsNothing: the ring reads the bench's taps, so the Kotlin side
    // always enables the bench first.
    {
        OrpheusEngine* e = dj_engine(true);
        if (e) {
            orpheus_engine_set_stem_capture(e, 1);
            orpheus_engine_arm_stem_capture(e, 1000);
            std::vector<float> buf(kBlock * 2), chunk(static_cast<size_t>(1024) * kFpf);
            for (int b = 0; b < kSr / kBlock; b++) orpheus_engine_process(e, buf.data(), kBlock);
            const int n = orpheus_engine_drain_stem_capture(e, chunk.data(), 1024);
            const int rem = orpheus_engine_stem_capture_remaining(e);
            printf("  drained %d, remaining %d\n", n, rem);
            tally(check(n == 0 && rem == 1000, "stemCaptureWithoutBenchRecordsNothing: no bench, no frames"));
            orpheus_engine_destroy(e);
        } else {
            tally(check(false, "stemCaptureWithoutBenchRecordsNothing: DJ graph loads"));
        }
    }

    // disableAndDestroyWaitForAnInFlightReader: a held reader guard (as a drain mid-call holds it)
    // keeps both the disable and the destroy from freeing the ring until it is released.
    for (const bool destroy : {false, true}) {
        const char* what = destroy ? "disableAndDestroyWaitForAnInFlightReader: destroy waits for the reader"
                                   : "disableAndDestroyWaitForAnInFlightReader: disable waits for the reader";
        OrpheusEngine* e = orpheus_engine_create(static_cast<float>(kSr));
        orpheus_engine_set_stem_capture(e, 1);
        e->stem_capture_drains.fetch_add(1, std::memory_order_seq_cst);
        std::atomic<bool> returned{false};
        std::thread closer([&] {
            if (destroy) orpheus_engine_destroy(e); else orpheus_engine_set_stem_capture(e, 0);
            returned.store(true, std::memory_order_seq_cst);
        });
        // Past the ~100 ms block wait (no audio runs here), so only the reader holds it up.
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        const bool held = !returned.load(std::memory_order_seq_cst);
        // A destroy that did not wait has already freed the engine.
        if (held || !destroy) e->stem_capture_drains.fetch_sub(1, std::memory_order_seq_cst);
        closer.join();
        tally(check(held && returned.load(), what));
        if (!destroy) orpheus_engine_destroy(e);
    }

    printf("\nStem capture tests: %s\n", fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(pass, fail);
}
