// Mix bench: the engine's per-block meter summaries and the ring the reader thread drains.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "mix_bench.h"
#include <cmath>
#include <cstdio>
#include <vector>

namespace {

constexpr int kSr = 48000;
constexpr int kBlock = 256;

bool check(bool ok, const char* what) {
    printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    return ok;
}

// DJ graph engine. With play, Pulsar runs the baseline fixture (panned tracks) with every track
// forced dense: density_override defaults to 0, which leaves generative tracks empty.
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

BenchSummary unflatten(const float* f) {
    BenchSummary s{};
    s.frames = static_cast<int32_t>(f[0]);
    s.section = static_cast<int32_t>(f[1]);
    s.pre_limit_peak = f[2]; s.e_pre_limit = f[3]; s.e_post_limit = f[4]; s.e_pre_hpf = f[5]; s.e_post_hpf = f[6];
    s.over_knee = static_cast<int32_t>(f[7]);
    s.samples = static_cast<int32_t>(f[8]);
    std::memcpy(s.ch, f + 9, sizeof(s.ch));
    return s;
}

}  // namespace

bool run_mix_bench_tests() {
    printf("\n=== Mix bench ===\n");
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };

    // disabled_by_default
    {
        OrpheusEngine* e = orpheus_engine_create(static_cast<float>(kSr));
        tally(check(e->mix_bench.load() == nullptr && e->pulsar_measure_taps.load() == nullptr,
                    "disabled_by_default: no bench and no taps after create"));
        orpheus_engine_destroy(e);
    }

    // summaries_flow and dry_is_sum share one render.
    {
        OrpheusEngine* e = dj_engine(true);
        tally(check(e != nullptr, "summaries_flow: the DJ graph loads"));
        if (!e) TEST_SUITE_RETURN(pass, fail);
        orpheus_engine_set_mix_bench(e, 1);
        tally(check(e->mix_bench.load() != nullptr && e->pulsar_measure_taps.load() != nullptr,
                    "enable sets the bench and the taps"));
        std::vector<float> buf(kBlock * 2);
        for (int b = 0; b < 200; b++) orpheus_engine_process(e, buf.data(), kBlock);

        std::vector<float> flat(static_cast<size_t>(kBenchSummaryFloats) * 1024);
        const int n = orpheus_engine_drain_mix_bench(e, flat.data(), 1024);
        printf("  drained %d summaries\n", n);
        tally(check(n == 200, "summaries_flow: one summary per block"));
        bool frames_ok = true, out_ok = true;
        double dry_k = 0.0, dry_e = 0.0, out_e = 0.0, track_l[8] = {}, track_r[8] = {};
        int loudest = -1;
        float loudest_e = 0.0f;
        for (int i = 0; i < n; i++) {
            const BenchSummary s = unflatten(&flat[static_cast<size_t>(i) * kBenchSummaryFloats]);
            frames_ok &= s.frames == kBlock && s.samples == 2 * kBlock;
            out_ok &= s.ch[kBenchOut].peak <= 1.0f;
            dry_k += s.ch[kBenchDry].k_energy;
            dry_e += static_cast<double>(s.ch[kBenchDry].e_l) + s.ch[kBenchDry].e_r;
            out_e += static_cast<double>(s.ch[kBenchOut].e_l) + s.ch[kBenchOut].e_r;
            for (int t = 0; t < 8; t++) { track_l[t] += s.ch[t].e_l; track_r[t] += s.ch[t].e_r; }
            if (s.e_pre_hpf > loudest_e) { loudest_e = s.e_pre_hpf; loudest = i; }
        }
        printf("  dry K energy %.4g, dry energy %.4g, out energy %.4g over %d blocks\n", dry_k, dry_e, out_e, n);
        // The baseline fixture pans tracks 1, 2 and 4-7 off centre, so their sides must differ.
        int lopsided = 0;
        for (int t = 0; t < 8; t++) {
            const double sum = track_l[t] + track_r[t];
            printf("    track %d: e_l %.4g e_r %.4g\n", t, track_l[t], track_r[t]);
            if (sum > 1e-6 && std::fabs(track_l[t] - track_r[t]) > 0.05 * sum) lopsided++;
        }
        tally(check(frames_ok, "summaries_flow: every summary covers 256 frames"));
        tally(check(dry_k > 0.0, "summaries_flow: the dry mix has K-weighted energy"));
        tally(check(out_ok, "summaries_flow: OUT never peaks above 1.0"));
        tally(check(dry_e > 1.0 && out_e > 0.01, "summaries_flow: DRY and OUT carry clear energy"));
        tally(check(lopsided > 0, "summaries_flow: a panned track has e_l != e_r"));
        tally(check(orpheus_engine_drain_mix_bench(e, flat.data(), 1024) == 0, "a second drain is empty"));

        // dry_is_sum: DRY is the sum of tracks and sources, which is the pre-HPF bus.
        bool sum_ok = loudest >= 0;
        if (sum_ok) {
            const BenchSummary s = unflatten(&flat[static_cast<size_t>(loudest) * kBenchSummaryFloats]);
            const double dry = static_cast<double>(s.ch[kBenchDry].e_l) + s.ch[kBenchDry].e_r;
            const double rel = std::fabs(dry - s.e_pre_hpf) / s.e_pre_hpf;
            printf("  block %d: DRY %.6g vs pre-HPF %.6g (rel %.2e)\n", loudest, dry, s.e_pre_hpf, rel);
            sum_ok = rel < 1e-3;
        }
        tally(check(sum_ok, "dry_is_sum: DRY energy equals the pre-HPF bus energy"));
        orpheus_engine_destroy(e);
    }

    // ring_overflow_drops_oldest_safely: the writer never blocks; overflow drops the newest.
    {
        OrpheusEngine* e = dj_engine(false);
        if (e) {
            orpheus_engine_set_mix_bench(e, 1);
            std::vector<float> buf(kBlock * 2);
            for (int b = 0; b < 1500; b++) orpheus_engine_process(e, buf.data(), kBlock);
            std::vector<float> flat(static_cast<size_t>(kBenchSummaryFloats) * 2048);
            const int n = orpheus_engine_drain_mix_bench(e, flat.data(), 2048);
            const uint64_t dropped = e->mix_bench.load()->dropped();
            printf("  drained %d, dropped %llu\n", n, static_cast<unsigned long long>(dropped));
            tally(check(n <= 1024 && n > 0, "ring_overflow: the drain returns at most the ring's 1024"));
            tally(check(n + static_cast<int>(dropped) == 1500, "ring_overflow: every block is either drained or counted dropped"));
            orpheus_engine_destroy(e);
        } else {
            tally(check(false, "ring_overflow: DJ graph loads"));
        }
    }

    // disable_frees
    {
        OrpheusEngine* e = dj_engine(false);
        if (e) {
            std::vector<float> buf(kBlock * 2);
            orpheus_engine_set_mix_bench(e, 1);
            orpheus_engine_process(e, buf.data(), kBlock);
            orpheus_engine_set_mix_bench(e, 0);
            orpheus_engine_process(e, buf.data(), kBlock);
            tally(check(e->mix_bench.load() == nullptr && e->pulsar_measure_taps.load() == nullptr,
                        "disable_frees: both pointers are null after disable and a block"));
            float one[kBenchSummaryFloats];
            tally(check(orpheus_engine_drain_mix_bench(e, one, 1) == 0, "disable_frees: a drain while disabled is empty"));
            orpheus_engine_set_mix_bench(e, 1);
            orpheus_engine_process(e, buf.data(), kBlock);
            tally(check(orpheus_engine_drain_mix_bench(e, one, 1) == 1, "re-enable produces summaries again"));
            orpheus_engine_destroy(e);
        } else {
            tally(check(false, "disable_frees: DJ graph loads"));
        }
    }

    printf("\nMix bench tests: %s\n", fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(pass, fail);
}
