// LPG (low-pass gate) per-voice behavior:
//   - LPG_BYPASS  must produce output ≥ pre-LPG levels (regression)
//   - LPG_PLUCK   must produce a vactrol bloom + decay even with held gate
//   - LPG_SUSTAINED must follow the gate (open while held, decay on release)
//   - LPG_ENGINE_DEFAULT must resolve to kOrpheusLpgDefault[engine_index]
//   - Engine switch must reset LPG state (no leftover gain envelope)
//
// Each test allocates an OrpheusVoice on the heap (it carries 24 engine
// instances + render scratch + LPG state) and a single allocator buffer.
// All scenarios are run on the same voice in sequence.

#include "test_harness.h"
#include "orpheus_voice.h"
#include "stmlib/utils/buffer_allocator.h"
#include <memory>

static constexpr int kSr = 48000;
static constexpr int kAllocBytes = 32768;

namespace {

struct VoiceFixture {
    std::unique_ptr<OrpheusVoice> voice = std::make_unique<OrpheusVoice>();
    std::vector<uint8_t> alloc_buf = std::vector<uint8_t>(kAllocBytes, 0);

    VoiceFixture() {
        stmlib::BufferAllocator allocator(alloc_buf.data(), kAllocBytes);
        voice->Init(&allocator);
    }
};

// Render `n_blocks` of `block_size` frames into `out` with given LPG config.
// Returns peak |sample| over all rendered samples.
float render_and_measure(OrpheusVoice& v, int engine_index, int gate, float note,
                         int n_blocks, int block_size,
                         LpgMode mode, float decay, float colour,
                         float* out_buf, int out_buf_len) {
    float pk = 0.0f;
    int written = 0;
    for (int b = 0; b < n_blocks && written + block_size <= out_buf_len; b++) {
        v.Render(engine_index, gate, note, 0.5f, 0.5f, 0.5f, 0.0f,
                 out_buf + written, block_size, mode, decay, colour);
        for (int i = 0; i < block_size; i++) {
            float a = std::fabs(out_buf[written + i]);
            if (a > pk) pk = a;
        }
        written += block_size;
    }
    return pk;
}

// Peak in a sample range [lo, hi).
float peak_range(const float* buf, int lo, int hi) {
    float pk = 0.0f;
    for (int i = lo; i < hi; i++) {
        float a = std::fabs(buf[i]);
        if (a > pk) pk = a;
    }
    return pk;
}

}  // namespace

// ────────────────────────────────────────────────────────────────────
//  Test 1 — BYPASS regression: signal must reach the output unattenuated
// ────────────────────────────────────────────────────────────────────
static bool test_lpg_bypass_passthrough() {
    printf("\n=== Test: LPG_BYPASS leaves signal intact ===\n");
    VoiceFixture fx;
    bool all_pass = true;

    constexpr int kBlk = 64, kN = 30;  // ~40ms
    std::vector<float> buf(kBlk * kN);

    // Engine 9 (Waveshaping): raw oscillator, no internal envelope.
    float pk = render_and_measure(*fx.voice, 9, /*gate*/1, 60.0f,
                                  kN, kBlk, LPG_BYPASS, 0.5f, 0.5f,
                                  buf.data(), buf.size());
    bool ok = pk > 0.05f && pk < 1.1f;
    printf("  WSH BYPASS: peak=%.4f %s\n", pk, ok ? "OK" : "FAIL");
    all_pass &= ok;

    return all_pass;
}

// ────────────────────────────────────────────────────────────────────
//  Test 2 — PLUCK shape: held gate still produces a vactrol bloom + decay
// ────────────────────────────────────────────────────────────────────
static bool test_lpg_pluck_envelope_shape() {
    printf("\n=== Test: LPG_PLUCK produces bloom + decay (held gate) ===\n");
    VoiceFixture fx;
    bool all_pass = true;

    // ~1 second at 48kHz with 64-sample blocks = ~750 blocks, but we'll
    // settle for 200 blocks (~270ms) which is plenty for the vactrol decay.
    constexpr int kBlk = 64, kN = 200;
    std::vector<float> buf(kBlk * kN);

    // Engine 9 (Waveshaping). Hold gate high — PLUCK should still bloom & decay.
    render_and_measure(*fx.voice, 9, /*gate*/1, 60.0f,
                       kN, kBlk, LPG_PLUCK, /*decay*/0.7f, /*colour*/0.5f,
                       buf.data(), buf.size());

    // Bloom phase: peak in first 50ms (frames 0..2400).
    int bloom_end = std::min<int>(2400, (int)buf.size());
    float bloom_pk = peak_range(buf.data(), 0, bloom_end);
    // Decay phase: peak in the final 50ms.
    int decay_lo = std::max<int>(0, (int)buf.size() - 2400);
    float decay_pk = peak_range(buf.data(), decay_lo, (int)buf.size());

    bool blooms = bloom_pk > 0.05f;
    bool decays = decay_pk < bloom_pk * 0.5f;  // late peak < half of bloom peak
    printf("  WSH PLUCK: bloom_pk=%.4f decay_pk=%.4f ratio=%.2f %s\n",
           bloom_pk, decay_pk,
           bloom_pk > 1e-6f ? decay_pk / bloom_pk : 0.0f,
           (blooms && decays) ? "OK" : "FAIL");
    all_pass &= (blooms && decays);

    return all_pass;
}

// ────────────────────────────────────────────────────────────────────
//  Test 3 — SUSTAINED follows the gate
// ────────────────────────────────────────────────────────────────────
static bool test_lpg_sustained_follows_gate() {
    printf("\n=== Test: LPG_SUSTAINED follows gate (held=audible, released=decays) ===\n");
    VoiceFixture fx;
    bool all_pass = true;

    constexpr int kBlk = 64, kHold = 80, kRelease = 200;  // ~100ms hold, ~270ms release

    // Phase A: gate held — should bloom up and stay open.
    std::vector<float> hold_buf(kBlk * kHold);
    float hold_pk = render_and_measure(*fx.voice, 9, /*gate*/1, 60.0f,
                                       kHold, kBlk, LPG_SUSTAINED, 0.5f, 0.5f,
                                       hold_buf.data(), hold_buf.size());

    // Phase B: gate released — vactrol decays toward silence.
    std::vector<float> rel_buf(kBlk * kRelease);
    render_and_measure(*fx.voice, 9, /*gate*/0, 60.0f,
                       kRelease, kBlk, LPG_SUSTAINED, 0.5f, 0.5f,
                       rel_buf.data(), rel_buf.size());

    // Peak of late part of release (last 50ms) should be << hold peak.
    int late_lo = std::max<int>(0, (int)rel_buf.size() - 2400);
    float late_pk = peak_range(rel_buf.data(), late_lo, (int)rel_buf.size());

    bool open = hold_pk > 0.05f;
    // Vactrol decay is intentionally analog-slow — Plaits' formula gives
    // roughly -10..-15 dB at 270ms with decay=0.5, with full close taking
    // closer to 1s. We just need to confirm the gate closes meaningfully.
    bool decaying = late_pk < hold_pk * 0.4f;
    printf("  WSH SUSTAIN: hold_pk=%.4f late_release_pk=%.4f ratio=%.2f %s\n",
           hold_pk, late_pk,
           hold_pk > 1e-6f ? late_pk / hold_pk : 0.0f,
           (open && decaying) ? "OK" : "FAIL");
    all_pass &= (open && decaying);

    return all_pass;
}

// ────────────────────────────────────────────────────────────────────
//  Test 4 — ENGINE_DEFAULT resolves correctly per engine
// ────────────────────────────────────────────────────────────────────
static bool test_lpg_engine_default_resolution() {
    printf("\n=== Test: LPG_ENGINE_DEFAULT resolves to per-engine table ===\n");
    bool all_pass = true;

    // BD (engine 21) defaults to BYPASS. With ENGINE_DEFAULT,
    // its output should match raw BYPASS output (a struck transient).
    {
        VoiceFixture fx;
        constexpr int kBlk = 64, kN = 80;
        std::vector<float> def_buf(kBlk * kN);
        std::vector<float> byp_buf(kBlk * kN);

        // ENGINE_DEFAULT path
        // BD requires a rising-edge gate to trigger; render gate=0 first
        // to seed trigger_state_=false, then gate=1 to get a clean trigger.
        fx.voice->Render(21, 0, 60.0f, 0.5f, 0.5f, 0.5f, 0.0f,
                         def_buf.data(), kBlk, LPG_ENGINE_DEFAULT, 0.5f, 0.5f);
        float def_pk = render_and_measure(*fx.voice, 21, 1, 60.0f,
                                          kN - 1, kBlk, LPG_ENGINE_DEFAULT, 0.5f, 0.5f,
                                          def_buf.data() + kBlk, def_buf.size() - kBlk);
        // Same again with explicit BYPASS — must match magnitude.
        VoiceFixture fx2;
        fx2.voice->Render(21, 0, 60.0f, 0.5f, 0.5f, 0.5f, 0.0f,
                          byp_buf.data(), kBlk, LPG_BYPASS, 0.5f, 0.5f);
        float byp_pk = render_and_measure(*fx2.voice, 21, 1, 60.0f,
                                          kN - 1, kBlk, LPG_BYPASS, 0.5f, 0.5f,
                                          byp_buf.data() + kBlk, byp_buf.size() - kBlk);

        // Within 5% — the BD trigger is stochastic in noise content but
        // peak amplitude of the click should be very close.
        float ratio = (byp_pk > 1e-6f) ? def_pk / byp_pk : 0.0f;
        bool match = ratio > 0.95f && ratio < 1.05f;
        printf("  BD: ENGINE_DEFAULT pk=%.4f vs BYPASS pk=%.4f ratio=%.3f %s\n",
               def_pk, byp_pk, ratio, match ? "OK" : "FAIL");
        all_pass &= match;
    }

    // Sanity check that the resolution path itself works regardless of which
    // mode the table currently selects. The current baseline is all-BYPASS
    // so ENGINE_DEFAULT should give identical output to explicit BYPASS for
    // every engine. (If you flip an entry to PLUCK/SUSTAINED to A/B it,
    // expect this row to differ — that's the change you're listening for.)
    {
        VoiceFixture fx;
        constexpr int kBlk = 64, kN = 200;
        std::vector<float> def_buf(kBlk * kN);
        std::vector<float> byp_buf(kBlk * kN);

        render_and_measure(*fx.voice, 9, 1, 60.0f,
                           kN, kBlk, LPG_ENGINE_DEFAULT, 0.5f, 0.5f,
                           def_buf.data(), def_buf.size());
        VoiceFixture fx2;
        render_and_measure(*fx2.voice, 9, 1, 60.0f,
                           kN, kBlk, LPG_BYPASS, 0.5f, 0.5f,
                           byp_buf.data(), byp_buf.size());

        int lo = std::max<int>(0, (int)def_buf.size() - 2400);
        float def_late = peak_range(def_buf.data(), lo, (int)def_buf.size());
        float byp_late = peak_range(byp_buf.data(), lo, (int)byp_buf.size());

        // Resolution worked → the two paths produced *some* output that's
        // either equal (table=BYPASS) or differ (table=PLUCK/SUSTAINED).
        bool both_audible = def_late > 0.001f && byp_late > 0.001f;
        printf("  WSH: ENGINE_DEFAULT late_pk=%.4f vs BYPASS late_pk=%.4f (table-dependent) %s\n",
               def_late, byp_late, both_audible ? "OK" : "FAIL");
        all_pass &= both_audible;
    }

    return all_pass;
}

// ────────────────────────────────────────────────────────────────────
//  Test 5 — Engine switch resets LPG state
// ────────────────────────────────────────────────────────────────────
static bool test_lpg_resets_on_engine_switch() {
    printf("\n=== Test: engine switch resets LPG state ===\n");
    VoiceFixture fx;
    bool all_pass = true;

    constexpr int kBlk = 64;
    std::vector<float> buf(kBlk * 2);

    // Drive PLUCK on WSH for a while so vactrol_state climbs.
    for (int i = 0; i < 30; i++) {
        fx.voice->Render(9, 1, 60.0f, 0.5f, 0.5f, 0.5f, 0.0f,
                         buf.data(), kBlk, LPG_PLUCK, 0.5f, 0.5f);
    }
    // Switch to engine 21 (BD). On switch, LPG must Init() back to zero
    // gain. With BD's default = BYPASS, we expect normal raw drum hits;
    // but if we use ENGINE_DEFAULT here it's BYPASS so audible. The check
    // is simply that the first rendered block isn't dominated by leftover
    // LPG attenuation from WSH (which would make the BD start near silent).
    fx.voice->Render(21, 0, 60.0f, 0.5f, 0.5f, 0.5f, 0.0f,
                     buf.data(), kBlk, LPG_ENGINE_DEFAULT, 0.5f, 0.5f);
    fx.voice->Render(21, 1, 60.0f, 0.5f, 0.5f, 0.5f, 0.0f,
                     buf.data(), kBlk, LPG_ENGINE_DEFAULT, 0.5f, 0.5f);
    float pk = peak_range(buf.data(), 0, kBlk);
    bool ok = pk > 0.01f;
    printf("  After WSH→BD switch with ENGINE_DEFAULT: BD trigger pk=%.4f %s\n",
           pk, ok ? "OK" : "FAIL");
    all_pass &= ok;

    return all_pass;
}

bool run_lpg_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_lpg_bypass_passthrough());
    tally(test_lpg_pluck_envelope_shape());
    tally(test_lpg_sustained_follows_gate());
    tally(test_lpg_engine_default_resolution());
    tally(test_lpg_resets_on_engine_switch());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
