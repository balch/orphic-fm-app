// Cowbell unit tests: knob mapping, strike packing and the UNIT_COWBELL graph unit.
// Add a test_* function below and call it from run_cowbell_unit_tests().
#include "cowbell_macros.h"
#include "kotlin_engine_ordinal.h"
#include "orpheus_dsp.h"
#include "orpheus_engine.h"
#include "orpheus_units.h"
#include "stmlib/utils/random.h"
#include "test_harness.h"
#include "test_pulsar_helpers.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <random>
#include <thread>
#include <vector>

extern int g_suite_passes;
extern int g_suite_fails;

namespace {

int g_pass = 0;
int g_fail = 0;

#define CHECK(cond) do { \
    if (cond) { ++g_pass; } \
    else { ++g_fail; printf("  FAIL: %s (line %d)\n", #cond, __LINE__); } \
} while (0)

bool near(float a, float b, float tol) { return std::fabs(a - b) <= tol; }

void test_macros_defaults() {
    printf("\n--- macros_defaults ---\n");
    // The shipped default: METAL body, TONE 0, RING 0.48, INTENSITY 0.85.
    const CowbellParams d = cowbell_macros(2, 0.0f, 0.48f, 0.85f);
    CHECK(d.model == 0 && near(d.structure, 0.25f, 1e-4f) && near(d.damping, 0.5289f, 1e-4f));
    CHECK(near(d.brightness, 0.44f + 0.40f * 0.85f - 0.15f * 0.48f, 1e-4f));
    CHECK(near(d.strike_gain, 0.35f + 0.65f * 0.85f, 1e-4f));
    CHECK(near(d.level_trim, 1.0f, 1e-6f));
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    CHECK(e->cowbell_body.load() == 2);
    CHECK(near(e->cowbell_tone.load(), 0.0f, 1e-6f));
    CHECK(near(e->cowbell_ring.load(), 0.48f, 1e-6f));
    CHECK(near(e->cowbell_intensity.load(), 0.85f, 1e-6f));
    orpheus_engine_destroy(e);
}

void test_macros_bodies() {
    printf("\n--- macros_bodies ---\n");
    // Model and polyphony per body.
    CHECK(cowbell_macros(0, .5f, .5f, .5f).model == 2 && cowbell_macros(0, .5f, .5f, .5f).polyphony == 2);
    CHECK(cowbell_macros(1, .5f, .5f, .5f).model == 1 && cowbell_macros(1, .5f, .5f, .5f).polyphony == 1);
    CHECK(cowbell_macros(2, .5f, .5f, .5f).model == 0 && cowbell_macros(2, .5f, .5f, .5f).polyphony == 1);
    CHECK(cowbell_macros(3, .5f, .5f, .5f).model == 3 && cowbell_macros(3, .5f, .5f, .5f).polyphony == 2);
    // Each body's TONE ends.
    CHECK(near(cowbell_macros(0, 0.f, .5f, .5f).structure, 0.25f, 1e-4f));
    CHECK(near(cowbell_macros(1, 1.f, .5f, .5f).structure, 0.70f, 1e-4f));
    CHECK(near(cowbell_macros(3, 1.f, .5f, .5f).structure, 0.90f, 1e-4f));
    CHECK(near(cowbell_macros(1, .5f, 1.f, 0.f).damping, 0.95f, 1e-4f));
}

void test_macros_ring_polyphony() {
    printf("\n--- macros_ring_polyphony ---\n");
    // RING no longer changes polyphony.
    for (int body = 0; body < kCowbellBodyCount; ++body)
        CHECK(cowbell_macros(body, .5f, 0.f, .5f).polyphony == cowbell_macros(body, .5f, 1.f, .5f).polyphony);
}

void test_macros_grid_in_range() {
    printf("\n--- macros_grid_in_range ---\n");
    // -1 and 11 tenths are out-of-range inputs that must clamp.
    for (int body = 0; body < kCowbellBodyCount; ++body)
        for (int a = -1; a <= 11; ++a)
            for (int b = -1; b <= 11; ++b)
                for (int c = -1; c <= 11; ++c) {
                    const CowbellParams p = cowbell_macros(body, a / 10.0f, b / 10.0f, c / 10.0f);
                    const bool finite = orpheus_is_finite(p.structure) && orpheus_is_finite(p.brightness) &&
                                        orpheus_is_finite(p.damping) && orpheus_is_finite(p.strike_gain) &&
                                        orpheus_is_finite(p.level_trim);
                    const bool ranged = p.structure >= 0.0f && p.structure <= 0.9995f &&
                                        p.brightness >= 0.0f && p.brightness <= 0.9995f &&
                                        p.damping >= 0.0f && p.damping <= 0.95f + 1e-6f;
                    const bool poly = p.polyphony == 1 || p.polyphony == 2;
                    const bool model = p.model >= 0 && p.model <= 3;
                    if (!(finite && ranged && poly && model)) {
                        CHECK(finite && ranged && poly && model);
                        printf("    at body=%d tone=%d ring=%d intensity=%d (tenths)\n", body, a, b, c);
                        return;
                    }
                }
}

void test_macros_monotonic() {
    printf("\n--- macros_monotonic ---\n");
    // Structure rises with TONE and damping rises with RING, on every body.
    for (int body = 0; body < kCowbellBodyCount; ++body)
        for (int i = 0; i < 10; ++i) {
            const float lo = i / 10.0f, hi = (i + 1) / 10.0f;
            CHECK(cowbell_macros(body, hi, .5f, .5f).structure > cowbell_macros(body, lo, .5f, .5f).structure);
            CHECK(cowbell_macros(body, .5f, hi, .5f).damping > cowbell_macros(body, .5f, lo, .5f).damping);
        }
}

void test_strike_round_trip() {
    printf("\n--- strike_round_trip ---\n");
    const struct { float v; int kind; int color; } cases[] = {
        {1.0f, 0, 0}, {0.5f, 3, 64}, {0.01f, 15, 127}};
    for (const auto& c : cases) {
        const uint32_t packed = cowbell_encode_strike(c.v, c.kind, c.color);
        CHECK(packed < (1u << 24));
        CowbellStrike s{};
        CHECK(cowbell_decode_strike(packed, &s));
        CHECK(s.kind == c.kind);
        CHECK(s.color == c.color);
        CHECK(near(s.velocity, c.v, 1.0f / 127.0f));
    }
    CowbellStrike s{};
    CHECK(!cowbell_decode_strike(0u, &s));
}

void test_strike_parity_constants() {
    printf("\n--- strike_parity_constants ---\n");
    // Kotlin asserts the same constants.
    CHECK(cowbell_encode_strike(1.0f, 0, 0) == 0x4007Fu);
    CHECK(cowbell_encode_strike(0.5f, 3, 64) == 0x4E040u);
}

void test_strike_bad_port_values() {
    printf("\n--- strike_bad_port_values ---\n");
    CHECK(cowbell_strike_from_port(std::nanf("")) == 0u);
    CHECK(cowbell_strike_from_port(-1.0f) == 0u);
    CHECK(cowbell_strike_from_port(0.0f) == 0u);
    CHECK(cowbell_strike_from_port(3e6f) == 0u);
    const uint32_t good = cowbell_encode_strike(0.5f, 3, 64);
    CHECK(cowbell_strike_from_port((float)good) == good);
}

void test_macros_boundaries() {
    printf("\n--- macros_boundaries ---\n");
    // Bad bodies clamp to the table.
    CHECK(cowbell_macros(-1, .5f, .5f, .5f).model == 2);
    CHECK(cowbell_macros(99, .5f, .5f, .5f).model == 3);
    // NaN knobs read as 0 and stay in range.
    const CowbellParams n = cowbell_macros(2, std::nanf(""), std::nanf(""), std::nanf(""));
    CHECK(near(n.structure, 0.25f, 1e-4f) && near(n.damping, 0.3156f, 1e-4f) && near(n.brightness, 0.44f, 1e-4f));
}

void test_body_port_validates() {
    printf("\n--- body_port_validates ---\n");
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    orpheus_engine_set_port(e, "org.balch.orpheus.plugins.cowbell", "body", 3.0f);
    CHECK(e->cowbell_body.load() == 3);
    for (float bad : {std::nanf(""), 99.0f, -1.0f, INFINITY, -INFINITY}) {
        orpheus_engine_set_port(e, "org.balch.orpheus.plugins.cowbell", "body", bad);
        CHECK(e->cowbell_body.load() == 3);
    }
    orpheus_engine_set_port(e, "org.balch.orpheus.plugins.cowbell", "body", 1.4f);
    CHECK(e->cowbell_body.load() == 1);
    orpheus_engine_destroy(e);
}

void test_strike_clamps() {
    printf("\n--- strike_clamps ---\n");
    CowbellStrike s{};
    CHECK(cowbell_decode_strike(cowbell_encode_strike(0.5f, 16, 200), &s));
    CHECK(s.kind == 15);
    CHECK(s.color == 127);
    CHECK((cowbell_encode_strike(0.0f, 0, 0) & 0x7Fu) == 1u);
    CHECK((cowbell_encode_strike(2.0f, 0, 0) & 0x7Fu) == 127u);
    // NaN and the negative extremes are the softest strike, the positive extremes the loudest.
    for (float v : {std::nanf(""), -INFINITY, -1e30f}) CHECK((cowbell_encode_strike(v, 0, 0) & 0x7Fu) == 1u);
    for (float v : {INFINITY, 1e30f}) CHECK((cowbell_encode_strike(v, 0, 0) & 0x7Fu) == 127u);
}

// ── UNIT_COWBELL ────────────────────────────────────────────────

constexpr const char* kUri = "org.balch.orpheus.plugins.cowbell";
const char* const kBodyNames[kCowbellBodyCount] = {"STRING", "SITAR", "METAL", "SYNTH"};

// In-place radix-2 FFT, n a power of two (copied from test_cowbell_resonator.cpp).
void fft(std::vector<std::complex<float>>& a) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const float ang = -2.0f * 3.14159265f / (float)len;
        const std::complex<float> wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            for (size_t k = 0; k < len / 2; ++k) {
                std::complex<float> u = a[i + k];
                std::complex<float> v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

// Lowest partial in [lo, hi] Hz: the first local peak at least 10% of the strongest one,
// parabolic-interpolated (copied from test_cowbell_resonator.cpp).
float lowest_partial_hz(const float* x, size_t n, float sr, float lo, float hi) {
    std::vector<std::complex<float>> a(n);
    for (size_t i = 0; i < n; ++i) {
        float w = 0.5f - 0.5f * std::cos(2.0f * 3.14159265f * (float)i / (float)(n - 1));
        a[i] = x[i] * w;
    }
    fft(a);
    const float bin_hz = sr / (float)n;
    const size_t k_lo = std::max<size_t>(1, (size_t)(lo / bin_hz));
    const size_t k_hi = (size_t)(hi / bin_hz);
    float strongest = 0.0f;
    for (size_t k = k_lo; k <= k_hi; ++k) strongest = std::max(strongest, std::abs(a[k]));
    size_t best = k_lo;
    for (size_t k = k_lo; k <= k_hi; ++k) {
        float m = std::abs(a[k]);
        if (m >= 0.1f * strongest && m >= std::abs(a[k - 1]) && m >= std::abs(a[k + 1])) {
            best = k;
            break;
        }
    }
    float m0 = std::log(std::abs(a[best - 1]) + 1e-12f);
    float m1 = std::log(std::abs(a[best]) + 1e-12f);
    float m2 = std::log(std::abs(a[best + 1]) + 1e-12f);
    float denom = m0 - 2.0f * m1 + m2;
    float delta = denom != 0.0f ? 0.5f * (m0 - m2) / denom : 0.0f;
    return ((float)best + delta) * bin_hz;
}

float peak_abs(const float* x, size_t begin, size_t end) {
    float p = 0.0f;
    for (size_t i = begin; i < end; ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

// Peak |fifth difference| over [begin, end). A ringing bell's high partials swamp a step in the
// second difference; each further difference favours the step's broadband edge.
float max_fifth_diff(const float* x, size_t begin, size_t end) {
    constexpr int kOrder = 5;
    std::vector<float> d(x + begin - kOrder, x + end);
    for (int o = 0; o < kOrder; ++o) {
        for (size_t i = d.size() - 1; i > (size_t)o; --i) d[i] -= d[i - 1];
    }
    float m = 0.0f;
    for (size_t i = kOrder; i < d.size(); ++i) m = std::max(m, std::fabs(d[i]));
    return m;
}

// Worst ratio of a 32-frame window's peak |fifth difference| to the median of the windows 2-5
// away on each side. A click spikes one window far above its neighbours.
float worst_click_ratio(const std::vector<float>& x, size_t begin, size_t end) {
    const size_t w = 32;
    std::vector<float> m;
    for (size_t s = begin; s + w <= end; s += w) m.push_back(max_fifth_diff(x.data(), s, s + w));
    float worst = 0.0f;
    for (size_t k = 5; k + 5 < m.size(); ++k) {
        float nb[8];
        int c = 0;
        for (size_t d = 2; d <= 5; ++d) {
            nb[c++] = m[k - d];
            nb[c++] = m[k + d];
        }
        std::sort(nb, nb + 8);
        const float median = 0.5f * (nb[3] + nb[4]);
        if (median > 1e-7f) worst = std::max(worst, m[k] / median);
    }
    return worst;
}

// Both outputs of a render. Polyphonic Rings alternates voices between out and aux, so a lone
// strike can sound on one side only: level and pitch checks use both.
struct Take {
    std::vector<float> left, right;
    std::vector<float> mono() const {
        std::vector<float> m(left.size());
        for (size_t i = 0; i < m.size(); ++i) m[i] = left[i] + right[i];
        return m;
    }
    float peak(size_t begin, size_t end) const {
        return std::max(peak_abs(left.data(), begin, end), peak_abs(right.data(), begin, end));
    }
    float peak() const { return peak(0, left.size()); }
};

constexpr uint32_t kRigSeed = 0x1234;

// A real engine plus a stand-alone UNIT_COWBELL, driven through the plugin's ports.
struct Rig {
    float sr;
    OrpheusEngine* e;
    std::unique_ptr<GraphUnit> u;
    int last_block = 0;  // frames in render()'s final block

    // The mechanism tests pin the METAL body (modal, one voice) at a fixed TONE; the shipped
    // default is TONE 0.3 and RING 0.1. test_default_voicing renders it with `pin_voicing` off.
    explicit Rig(float sample_rate = 48000.0f, bool pin_voicing = true)
        : sr(sample_rate), e(orpheus_engine_create(sample_rate)), u(std::make_unique<GraphUnit>()) {
        stmlib::Random::Seed(kRigSeed);  // the resonator's strings draw from the shared generator
        u->type = UNIT_COWBELL;
        unit_init(u.get(), sr);
        if (pin_voicing) {
            set("body", 2.0f);
            set("tone", 0.55f);
            set("ring", 0.5f);
            set("intensity", 0.6f);
        }
    }
    ~Rig() { orpheus_engine_destroy(e); }

    void set(const char* symbol, float v) { orpheus_engine_set_port(e, kUri, symbol, v); }
    void strike(float velocity, int kind = 0) { set("strike", (float)cowbell_encode_strike(velocity, kind, 0)); }
    void block(int n) { unit_process_cowbell(u.get(), e, n, sr); }

    // Renders `frames` in `block_size` calls, appending OUT and OUT_RIGHT to `t`.
    void render(size_t frames, Take& t, int block_size = 128) {
        for (size_t pos = 0; pos < frames; pos += (size_t)block_size) {
            const int n = (int)std::min<size_t>((size_t)block_size, frames - pos);
            block(n);
            last_block = n;
            const float* l = u->output_buffers[OPORT_OUT];
            const float* r = u->output_buffers[OPORT_OUT_RIGHT];
            t.left.insert(t.left.end(), l, l + n);
            t.right.insert(t.right.end(), r, r + n);
        }
    }

    float block_peak(int n) const {
        return std::max(peak_abs(u->output_buffers[OPORT_OUT], 0, (size_t)n),
                        peak_abs(u->output_buffers[OPORT_OUT_RIGHT], 0, (size_t)n));
    }

    bool slots_empty() const {
        for (const auto& s : e->cowbell_strike_slots) {
            if (s.load() != 0u) return false;
        }
        return true;
    }

    float last_viz() const {
        const VizRing& ring = e->viz_rings[VIZ_COWBELL_OUT];
        const uint32_t wc = ring.write_count.load();
        return wc ? ring.buf[(wc - 1) % VizRing::kVizBufSize] : -1.0f;
    }
};

float cents(float hz, float ref) { return 1200.0f * std::log2(hz / ref); }

// Strike once at `octave` and return the ring-out's fundamental.
float measure_f0(float sr, int octave, float expected) {
    Rig r(sr);
    r.e->pulsar_root_note.store(2);
    r.set("octave", (float)octave);
    r.strike(1.0f);
    Take t;
    r.render((size_t)sr, t);
    const std::vector<float> m = t.mono();
    return lowest_partial_hz(m.data() + (size_t)(0.05f * sr), 16384, sr, 0.6f * expected,
                             3.0f * expected);
}

void test_unit_strike_rings_at_root() {
    printf("\n--- unit_strike_rings_at_root ---\n");
    for (float sr : {44100.0f, 48000.0f}) {
        const float f0 = measure_f0(sr, 0, 587.33f);
        printf("  sr=%.0f f0=%.3f Hz (%.2f cents from 587.33)\n", sr, f0, cents(f0, 587.33f));
        CHECK(std::fabs(cents(f0, 587.33f)) <= 5.0f);
    }
}

void test_unit_octave_up() {
    printf("\n--- unit_octave_up ---\n");
    const float up = measure_f0(48000.0f, 1, 1174.66f);
    printf("  octave 1: f0=%.3f Hz (%.2f cents)\n", up, cents(up, 1174.66f));
    CHECK(std::fabs(cents(up, 1174.66f)) <= 5.0f);
    const float clamped = measure_f0(48000.0f, 7, 2349.32f);
    printf("  octave 7 (clamps to 2): f0=%.3f Hz (%.2f cents)\n", clamped, cents(clamped, 2349.32f));
    CHECK(std::fabs(cents(clamped, 2349.32f)) <= 5.0f);
}

float struck_peak(float velocity) {
    Rig r;
    r.strike(velocity);
    Take t;
    r.render(4800, t);
    return t.peak();
}

void test_unit_louder_strike_louder() {
    printf("\n--- unit_louder_strike_louder ---\n");
    const float loud = struck_peak(1.0f);
    const float soft = struck_peak(0.3f);
    printf("  peak 1.0=%.4f 0.3=%.4f (ratio %.2f)\n", loud, soft, soft > 0 ? loud / soft : 0.0f);
    CHECK(soft > 1e-4f);
    CHECK(loud > 2.0f * soft);
}

void test_unit_slots_keep_loudest() {
    printf("\n--- unit_slots_keep_loudest ---\n");
    Rig r;
    std::vector<float> vels;
    for (int i = 1; i <= 20; ++i) vels.push_back(0.05f * (float)i);
    std::mt19937 rng(7);
    std::shuffle(vels.begin(), vels.end(), rng);
    for (float v : vels) cowbell_push_strike(r.e, cowbell_encode_strike(v, 0, 0));
    r.block(128);
    std::vector<float> sorted = vels;
    std::sort(sorted.begin(), sorted.end(), std::greater<float>());
    const float eighth = (float)(cowbell_encode_strike(sorted[7], 0, 0) & 0x7Fu) / 127.0f;
    const CowbellUnitState& st = r.e->cowbell_state;
    printf("  drained %d, min velocity %.4f (8th highest %.4f)\n", st.last_drain_count,
           st.last_drain_min_velocity, eighth);
    CHECK(st.last_drain_count == 8);
    CHECK(st.last_drain_min_velocity >= eighth - 1e-6f);
    CHECK(r.slots_empty());
}

void test_unit_slots_two_threads() {
    printf("\n--- unit_slots_two_threads ---\n");
    Rig r;
    // Per-word tallies, indexed by the word's low 18 bits (the valid bit dropped). Each pusher
    // owns its arrays; they are summed after the join.
    constexpr uint32_t kWordMask = kCowbellStrikeValid - 1;
    struct Tally {
        std::vector<uint32_t> pushed, dropped, overwritten;
        uint32_t pushes = 0;
        Tally() : pushed(kWordMask + 1), dropped(kWordMask + 1), overwritten(kWordMask + 1) {}
    };
    Tally tally[2];
    std::vector<uint32_t> drained(kWordMask + 1);
    std::atomic<bool> stop{false};
    std::atomic<bool> draining{false};
    std::atomic<int> started{0};
    std::atomic<uint32_t> overlapped[2] = {{0}, {0}};  // pushes made while drains were live
    auto pusher = [&](uint32_t id) {
        Tally& t = tally[id];
        uint32_t seq = 0;
        started.fetch_add(1);
        while (!stop.load(std::memory_order_relaxed)) {
            // kind = thread, color = seq mod 128, vel7 from the higher bits: 16256 distinct words
            // per thread before they repeat.
            const uint32_t w = kCowbellStrikeValid | (id << 14) | ((seq & 127u) << 7) | (1u + (seq >> 7) % 127u);
            ++t.pushed[w & kWordMask];
            uint32_t lost = 0;
            switch (cowbell_push_strike_result(r.e, w, &lost)) {
                case COWBELL_PUSH_PLACED: break;
                case COWBELL_PUSH_REPLACED: ++t.overwritten[lost & kWordMask]; break;
                case COWBELL_PUSH_DROPPED: ++t.dropped[w & kWordMask]; break;
            }
            ++seq;
            if (draining.load(std::memory_order_relaxed)) overlapped[id].fetch_add(1, std::memory_order_relaxed);
        }
        t.pushes = seq;
    };
    std::thread a(pusher, 0u), b(pusher, 1u);
    while (started.load() < 2) std::this_thread::yield();

    uint64_t drained_total = 0;
    int busy_blocks = 0, max_drain = 0;
    bool finite = true;
    auto drain_block = [&] {
        r.block(128);
        const CowbellUnitState& st = r.e->cowbell_state;
        drained_total += (uint64_t)st.last_drain_count;
        busy_blocks += st.last_drain_count > 0;
        max_drain = std::max(max_drain, st.last_drain_count);
        for (int i = 0; i < st.last_drain_count; ++i) ++drained[st.last_drained_words[i] & kWordMask];
        for (int i = 0; i < 128; ++i) finite = finite && std::isfinite(r.u->output_buffers[OPORT_OUT][i]);
    };
    // Drain until each pusher has measurably raced live drains and every push outcome has
    // happened, or 10 s pass. A starved box takes longer instead of failing a fixed floor.
    constexpr uint32_t kMinOverlap = 2000;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    draining.store(true);
    int blocks = 0;
    for (;;) {
        drain_block();
        ++blocks;
        const bool raced = overlapped[0].load() >= kMinOverlap && overlapped[1].load() >= kMinOverlap;
        const bool all_outcomes = r.e->cowbell_push_placed.load() > 0 && r.e->cowbell_push_replaced.load() > 0 &&
                                  r.e->cowbell_push_dropped.load() > 0;
        if ((blocks >= 200 && raced && all_outcomes) || std::chrono::steady_clock::now() > deadline) break;
    }
    draining.store(false);
    stop.store(true);
    a.join();
    b.join();
    drain_block();  // whatever the pushers left behind

    // Every push of a word ends exactly once: drained, overwritten by a louder one, or dropped.
    int unbalanced = 0;
    for (uint32_t w = 0; w <= kWordMask; ++w) {
        const uint32_t pushed = tally[0].pushed[w] + tally[1].pushed[w];
        const uint32_t ended = drained[w] + tally[0].overwritten[w] + tally[1].overwritten[w] +
                               tally[0].dropped[w] + tally[1].dropped[w];
        unbalanced += pushed != ended;
    }
    const uint64_t placed = r.e->cowbell_push_placed.load();
    const uint64_t replaced = r.e->cowbell_push_replaced.load();
    const uint64_t dropped = r.e->cowbell_push_dropped.load();
    const uint64_t total = (uint64_t)tally[0].pushes + tally[1].pushes;
    printf("  %llu pushes: placed %llu, replaced %llu, dropped %llu; drained %llu over %d busy blocks "
           "of %d (largest %d), %d words unbalanced; raced live drains %u + %u\n", (unsigned long long)total,
           (unsigned long long)placed, (unsigned long long)replaced, (unsigned long long)dropped,
           (unsigned long long)drained_total, busy_blocks, blocks, max_drain, unbalanced, overlapped[0].load(),
           overlapped[1].load());
    CHECK(placed + replaced + dropped == total);
    CHECK(drained_total == placed);  // a replace takes a queued word's slot, so only placements add one
    CHECK(unbalanced == 0);
    // The pushers really ran against the drain; a single core can't overlap them.
    if (std::thread::hardware_concurrency() >= 2) {
        CHECK(overlapped[0].load() >= kMinOverlap && overlapped[1].load() >= kMinOverlap);
    } else {
        printf("  single core: overlap check skipped\n");
    }
    CHECK(busy_blocks > 0);
    CHECK(placed > 0 && replaced > 0 && dropped > 0);
    CHECK(max_drain <= 8);
    CHECK(finite);
    CHECK(r.slots_empty());
}

// A graph swap drops strikes queued while no cowbell unit ran; later ones still play.
void test_unit_graph_swap_drops_stale_strikes() {
    printf("\n--- unit_graph_swap_drops_stale_strikes ---\n");
    Rig r;
    std::vector<float> stereo(256);
    orpheus_engine_process(r.e, stereo.data(), 128);
    for (int i = 0; i < 5; ++i) r.strike(0.5f);
    r.e->cowbell_state.pending_count = 3;  // as if GRID held strikes back
    CHECK(!r.slots_empty());
    // A hand-built graph with no cowbell unit swaps in the way orpheus_engine_load_patch does it.
    r.e->graph_generation.fetch_add(1, std::memory_order_release);
    OrpheusGraph* old = r.e->graph.exchange(create_minimal_graph(0, r.sr), std::memory_order_acq_rel);
    CHECK(old == nullptr);  // the engine frees the new one
    orpheus_engine_process(r.e, stereo.data(), 128);
    CHECK(r.slots_empty());
    CHECK(r.e->cowbell_state.pending_count == 0);

    r.strike(1.0f);
    orpheus_engine_process(r.e, stereo.data(), 128);
    CHECK(!r.slots_empty());
    r.block(128);
    CHECK(r.e->cowbell_state.last_drain_count == 1);
}

void test_unit_level_zero_silent() {
    printf("\n--- unit_level_zero_silent ---\n");
    Rig r;
    r.set("level", 0.0f);
    r.strike(1.0f);
    Take t;
    r.render(24000, t);
    CHECK(t.peak() == 0.0f);
}

void test_unit_idle_skips_and_wakes() {
    printf("\n--- unit_idle_skips_and_wakes ---\n");
    Rig r;
    Take t;
    r.render(48000, t);
    CHECK(r.e->cowbell_state.idle);
    CHECK(t.peak() == 0.0f);

    r.strike(1.0f);
    r.block(128);
    const float woke = r.block_peak(128);
    printf("  first block after the strike: peak %.4f\n", woke);
    CHECK(!r.e->cowbell_state.idle);
    CHECK(woke > 1e-3f);

    // The ring-out decays back to idle, and idle output is exactly zero again.
    int blocks = 0;
    while (!r.e->cowbell_state.idle && blocks < 20 * 375) {
        r.block(128);
        ++blocks;
    }
    printf("  idle again after %.2f s\n", blocks * 128 / 48000.0f);
    CHECK(r.e->cowbell_state.idle);
    r.block(128);
    CHECK(r.block_peak(128) == 0.0f);
    CHECK(r.last_viz() == 0.0f);
}

void test_unit_disabled_zeroes() {
    printf("\n--- unit_disabled_zeroes ---\n");
    Rig r;
    r.strike(1.0f);
    r.block(128);
    CHECK(r.last_viz() > 1e-3f);  // ringing before it is disabled
    r.u->enabled = false;
    for (int p = 0; p < kMaxOutputPorts; ++p) std::fill_n(r.u->output_buffers[p], 128, 1.0f);
    r.strike(1.0f);
    const uint32_t writes = r.e->viz_rings[VIZ_COWBELL_OUT].write_count.load();
    r.block(128);
    CHECK(r.block_peak(128) == 0.0f);
    CHECK(r.e->viz_rings[VIZ_COWBELL_OUT].write_count.load() == writes + 1);
    CHECK(r.last_viz() == 0.0f);
}

void test_unit_bad_ports() {
    printf("\n--- unit_bad_ports ---\n");
    Rig r;
    r.set("strike", std::nanf(""));
    r.set("strike", -1.0f);
    r.set("strike", 0.0f);
    CHECK(r.slots_empty());

    r.set("level", std::nanf(""));
    r.set("tone", INFINITY);
    CHECK(r.e->cowbell_level.load() == 0.7f);
    CHECK(r.e->cowbell_tone.load() == 0.55f);

    r.set("structure_offset", 5.0f);
    r.set("position_offset", -5.0f);
    r.set("bend", 3.0f);
    r.set("octave", -9.0f);
    CHECK(r.e->cowbell_structure_offset.load() == 0.4f);
    CHECK(r.e->cowbell_position_offset.load() == -0.45f);
    CHECK(r.e->cowbell_bend.load() == 1.0f);
    CHECK(r.e->cowbell_octave.load() == -2);

    r.set("gate", 1.0f);
    r.set("grid", 0.4f);
    CHECK(r.e->cowbell_gate.load());
    CHECK(!r.e->cowbell_grid.load());

    Take t;
    r.render(1280, t);
    bool finite = true;
    for (float v : t.mono()) finite = finite && std::isfinite(v);
    CHECK(finite);
    CHECK(r.e->cowbell_state.last_drain_count == 0);
}

// Strike with a long RING, then sweep (or hold) both offsets over 1 s, changing them every
// `step` frames.
std::vector<float> render_offset_sweep(bool sweep, int step) {
    Rig r;
    r.set("ring", 0.9f);
    r.strike(1.0f);
    Take take;
    const int steps = 48000 / step;
    for (int i = 0; i < steps; ++i) {
        const float t = (float)i / (float)(steps - 1);
        if (sweep) {
            r.set("structure_offset", -0.4f + 0.8f * t);
            r.set("position_offset", -0.45f + 0.9f * t);
        }
        r.render((size_t)step, take);
    }
    return take.mono();
}

void test_unit_offsets_sweep_glides() {
    printf("\n--- unit_offsets_sweep_glides ---\n");
    const std::vector<float> swept = render_offset_sweep(true, 800);  // 60 Hz at 48 kHz
    const std::vector<float> fine = render_offset_sweep(true, 32);
    const std::vector<float> held = render_offset_sweep(false, 800);
    const size_t n = swept.size();
    const float ratio = worst_click_ratio(swept, 4800, n);
    const float fine_ratio = worst_click_ratio(fine, 4800, n);
    const float held_ratio = worst_click_ratio(held, 4800, n);
    const float tail = peak_abs(swept.data(), n - 4800, n);
    printf("  click ratio 60 Hz=%.2f per-32=%.2f held=%.2f, last 100 ms peak %.4f\n", ratio,
           fine_ratio, held_ratio, tail);
    CHECK(tail > 1e-3f);
    CHECK(ratio < 4.0f);
    CHECK(ratio <= 1.25f * fine_ratio);  // the 60 Hz steps add nothing a near-continuous sweep lacks

    // The detector catches a step a tenth of the local peak.
    std::vector<float> clicked = swept;
    const size_t at = n / 2;
    const float jump = 0.1f * peak_abs(swept.data(), at - 2400, at + 2400);
    for (size_t i = at; i < n; ++i) clicked[i] += jump;
    const float clicked_ratio = worst_click_ratio(clicked, 4800, n);
    printf("  with a %.4f step: click ratio %.2f\n", jump, clicked_ratio);
    CHECK(clicked_ratio >= 4.0f);
}

// ── SYNTH gate and bend ─────────────────────────────────────────

float rms(const std::vector<float>& x, size_t begin, size_t end) {
    double sum = 0.0;
    for (size_t i = begin; i < end; ++i) sum += (double)x[i] * x[i];
    return end > begin ? (float)std::sqrt(sum / (double)(end - begin)) : 0.0f;
}

bool all_finite(const std::vector<float>& x) {
    for (float v : x) {
        if (!orpheus_is_finite(v)) return false;  // -ffast-math folds std::isfinite
    }
    return true;
}

// The bare strike rings, then falls quiet on its own.
void test_tap_releases() {
    printf("\n--- tap_releases ---\n");
    Rig r;
    r.set("ring", 0.5f);
    r.strike(1.0f);
    Take t;
    r.render(3 * 48000, t);
    const float head = t.peak(0, 4800);
    const float tail = t.peak(t.left.size() - 4800, t.left.size());
    printf("  first 100 ms peak %.4f, last 100 ms peak %.2e (%.1f dBFS)\n", head, tail,
           20.0f * std::log10(tail + 1e-12f));
    CHECK(head > 1e-3f);
    CHECK(tail < 1e-3f * kCowbellOutputGain);
}

// Gate on for 2 s, then off, at RING 0.3: at 0.5 a held note's fundamental still rings near
// -36 dBFS 2 s on (about 8 dB/s), so the noise bed's buffer is also checked directly.
void test_synth_gate_sustains() {
    printf("\n--- synth_gate_sustains ---\n");
    Rig r;
    r.set("ring", 0.3f);
    r.set("gate", 1.0f);
    Take t;
    r.render(2 * 48000, t);
    r.set("gate", 0.0f);
    r.render(4800, t);
    const float bed_after = peak_abs(r.e->cowbell_input_buf, 0, (size_t)r.last_block);  // 100 ms on
    r.render(48000 * 2, t);
    const std::vector<float> m = t.mono();
    const float early = rms(m, 9600, 33600);   // 0.2-0.7 s
    const float late = rms(m, 72000, 96000);   // 1.5-2.0 s
    const float after = t.peak(4 * 48000, 4 * 48000 + 4800);  // 2 s after release
    printf("  RMS 0.2-0.7 s %.4f, 1.5-2.0 s %.4f (ratio %.2f); 2 s after release %.1f dBFS; "
           "noise bed 100 ms after release %.2e\n", early, late, early > 0 ? late / early : 0.0f,
           20.0f * std::log10(after + 1e-12f), bed_after);
    CHECK(bed_after == 0.0f);
    CHECK(early > 1e-3f);
    CHECK(late > 0.3f * early);
    CHECK(after < 0.00316f);
    CHECK(all_finite(m));

    // At full INTENSITY a held note stays under Rings' limiter at any RING: its output reaches
    // about 0.68 (0.47 after LEVEL 0.7, before the makeup gain) when it engages. The ceiling would
    // hide that in the output, so this reads the level before it.
    for (float ring : {0.3f, 0.6f, 0.8f, 1.0f}) {
        Rig h;
        h.set("intensity", 1.0f);
        h.set("ring", ring);
        h.set("gate", 1.0f);
        Take ht;
        h.render(48000, ht);
        float steady = 0.0f;
        for (int b = 0; b < 2 * 48000 / 128; ++b) {
            h.block(128);
            steady = std::max(steady, h.e->cowbell_state.last_pre_ceiling_peak);
        }
        printf("  INTENSITY 1, RING %.1f: held pre-ceiling peak 1-3 s %.4f\n", ring, steady);
        CHECK(steady < 0.42f * kCowbellOutputGain);
    }
}

// kCeiling in orpheus_unit_cowbell.cpp, plus the rounding of its gain x sample.
constexpr float kTestCeiling = 0.98f + 1e-5f;

// A held SYNTH note on every body at full INTENSITY, at the shortest, middle and longest RING:
// it sounds, stays under the ceiling and, once released, only decays to under -50 dBFS within
// 8 s. METAL at RING 1 gets 9 s: it reads -48.5 dBFS at 8 s and crosses -50 at 8.4 s.
void test_synth_held_note_every_body() {
    printf("\n--- synth_held_note_every_body ---\n");
    const char* names[4] = {"STRING", "SITAR", "METAL", "SYNTH"};
    for (int body = 0; body < kCowbellBodyCount; ++body) {
        for (float ring : {0.0f, 0.5f, 1.0f}) {
            Rig r(48000.0f, false);
            r.set("body", (float)body);
            r.set("intensity", 1.0f);
            r.set("ring", ring);
            r.set("gate", 1.0f);
            Take held;
            r.render(2 * 48000, held);
            r.set("gate", 0.0f);
            Take rel;
            const int wait_seconds = (body == 2 && ring == 1.0f) ? 9 : 8;
            r.render((size_t)wait_seconds * 48000, rel);
            const size_t n = rel.left.size();
            const float sounding = held.peak(24000, held.left.size());  // 0.5-2 s
            const float top = std::max(held.peak(), rel.peak());
            const float soon = rel.peak(4800, 9600);  // 0.1-0.2 s after release
            const float quiet = rel.peak(n - 4800, n);
            printf("  %-6s RING %.1f: held peak %.4f (%.1f dBFS), highest %.4f, after release %.1f dBFS at 0.1 s, "
                   "%.1f at %d s\n", names[body], ring, sounding, 20.0f * std::log10(sounding + 1e-12f), top,
                   20.0f * std::log10(soon + 1e-12f), 20.0f * std::log10(quiet + 1e-12f), wait_seconds);
            CHECK(sounding > 0.001f);  // above -60 dBFS
            CHECK(top <= kTestCeiling);
            CHECK(quiet <= soon);
            CHECK(quiet < 0.00316f);
            CHECK(all_finite(held.mono()) && all_finite(rel.mono()));
        }
    }
}

// Ring-out fundamental over 16384 frames from `begin`, searched around `expected`.
float f0_at(const Take& t, size_t begin, float expected) {
    const std::vector<float> m = t.mono();
    return lowest_partial_hz(m.data() + begin, 16384, 48000.0f, 0.6f * expected, 3.0f * expected);
}

void test_bend_bends_ringing_voices() {
    printf("\n--- bend_bends_ringing_voices ---\n");
    Rig r;
    r.e->pulsar_root_note.store(2);
    r.set("ring", 0.7f);
    r.strike(1.0f);
    Take t;
    r.render(4800, t);
    r.set("bend", 0.5f);
    r.render(4800 + 16384, t);
    const float bent_expected = 587.33f * std::pow(2.0f, 15.0f / 12.0f);
    const float bent = f0_at(t, 9600, bent_expected);
    r.set("bend", 0.0f);
    const size_t back_at = t.left.size() + 4800;
    r.render(4800 + 16384, t);
    const float back = f0_at(t, back_at, 587.33f);
    printf("  bend 0.5: f0 %.2f Hz (%.2f cents from %.2f); back to 0: %.2f Hz (%.2f cents)\n", bent,
           cents(bent, bent_expected), bent_expected, back, cents(back, 587.33f));
    CHECK(std::fabs(cents(bent, bent_expected)) <= 10.0f);
    CHECK(std::fabs(cents(back, 587.33f)) <= 10.0f);
}

// Bend -1 to 1 to 0 over 1 s after a long-ringing strike, stepping every `step` frames. With
// `held`, a SYNTH note is held through it instead.
std::vector<float> render_bend_sweep(int step, bool held = false) {
    Rig r;
    r.set("ring", 0.9f);
    r.set("bend", -1.0f);
    if (held) {
        r.set("gate", 1.0f);
    } else {
        r.strike(1.0f);
    }
    Take take;
    const int steps = 48000 / step;
    for (int i = 0; i < steps; ++i) {
        const float t = (float)i / (float)(steps - 1);
        r.set("bend", t < 0.5f ? -1.0f + 4.0f * t : 2.0f - 2.0f * t);
        r.render((size_t)step, take);
    }
    return take.mono();
}

void test_bend_sweep_glides() {
    printf("\n--- bend_sweep_glides ---\n");
    const std::vector<float> swept = render_bend_sweep(800);  // 60 Hz at 48 kHz
    const std::vector<float> fine = render_bend_sweep(32);
    const size_t n = swept.size();
    const float ratio = worst_click_ratio(swept, 4800, n);
    const float fine_ratio = worst_click_ratio(fine, 4800, n);
    const float tail = peak_abs(swept.data(), n - 4800, n);
    printf("  click ratio 60 Hz=%.2f per-32=%.2f, last 100 ms peak %.4f\n", ratio, fine_ratio, tail);
    CHECK(all_finite(swept));
    CHECK(tail > 1e-3f);
    CHECK(ratio < 4.0f);
    CHECK(ratio <= 1.25f * fine_ratio);

    const std::vector<float> held = render_bend_sweep(800, true);
    const std::vector<float> held_fine = render_bend_sweep(32, true);
    const float held_ratio = worst_click_ratio(held, 4800, held.size());
    const float held_fine_ratio = worst_click_ratio(held_fine, 4800, held_fine.size());
    printf("  held: click ratio 60 Hz=%.2f per-32=%.2f, peak %.4f\n", held_ratio, held_fine_ratio,
           peak_abs(held.data(), 4800, held.size()));
    CHECK(all_finite(held));
    CHECK(peak_abs(held.data(), held.size() - 4800, held.size()) > 1e-3f);
    // The held noise bed's peaks land in some 32-frame windows and not others, so only the
    // relative check applies: the 60 Hz steps add nothing a near-continuous sweep lacks.
    CHECK(held_ratio <= 1.25f * held_fine_ratio);
    CHECK(held_ratio < 4.0f);

    // The highest note bent fully up (MIDI 143 uncapped) and the lowest fully down.
    for (float bend : {1.0f, -1.0f}) {
        Rig r;
        r.e->pulsar_root_note.store(bend > 0 ? 11 : 0);
        r.set("octave", bend > 0 ? 2.0f : -2.0f);
        r.set("bend", bend);
        r.set("gate", 1.0f);
        r.strike(1.0f);
        Take t;
        r.render(24000, t);
        printf("  bend %+.0f at the octave extreme: peak %.4f\n", bend, t.peak());
        CHECK(all_finite(t.mono()));
        CHECK(t.peak() > 1e-3f);  // the top is capped below Nyquist, so it still sounds
        CHECK(t.peak() <= 1.0f);
    }
}

// ── Bend to scale ───────────────────────────────────────────────

constexpr int kMajor = 1;  // kPulsarScales[1]

// Holds a SYNTH note on the bend for `blocks` and returns the resonator's bend in semitones.
float held_bend(Rig& r, int blocks) {
    Take t;
    for (int i = 0; i < blocks; ++i) r.render(128, t);
    return r.e->cowbell_reso.bend_semitones();
}

void test_snap_to_scale_major() {
    printf("\n--- snap_to_scale_major ---\n");
    const PulsarScale& major = kPulsarScales[kMajor];
    CHECK(snap_to_scale(1.3f, major, 100.0f) == 2.0f);
    CHECK(snap_to_scale(0.9f, major, 100.0f) == 0.0f);
    CHECK(snap_to_scale(-0.6f, major, 100.0f) == -1.0f);
    CHECK(snap_to_scale(11.6f, major, 100.0f) == 12.0f);
    CHECK(snap_to_scale(13.4f, major, 100.0f) == 14.0f);
    // The cap wins over the nearest degree: 13.4 under a cap of 13 lands on 12.
    CHECK(snap_to_scale(13.4f, major, 13.0f) == 12.0f);
}

void test_bend_scale_snaps_held_note() {
    printf("\n--- bend_scale_snaps_held_note ---\n");
    // Bend 0.05 -> curve 0.05125 -> 1.23 st free: nearest Major degree is 2.
    Rig r;
    r.e->pulsar_root_note.store(0);
    r.e->pulsar_scale_index.store(kMajor);
    r.e->pulsar_playing.store(1);
    r.set("gate", 1.0f);
    r.set("bend", 0.05f);
    const float snapped = held_bend(r, 375);
    printf("  free 1.23 st -> %.3f st\n", snapped);
    CHECK(std::fabs(snapped - 2.0f) <= 0.05f);

    // The same note with Pulsar stopped bends free.
    Rig f;
    f.e->pulsar_root_note.store(0);
    f.e->pulsar_scale_index.store(kMajor);
    f.set("gate", 1.0f);
    f.set("bend", 0.05f);
    const float freebend = held_bend(f, 375);
    CHECK(std::fabs(freebend - 1.23f) <= 0.05f);
}

Take bend_take(bool playing, float bend_to) {
    Rig r;
    r.e->pulsar_root_note.store(2);
    r.e->pulsar_scale_index.store(kMajor);
    r.e->pulsar_playing.store(playing ? 1 : 0);
    r.set("ring", 0.7f);
    r.strike(1.0f);
    Take t;
    r.render(4800, t);
    r.set("bend", bend_to);
    r.render(9600, t);
    return t;
}

void test_bend_scale_free_while_pulsar_stopped() {
    printf("\n--- bend_scale_free_while_pulsar_stopped ---\n");
    const Take stopped = bend_take(false, 0.05f);
    const Take playing = bend_take(true, 0.05f);
    CHECK(playing.left != stopped.left);
}

void test_bend_scale_respects_note_cap() {
    printf("\n--- bend_scale_respects_note_cap ---\n");
    Rig r;
    r.e->pulsar_root_note.store(11);  // B
    r.e->pulsar_scale_index.store(kMajor);
    r.e->pulsar_playing.store(1);
    r.set("octave", 2.0f);
    r.set("gate", 1.0f);
    r.set("bend", 1.0f);
    const float bend = held_bend(r, 375);
    const float note = 72.0f + 11.0f + 24.0f;
    const int offset = (int)std::lround(bend);
    printf("  bend %.3f st, note %.0f, top %.2f\n", bend, note, note + bend);
    CHECK(note + bend <= 120.0f + 0.01f);
    CHECK(std::fabs(bend - (float)offset) < 0.05f);
    CHECK(((offset % 12) + 12) % 12 == 0);  // 12: Major has no degree 13 under the cap
}

// A long-ringing strike with the bend swept 0 to 1 over 0.5 s: snapped while Pulsar plays, else free.
std::vector<float> scale_sweep(bool playing) {
    Rig r;
    r.e->pulsar_root_note.store(0);
    r.e->pulsar_scale_index.store(kMajor);
    r.e->pulsar_playing.store(playing ? 1 : 0);
    r.set("ring", 0.9f);
    r.strike(1.0f);
    Take t;
    const int blocks = 188;  // 0.5 s in 128-frame blocks
    for (int i = 0; i < blocks; ++i) {
        r.set("bend", (float)i / (float)(blocks - 1));
        r.render(128, t);
    }
    return t.mono();
}

void test_bend_scale_glide_has_no_click() {
    printf("\n--- bend_scale_glide_has_no_click ---\n");
    const std::vector<float> snapped = scale_sweep(true);
    const std::vector<float> free = scale_sweep(false);
    const float ratio = worst_click_ratio(snapped, 4800, snapped.size());
    const float free_ratio = worst_click_ratio(free, 4800, free.size());
    printf("  click ratio %.2f (free %.2f)\n", ratio, free_ratio);
    CHECK(all_finite(snapped));
    CHECK(ratio < 4.0f);  // the bound the free-bend sweep keeps
    CHECK(ratio <= 1.25f * free_ratio);
}

// A body change mid-ring waits for the next strike: the ringing bell keeps its model, unbroken.
void test_body_change_waits_for_strike() {
    printf("\n--- body_change_waits_for_strike ---\n");
    Rig r;
    r.strike(1.0f);
    Take warm;
    r.render(2400, warm);
    CHECK(r.e->cowbell_reso.model() == 0);
    r.set("body", 3.0f);
    bool silent = false;
    bool kept_model = true;
    for (int i = 0; i < 20; ++i) {
        r.block(128);
        silent = silent || r.block_peak(128) < 1e-6f;
        kept_model = kept_model && r.e->cowbell_reso.model() == 0;
    }
    CHECK(!silent);
    CHECK(kept_model);
    r.strike(0.8f);
    r.block(128);
    CHECK(r.e->cowbell_reso.model() == 3);
    CHECK(r.block_peak(128) > 1e-6f);
}

// A tap on `from` at a voicing; `to` is picked 50 ms in unless it is negative. Ends 550 ms in.
Take ring_out_picking(Rig& r, int from, int to, float ring) {
    r.e->pulsar_root_note.store(0);
    r.set("body", (float)from);
    r.set("tone", 0.5f);
    r.set("ring", ring);
    r.set("intensity", 0.7f);
    r.strike(0.9f);
    Take t;
    r.render(2400, t);
    if (to >= 0) r.set("body", (float)to);
    r.render(24000, t);
    return t;
}

// Picking a body mid-ring changes nothing until the next strike: left and right stay bit-identical
// to a bell left alone, from every body to every other, and the strike then brings the new model.
void test_body_change_mid_ring_identical() {
    printf("\n--- body_change_mid_ring_identical ---\n");
    for (int from = 0; from < kCowbellBodyCount; ++from) {
        for (int to = 0; to < kCowbellBodyCount; ++to) {
            if (to == from) continue;
            Rig alone(48000.0f, false);
            const Take same = ring_out_picking(alone, from, -1, 0.5f);
            Rig picked(48000.0f, false);
            const Take t = ring_out_picking(picked, from, to, 0.5f);
            const bool identical = t.left == same.left && t.right == same.right;
            if (!identical) {
                size_t first = 0;
                while (first < t.left.size() && t.left[first] == same.left[first] && t.right[first] == same.right[first]) ++first;
                const float ring_db = 20.0f * std::log10((t.peak(first, t.left.size()) + 1e-12f) /
                                                         (same.peak(first, same.left.size()) + 1e-12f));
                printf("  %s to %s: differs from frame %zu, tail %+.1f dB against the unchanged bell\n",
                       kBodyNames[from], kBodyNames[to], first, ring_db);
            }
            CHECK(t.peak() > 1e-3f);
            CHECK(identical);
            picked.strike(0.9f);
            picked.block(128);
            CHECK(picked.e->cowbell_reso.model() == cowbell_macros(to, 0.5f, 0.5f, 0.7f).model);
        }
    }
}

// The worst case for a tail: RING 1 and SITAR's damping of 0.95, which an FM or modal voice would
// hold as a drone. A body picked mid-ring leaves the tap to fall under -50 dBFS within 8 s.
void test_body_change_ring_top_falls_quiet() {
    printf("\n--- body_change_ring_top_falls_quiet ---\n");
    for (int from : {0, 2, 3}) {
        Rig r(48000.0f, false);
        Take t = ring_out_picking(r, from, 1, 1.0f);
        r.render(8 * 48000 - t.left.size(), t);
        const float tail = t.peak(t.left.size() - 4800, t.left.size());
        printf("  %-6s to SITAR at RING 1: %.1f dBFS at 8 s\n", kBodyNames[from], 20.0f * std::log10(tail + 1e-12f));
        CHECK(tail < 0.00316f);
    }
}

// What ends the wait: the gate's rising edge, or the bell going idle. A held gate does not.
void test_body_change_adopted_by_gate_or_idle() {
    printf("\n--- body_change_adopted_by_gate_or_idle ---\n");
    {
        Rig r(48000.0f, false);
        ring_out_picking(r, 2, 3, 0.5f);
        CHECK(r.e->cowbell_reso.model() == 0);
        r.set("gate", 1.0f);
        r.block(128);
        CHECK(r.e->cowbell_reso.model() == 3);
        r.set("body", 0.0f);
        for (int i = 0; i < 20; ++i) r.block(128);
        CHECK(r.e->cowbell_reso.model() == 3);  // still held, no new edge
    }
    {
        Rig r(48000.0f, false);
        r.set("ring", 0.0f);
        r.strike(0.9f);
        Take t;
        r.render(4800, t);
        r.set("body", 3.0f);
        CHECK(r.e->cowbell_state.sounding_body == 2);
        for (int i = 0; i < 48000 * 10 / 128 && !r.e->cowbell_state.idle; ++i) r.block(128);
        CHECK(r.e->cowbell_state.idle);
        r.block(128);
        CHECK(r.e->cowbell_state.sounding_body == 3);
    }
}

// Picking a louder body and striking at once: the hit reaches its full level in its first block,
// not at the end of a ramp up from the old body's trim.
void test_body_change_hit_attacks_at_full_level() {
    printf("\n--- body_change_hit_attacks_at_full_level ---\n");
    Rig fresh(48000.0f, false);
    fresh.set("body", 0.0f);
    fresh.set("tone", 0.5f);
    fresh.set("ring", 0.5f);
    fresh.set("intensity", 0.7f);
    fresh.strike(0.9f);
    fresh.block(128);
    const float want = fresh.e->cowbell_state.last_pre_ceiling_peak;
    Rig r(48000.0f, false);
    ring_out_picking(r, 2, 0, 0.5f);
    r.strike(0.9f);
    r.block(128);
    const float got = r.e->cowbell_state.last_pre_ceiling_peak;
    printf("  METAL to STRING: first block %.4f, fresh STRING %.4f (%+.1f dB)\n", got, want,
           20.0f * std::log10((got + 1e-12f) / (want + 1e-12f)));
    CHECK(want > 1e-3f);
    CHECK(got > want);  // measures 1.47 against 0.88; ramping up from METAL's trim reads 0.70
}

// Eight strikes and the gate's rising edge in one block.
void test_full_block_of_hits() {
    printf("\n--- full_block_of_hits ---\n");
    Rig r;
    for (int i = 0; i < 8; ++i) r.strike(0.3f + 0.08f * (float)i);
    r.set("gate", 1.0f);
    Take t;
    r.render(128, t);
    CHECK(r.e->cowbell_state.last_drain_count == 8);
    r.render(4800, t);
    CHECK(all_finite(t.mono()));
    CHECK(t.peak() > 1e-3f && t.peak() <= 1.0f);
}

// ── Fill bursts and hit colour ──────────────────────────────────

// A strike with a colour, at kind 0 unless given.
void strike_coloured(Rig& r, float velocity, int kind, int color) {
    r.set("strike", (float)cowbell_encode_strike(velocity, kind, color));
}

// Spectral centroid in Hz of mono[begin, begin + n), Hann-windowed.
float centroid_hz(const std::vector<float>& mono, size_t begin, size_t n, float sr) {
    size_t fft_n = 1;
    while (fft_n < n) fft_n <<= 1;
    std::vector<std::complex<float>> a(fft_n);
    for (size_t i = 0; i < n; ++i) {
        const float w = 0.5f - 0.5f * std::cos(2.0f * 3.14159265f * (float)i / (float)(n - 1));
        a[i] = mono[begin + i] * w;
    }
    fft(a);
    double num = 0.0, den = 0.0;
    for (size_t k = 1; k < fft_n / 2; ++k) {
        const double m = std::abs(a[k]);
        num += m * (double)k * sr / (double)fft_n;
        den += m;
    }
    return den > 0.0 ? (float)(num / den) : 0.0f;
}

// Seconds until the output's 5 ms peak last exceeds -40 dBFS.
float seconds_above_minus_40(const Take& t, float sr) {
    const size_t win = (size_t)(0.005f * sr);
    size_t last = 0;
    for (size_t s = 0; s + win <= t.left.size(); s += win) {
        if (t.peak(s, s + win) > 0.01f) last = s + win;
    }
    return (float)last / sr;
}

Take fill_take(int kind, float velocity, size_t frames, int color = 0) {
    Rig r;
    strike_coloured(r, velocity, kind, color);
    Take t;
    r.render(frames, t);
    return t;
}

void test_fill_kinds_differ() {
    printf("\n--- fill_kinds_differ ---\n");
    const float sr = 48000.0f;
    const char* names[5] = {"", "MARBLE", "BEADS", "SEEDS", "JINGLES"};
    float centroid[5] = {}, fall[5] = {};
    for (int kind = 1; kind <= 4; ++kind) {
        const Take t = fill_take(kind, 0.8f, (size_t)(4 * sr));
        const std::vector<float> m = t.mono();
        centroid[kind] = centroid_hz(m, 0, (size_t)(0.03f * sr), sr);
        fall[kind] = seconds_above_minus_40(t, sr);
        printf("  %-8s peak %.4f, RMS 0-100 ms %.4f, centroid(30 ms) %.0f Hz, above -40 dBFS for %.3f s\n",
               names[kind], t.peak(), rms(m, 0, (size_t)(0.1f * sr)), centroid[kind], fall[kind]);
        CHECK(all_finite(m));
        CHECK(t.peak() > 1e-4f);
    }
    CHECK(centroid[3] > centroid[2]);  // SEEDS over BEADS
    CHECK(fall[4] > fall[3]);          // JINGLES ring out longer than SEEDS
}

void test_marble_rings_like_a_tap() {
    printf("\n--- marble_rings_like_a_tap ---\n");
    const float sr = 48000.0f;
    const std::vector<float> marble = fill_take(1, 0.8f, (size_t)(0.6f * sr)).mono();
    const std::vector<float> tap = fill_take(0, 0.8f, (size_t)(0.6f * sr)).mono();
    const size_t a = (size_t)(0.1f * sr), b = (size_t)(0.6f * sr);
    const float db = 20.0f * std::log10(rms(marble, a, b) / rms(tap, a, b));
    printf("  100-600 ms RMS: marble vs tap %+.2f dB\n", db);
    CHECK(std::fabs(db) < 3.0f);
}

void test_colour_shapes_brightness() {
    printf("\n--- colour_shapes_brightness ---\n");
    const float sr = 48000.0f;
    // b = 0.5 (colour 64) offsets nothing, so it is colour 0.
    const Take none = fill_take(0, 0.8f, 24000, 0);
    const Take mid = fill_take(0, 0.8f, 24000, 64);
    float worst = 0.0f;
    for (size_t i = 0; i < none.left.size(); ++i) {
        worst = std::max(worst, std::max(std::fabs(none.left[i] - mid.left[i]), std::fabs(none.right[i] - mid.right[i])));
    }
    printf("  colour 0 vs 64: worst difference %.2e\n", worst);
    CHECK(none.peak() > 1e-3f);
    CHECK(worst < 1e-6f);

    const std::vector<float> dark = fill_take(0, 0.8f, 24000, 1).mono();
    const std::vector<float> bright = fill_take(0, 0.8f, 24000, 127).mono();
    const float c_dark = centroid_hz(dark, 0, (size_t)(0.03f * sr), sr);
    const float c_bright = centroid_hz(bright, 0, (size_t)(0.03f * sr), sr);
    printf("  centroid over 30 ms: colour 1 %.0f Hz, colour 127 %.0f Hz\n", c_dark, c_bright);
    CHECK(all_finite(dark) && all_finite(bright));
    CHECK(c_bright > c_dark);
}

struct StormResult {
    float peak = 0.0f;
    float pre_ceiling_peak = 0.0f;  // the largest block peak before the soft ceiling
    double mean_l = 0.0, mean_r = 0.0;
    bool finite = true;
};

// 45 SEEDS and 5 body hits pushed before every 128-frame block, velocities random in 0.2..1.
StormResult run_storm(Rig& r, int seconds) {
    std::mt19937 rng(11);
    std::uniform_real_distribution<float> vel(0.2f, 1.0f);
    StormResult res;
    r.set("level", 0.8f);
    const int blocks = seconds * 48000 / 128;
    for (int b = 0; b < blocks; ++b) {
        for (int i = 0; i < 45; ++i) cowbell_push_strike(r.e, cowbell_encode_strike(vel(rng), 3, 0));
        for (int i = 0; i < 5; ++i) cowbell_push_strike(r.e, cowbell_encode_strike(vel(rng), 0, 0));
        r.block(128);
        res.pre_ceiling_peak = std::max(res.pre_ceiling_peak, r.e->cowbell_state.last_pre_ceiling_peak);
        for (int i = 0; i < 128; ++i) {
            const float l = r.u->output_buffers[OPORT_OUT][i], rr = r.u->output_buffers[OPORT_OUT_RIGHT][i];
            res.finite = res.finite && orpheus_is_finite(l) && orpheus_is_finite(rr);
            res.peak = std::max(res.peak, std::max(std::fabs(l), std::fabs(rr)));
            res.mean_l += l;
            res.mean_r += rr;
        }
    }
    res.mean_l /= (double)blocks * 128.0;
    res.mean_r /= (double)blocks * 128.0;
    return res;
}

void test_fill_burst_storm_bounded() {
    printf("\n--- fill_burst_storm_bounded ---\n");
    // The ceiling alone keeps any pile-up under 1.0; the pre-ceiling peak shows the storm itself
    // is bounded (the resonator's own limiter times the makeup gain and each body's trim), not just
    // squashed. Bounds sit just over the measures, 2.93, 2.26, 2.77 and 0.96, so a doubled gain
    // trips them. SITAR's DC is its own: under this storm it measures -4.1e-3 on the left.
    const float pre_ceiling_bound[kCowbellBodyCount] = {3.2f, 2.5f, 3.0f, 1.1f};
    const double mean_bound[kCowbellBodyCount] = {1e-3, 6e-3, 1e-3, 1e-3};
    for (int body = 0; body < kCowbellBodyCount; ++body) {
        Rig r;
        r.set("body", (float)body);
        const StormResult s = run_storm(r, 5);
        printf("  %-6s peak %.4f (%.4f before the ceiling), mean L %.2e R %.2e\n", kBodyNames[body], s.peak,
               s.pre_ceiling_peak, s.mean_l, s.mean_r);
        CHECK(s.finite);
        CHECK(s.peak > 1e-3f && s.peak < 1.0f);
        CHECK(s.pre_ceiling_peak < pre_ceiling_bound[body]);
        CHECK(std::fabs(s.mean_l) < mean_bound[body] && std::fabs(s.mean_r) < mean_bound[body]);
    }
}

constexpr float kTapKnee = 0.8f;  // the soft ceiling's knee in orpheus_unit_cowbell.cpp
constexpr float kMaxKneeSeconds = 0.020f;  // the longest a tap anywhere on a sweep leans on the ceiling

// A full-velocity tap at the default LEVEL sounds below the ceiling's knee: the ceiling is for
// pile-ups, not for a normal hit. The shipped default strikes harder and may touch it, but only for
// the first few milliseconds.
void test_tap_peaks_below_knee() {
    printf("\n--- tap_peaks_below_knee ---\n");
    Rig r;
    r.strike(1.0f);
    Take t;
    float pre = 0.0f;
    for (int b = 0; b < 375; ++b) {
        r.render(128, t);
        pre = std::max(pre, r.e->cowbell_state.last_pre_ceiling_peak);
    }
    printf("  peak %.4f (%.4f before the ceiling)\n", t.peak(), pre);
    CHECK(t.peak() > 1e-3f);
    CHECK(t.peak() < kTapKnee);

    Rig shipped(48000.0f, false);
    shipped.strike(1.0f);
    Take d;
    shipped.render(36000, d);
    size_t over = 0;
    for (size_t i = 0; i < d.left.size(); ++i) {
        if (std::max(std::fabs(d.left[i]), std::fabs(d.right[i])) > kTapKnee) ++over;
    }
    printf("  shipped default: peak %.4f, above the knee %.2f ms\n", d.peak(), (float)over * 1000.0f / 48000.0f);
    CHECK(over < 192);  // 4 ms
}

// One tap at velocity 0.9 on a body, root C, rendered in the unit's 128-frame blocks.
struct BodyTap {
    Take take;
    float knee_seconds = 0.0f;  // time either channel spends above the ceiling's knee
};

BodyTap body_tap(int body, float tone, float ring, float intensity, size_t frames) {
    Rig r(48000.0f, false);
    r.e->pulsar_root_note.store(0);
    r.set("body", (float)body);
    r.set("tone", tone);
    r.set("ring", ring);
    r.set("intensity", intensity);
    r.strike(0.9f);
    BodyTap tap;
    r.render(frames, tap.take);
    size_t over = 0;
    for (size_t i = 0; i < tap.take.left.size(); ++i) {
        if (std::max(std::fabs(tap.take.left[i]), std::fabs(tap.take.right[i])) > kTapKnee) ++over;
    }
    tap.knee_seconds = (float)over / 48000.0f;
    return tap;
}

float stereo_rms(const Take& t, size_t begin, size_t end) {
    const float l = rms(t.left, begin, end), r = rms(t.right, begin, end);
    return std::sqrt(0.5f * (l * l + r * r));
}

float mean_of(const std::vector<float>& x) {
    double sum = 0.0;
    for (float v : x) sum += v;
    return x.empty() ? 0.0f : (float)(sum / (double)x.size());
}

// T60 estimate: 1.5 x the time a tap's backward-integrated energy takes to fall 40 dB. The integral
// rides over the beating of SITAR's strings, which makes a first crossing of -60 dB jump around.
float decay_seconds(const Take& t) {
    const size_t bin = 240;  // 5 ms
    std::vector<double> energy;
    for (size_t s = 0; s + bin <= t.left.size(); s += bin) {
        double e = 0.0;
        for (size_t i = s; i < s + bin; ++i) e += (double)t.left[i] * t.left[i] + (double)t.right[i] * t.right[i];
        energy.push_back(e);
    }
    double total = 0.0;
    for (double e : energy) total += e;
    double remaining = total;
    for (size_t i = 0; i < energy.size(); ++i) {
        remaining -= energy[i];
        if (remaining < 1e-4 * total) return 1.5f * (float)(i + 1) * 0.005f;
    }
    return 1.5f * (float)energy.size() * 0.005f;
}

// Every corner and middle of TONE, RING and INTENSITY: no silent stretch, no DC, nothing
// non-finite. The ceiling makes a peak over it impossible, so time above the knee is what
// catches a clipping stretch.
void test_sweep_tap_health() {
    printf("\n--- sweep_tap_health ---\n");
    for (int body = 0; body < kCowbellBodyCount; ++body) {
        float lowest = 1.0f, highest = 0.0f, worst_dc = 0.0f, worst_knee = 0.0f;
        for (float tone : {0.0f, 0.5f, 1.0f}) {
            for (float ring : {0.0f, 0.5f, 1.0f}) {
                for (float intensity : {0.0f, 0.5f, 1.0f}) {
                    const BodyTap tap = body_tap(body, tone, ring, intensity, 2 * 48000);
                    const float peak = tap.take.peak();
                    const float dc = std::max(std::fabs(mean_of(tap.take.left)), std::fabs(mean_of(tap.take.right)));
                    lowest = std::min(lowest, peak);
                    highest = std::max(highest, peak);
                    worst_dc = std::max(worst_dc, dc);
                    worst_knee = std::max(worst_knee, tap.knee_seconds);
                    const bool sound = peak <= kTestCeiling && peak > 0.00398f;  // -48 dBFS
                    const bool clean = dc < 1e-3f && tap.knee_seconds < kMaxKneeSeconds &&
                                       all_finite(tap.take.left) && all_finite(tap.take.right);
                    if (!sound || !clean) {
                        printf("  %s T%.1f R%.1f I%.1f: peak %.4f dc %.1e above knee %.1f ms\n", kBodyNames[body], tone,
                               ring, intensity, peak, dc, tap.knee_seconds * 1000.0f);
                    }
                    CHECK(sound);
                    CHECK(clean);
                }
            }
        }
        printf("  %-6s peak %.4f..%.4f (%.1f dBFS at the lowest), dc %.1e, above knee %.1f ms\n", kBodyNames[body],
               lowest, highest, 20.0f * std::log10(lowest), worst_dc, worst_knee * 1000.0f);
    }
}

// RING is the tail on every body: a clearly shorter tap at 0 than at 0.5 (20% at least), and
// longer again at 1.
void test_sweep_ring_is_the_tail() {
    printf("\n--- sweep_ring_is_the_tail ---\n");
    for (int body = 0; body < kCowbellBodyCount; ++body) {
        const float t0 = decay_seconds(body_tap(body, 0.5f, 0.0f, 0.7f, 14 * 48000).take);
        const float t5 = decay_seconds(body_tap(body, 0.5f, 0.5f, 0.7f, 14 * 48000).take);
        const float t1 = decay_seconds(body_tap(body, 0.5f, 1.0f, 0.7f, 14 * 48000).take);
        printf("  %-6s T60 RING 0 %.2f s, 0.5 %.2f s, 1 %.2f s\n", kBodyNames[body], t0, t5, t1);
        CHECK(t0 < 0.8f * t5);
        CHECK(t5 < t1);
    }
}

// No drone: a tap at RING 1 is under -50 dBFS within 8 s on every body, and the far corners of
// TONE and INTENSITY, longer because brightness holds the high partials, are under -30.
void test_sweep_ring_top_falls_quiet() {
    printf("\n--- sweep_ring_top_falls_quiet ---\n");
    for (int body = 0; body < kCowbellBodyCount; ++body) {
        const Take t = body_tap(body, 0.5f, 1.0f, 0.7f, 8 * 48000).take;
        const float quiet = t.peak(t.left.size() - 4800, t.left.size());
        float worst = 0.0f, at_07_worst = 0.0f;
        for (float tone : {0.0f, 0.5f, 1.0f}) {
            for (float intensity : {0.7f, 1.0f}) {
                const Take c = body_tap(body, tone, 1.0f, intensity, 8 * 48000).take;
                const float tail = c.peak(c.left.size() - 4800, c.left.size());
                worst = std::max(worst, tail);
                if (intensity == 0.7f) at_07_worst = std::max(at_07_worst, tail);
            }
        }
        printf("  %-6s %.1f dBFS at 8 s; any TONE at INTENSITY 0.7 %.1f; any corner %.1f\n", kBodyNames[body],
               20.0f * std::log10(quiet + 1e-12f), 20.0f * std::log10(at_07_worst + 1e-12f),
               20.0f * std::log10(worst + 1e-12f));
        CHECK(quiet < 0.00316f);
        CHECK(worst < 0.0316f);
    }
}

// TONE reads on METAL: its three sweep points differ by at least 5% in spectral centroid.
void test_sweep_metal_tone_distinct() {
    printf("\n--- sweep_metal_tone_distinct ---\n");
    const auto centroid = [](float tone) {
        const std::vector<float> m = body_tap(2, tone, 0.3f, 0.7f, 9600).take.mono();
        return centroid_hz(m, 0, 9600, 48000.0f);
    };
    const float low = centroid(0.0f), mid = centroid(0.5f), high = centroid(1.0f);
    printf("  centroid TONE 0 %.0f Hz, 0.5 %.0f Hz, 1 %.0f Hz\n", low, mid, high);
    CHECK(std::fabs(mid - low) >= 0.05f * low);
    CHECK(std::fabs(high - mid) >= 0.05f * mid);
    CHECK(std::fabs(high - low) >= 0.05f * low);
}

// Switching bodies keeps the level: a tap's first 700 ms on each body, at its default and its
// middle position, is within 3 dB of METAL at the default.
void test_level_trim_spread() {
    printf("\n--- level_trim_spread ---\n");
    const auto level = [](int body, float tone, float ring, float intensity) {
        return stereo_rms(body_tap(body, tone, ring, intensity, 33600).take, 0, 33600);
    };
    const float metal = level(2, 0.3f, 0.1f, 0.7f);
    CHECK(near(cowbell_macros(2, 0.3f, 0.1f, 0.7f).level_trim, 1.0f, 1e-6f));
    for (int body = 0; body < kCowbellBodyCount; ++body) {
        const float at_default = 20.0f * std::log10(level(body, 0.3f, 0.1f, 0.7f) / metal);
        const float at_mid = 20.0f * std::log10(level(body, 0.5f, 0.5f, 0.5f) / metal);
        printf("  %-6s trim %.2f: default %+.1f dB, mid %+.1f dB against METAL's default (RMS %.4f)\n",
               kBodyNames[body], cowbell_macros(body, 0.5f, 0.5f, 0.5f).level_trim, at_default, at_mid, metal);
        CHECK(std::fabs(at_default) <= 3.0f);
        CHECK(std::fabs(at_mid) <= 3.0f);
    }
}

void test_burst_pool_full_replaces_quietest() {
    printf("\n--- burst_pool_full_replaces_quietest ---\n");
    Rig r;
    std::vector<float> vels;
    for (int i = 1; i <= 20; ++i) vels.push_back(0.05f * (float)i);
    std::mt19937 rng(5);
    std::shuffle(vels.begin(), vels.end(), rng);
    std::vector<float> pushed;  // as the strike word carries them
    // The slots hold 8 a block, so 20 JINGLES arrive over three short blocks, all still alive.
    size_t next = 0;
    for (int n : {8, 8, 4}) {
        for (int i = 0; i < n; ++i, ++next) {
            const uint32_t w = cowbell_encode_strike(vels[next], 4, 0);
            pushed.push_back((float)(w & 0x7Fu) / 127.0f);
            cowbell_push_strike(r.e, w);
        }
        r.block(32);
    }
    std::sort(pushed.begin(), pushed.end(), std::greater<float>());
    int live = 0;
    float lowest = 2.0f;
    for (const CowbellBurst& b : r.e->cowbell_state.bursts) {
        if (b.frames_left > 0) {
            ++live;
            lowest = std::min(lowest, b.velocity);
        }
    }
    printf("  %d live bursts, lowest velocity %.4f, 16th highest pushed %.4f\n", live, lowest, pushed[15]);
    CHECK(live == kCowbellMaxBursts);
    CHECK(lowest >= pushed[15] - 1e-6f);
}

float side_db(const Take& t, size_t begin, size_t end) {
    const float l = rms(t.left, begin, end), r = rms(t.right, begin, end);
    return 20.0f * std::log10((std::max(l, r) + 1e-12f) / (std::min(l, r) + 1e-12f));
}

// Polyphonic Rings pans alternate voices hard left and right; the unit narrows that, but keeps
// the two-pickup spread of a single voice.
void test_stereo_width() {
    printf("\n--- stereo_width ---\n");
    Rig poly;
    poly.set("body", 0.0f);  // STRING: two voices
    poly.strike(1.0f);
    Take tp;
    poly.render(24000, tp);
    const float poly_db = side_db(tp, 0, tp.left.size());

    Rig mono;
    mono.strike(1.0f);  // METAL: one voice
    Take tm;
    mono.render(24000, tm);
    std::vector<float> diff(tm.left.size()), sum(tm.left.size());
    for (size_t i = 0; i < diff.size(); ++i) {
        diff[i] = tm.left[i] - tm.right[i];
        sum[i] = tm.left[i] + tm.right[i];
    }
    const float spread = rms(diff, 0, diff.size()) / std::max(rms(sum, 0, sum.size()), 1e-12f);
    printf("  STRING lone tap: L/R %.2f dB apart; METAL: side/mid %.3f\n", poly_db, spread);
    CHECK(poly_db < 3.0f);
    CHECK(spread > 0.05f);

    // Two quick hits on a two-voice body still land on opposite sides.
    Rig fast;
    fast.set("body", 0.0f);
    fast.strike(1.0f);
    Take tf;
    fast.render(2400, tf);
    const float first_lr = rms(tf.left, 0, 2400) - rms(tf.right, 0, 2400);
    fast.strike(1.0f);
    fast.render(2400, tf);
    const float second_lr = rms(tf.left, 2400, 4800) - rms(tf.right, 2400, 4800);
    printf("  two hits: L-R RMS %+.4f then %+.4f\n", first_lr, second_lr);
    CHECK(first_lr * second_lr < 0.0f);
}

// The ordinal map still serves the drum-voice engine port in the shared routing.
void test_kotlin_engine_ordinal_mapping() {
    printf("\n--- kotlin_engine_ordinal_mapping ---\n");
    CHECK(kotlin_engine_ordinal_to_plaits(-1) == -1);
    CHECK(kotlin_engine_ordinal_to_plaits(99) == -1);
    CHECK(kotlin_engine_ordinal_to_plaits(25) == -1);
    CHECK(kotlin_engine_ordinal_to_plaits(2) == 23);
    CHECK(kotlin_engine_ordinal_to_plaits(3) == 10);
    CHECK(kotlin_engine_ordinal_to_plaits(24) == 4);
}

// A stale writer may still send the retired `engine` and `metal`: the unit ignores them, and a tap
// rings the same.
void test_removed_ports_are_no_ops() {
    printf("\n--- removed_ports_are_no_ops ---\n");
    auto tap = [](bool write_ports) {
        Rig r;
        if (write_ports) {
            for (float v : {99.0f, -1.0f, std::nanf(""), 5.0f}) r.set("engine", v);
            r.set("metal", 1.0f);
        }
        r.strike(1.0f);
        Take t;
        r.render(24000, t);
        return t;
    };
    const Take plain = tap(false);
    const Take written = tap(true);
    printf("  peak %.4f with the ports written, %.4f without\n", written.peak(), plain.peak());
    CHECK(written.peak() > 1e-3f);
    CHECK(written.left == plain.left);
    CHECK(written.right == plain.right);
}

// ── GRID ────────────────────────────────────────────────────────

// A hand-set Pulsar grid: 16ths averaging `sps` frames, step 1000 at absolute frame `first`.
// With `shift`, even steps run sps + shift and odd ones sps - shift, as Pulsar's swing does.
struct FakeGrid {
    int sps;
    int64_t first;
    int shift = 0;
    int64_t pos = 0;  // absolute frame at the next block's start
    bool running = true;

    static int64_t floor_div(int64_t a, int64_t b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
    int64_t at(int64_t step) const {
        const int64_t k = step - 1000, pair = floor_div(k, 2);
        return first + pair * 2 * sps + (k - 2 * pair) * (sps + shift);
    }
    // The first step whose boundary is at or after absolute frame `f`.
    int64_t step_from(int64_t f) const {
        int64_t k = 1000 + floor_div(f - first, sps) - 2;
        while (at(k) < f) ++k;
        return k;
    }
    void fill(PulsarGridExport& g, int n) {
        g = PulsarGridExport{};
        g.running = running;
        g.samples_per_step = (float)sps;
        for (int64_t k = step_from(pos); at(k) < pos + n && g.boundary_count < kPulsarGridMaxBoundaries; ++k) {
            g.boundary_samples[g.boundary_count] = (int)(at(k) - pos);
            g.boundary_steps[g.boundary_count++] = k;
        }
        g.next_step = step_from(pos + n);
        g.samples_to_next = (float)(at(g.next_step) - (pos + n));
        g.samples_since_last = (float)(pos - at(step_from(pos) - 1));
        pos += n;
    }
};

// Renders `frames` in `block_size` blocks with the grid filled before each one.
void render_grid(Rig& r, FakeGrid& g, size_t frames, Take& t, int block_size, int* fires = nullptr) {
    for (size_t pos = 0; pos < frames; pos += (size_t)block_size) {
        const int n = (int)std::min<size_t>((size_t)block_size, frames - pos);
        g.fill(r.e->pulsar_grid, n);
        r.render((size_t)n, t, n);
        if (fires) *fires += r.e->cowbell_state.last_fire_count;
    }
}

// First frame where either side passes 1e-3, or -1.
long onset(const Take& t) {
    for (size_t i = 0; i < t.left.size(); ++i) {
        if (std::fabs(t.left[i]) > 1e-3f || std::fabs(t.right[i]) > 1e-3f) return (long)i;
    }
    return -1;
}

// One 1.0 strike before the first block, GRID on at `strength`, 6000-frame 16ths with a line at `line`.
long grid_onset(float strength, int64_t line, int block_size, int* fires = nullptr, int shift = 0) {
    Rig r;
    r.set("grid", 1.0f);
    r.set("grid_strength", strength);
    FakeGrid g{6000, line, shift};
    r.strike(1.0f);
    Take t;
    render_grid(r, g, 9600, t, block_size, fires);
    return onset(t);
}

void test_grid_off_plays_now() {
    printf("\n--- grid_off_plays_now ---\n");
    Rig r;
    r.set("grid", 0.0f);  // GRID off (it starts on), though Pulsar is running with a line 3000 frames on
    FakeGrid g{6000, 3000};
    r.strike(1.0f);
    Take t;
    render_grid(r, g, 4800, t, 128);
    const long at = onset(t);
    printf("  onset at %ld\n", at);
    CHECK(at >= 0 && at < 64);
}

void test_grid_full_pull_lands_on_line() {
    printf("\n--- grid_full_pull_lands_on_line ---\n");
    // A block holds at most kMaxFrames (512), so the line at 1000 falls in a later block.
    const long at = grid_onset(1.0f, 1000, 128);
    // The same with the line inside the strike's own block.
    const long in_block = grid_onset(1.0f, 400, 512);
    printf("  line at 1000: onset %ld; line at 400 in a 512 block: onset %ld\n", at, in_block);
    CHECK(at >= 1000 - 32 && at <= 1000 + 32);
    CHECK(in_block >= 400 - 32 && in_block <= 400 + 32);
}

void test_grid_partial_pull() {
    printf("\n--- grid_partial_pull ---\n");
    const long at = grid_onset(0.7f, 1000, 128);
    const long in_block = grid_onset(0.7f, 400, 512);
    printf("  strength 0.7, line at 1000: onset %ld; line at 400 in a 512 block: onset %ld\n", at, in_block);
    CHECK(at >= 700 - 32 && at <= 700 + 32);
    CHECK(in_block >= 280 - 32 && in_block <= 280 + 32);
}

void test_grid_across_blocks() {
    printf("\n--- grid_across_blocks ---\n");
    // No line in the first 256-frame block, the next 3000 frames after it.
    const long at = grid_onset(1.0f, 3256, 256);
    printf("  onset %ld\n", at);
    CHECK(at >= 3256 - 32 && at <= 3256 + 32);
}

void test_grid_just_after_plays_now() {
    printf("\n--- grid_just_after_plays_now ---\n");
    // The last line was 600 frames (10% of a step) before the strike.
    int fires = 0;
    const long at = grid_onset(1.0f, 5400, 128, &fires);
    printf("  onset %ld, %d fired\n", at, fires);
    CHECK(at >= 0 && at < 64);
    CHECK(fires == 1);

    // Swing 0.5: 7500-frame on-beats, 4500-frame off-beats. The window is a quarter of the
    // step in progress (1875 or 1125 frames), not of the average 16th.
    constexpr int kShift = 1500;
    // 600 frames into a short step: plays now (a window of a quarter of 6000 from its end missed it).
    const long short_now = grid_onset(1.0f, -600 - 7500, 128, nullptr, kShift);
    // 2500 frames into a long step: waits for the line at 5000 (a window of 6000 claimed it).
    const long long_waits = grid_onset(1.0f, -2500, 128, nullptr, kShift);
    // 600 frames into a long step: plays now.
    const long long_now = grid_onset(1.0f, -600, 128, nullptr, kShift);
    printf("  swung: 600 into a short step onset %ld, 2500 into a long one %ld, 600 into a long one %ld\n",
           short_now, long_waits, long_now);
    CHECK(short_now >= 0 && short_now < 64);
    CHECK(long_waits >= 5000 - 32 && long_waits <= 5000 + 32);
    CHECK(long_now >= 0 && long_now < 64);
}

// A shake inside one 16th: a strike every block. The just-after slot fires once, the rest
// merge into the next line.
void test_grid_one_strike_per_slot() {
    printf("\n--- grid_one_strike_per_slot ---\n");
    Rig r;
    r.set("grid", 1.0f);
    r.set("grid_strength", 1.0f);
    FakeGrid g{6000, 5400};  // just after a line
    Take t;
    int fires = 0;
    for (int b = 0; b < 40; ++b) {  // 5120 frames: up to the line at 5400
        r.strike(0.2f + 0.02f * (float)b);
        render_grid(r, g, 128, t, 128, &fires);
    }
    const int before_line = fires;
    render_grid(r, g, 1280, t, 128, &fires);
    printf("  40 strikes: %d fired before the line, %d in all\n", before_line, fires);
    CHECK(before_line == 1);
    CHECK(fires == 2);
}

// Low SNAP passes hits through: two in one 16th both sound at once. At full SNAP the same two
// wait and merge into the line's one slot.
void test_grid_low_snap_passes_through() {
    printf("\n--- grid_low_snap_passes_through ---\n");
    auto take = [](float strength, int* fires_early) {
        Rig r;
        r.set("grid", 1.0f);
        r.set("grid_strength", strength);
        FakeGrid g{6000, 3000};  // mid-step: the next line is 3000 frames on
        Take t;
        int fires = 0;
        r.strike(1.0f);
        render_grid(r, g, 128, t, 128, &fires);
        const int after_first = fires;
        r.strike(0.8f);
        render_grid(r, g, 128, t, 128, &fires);
        *fires_early = fires;
        render_grid(r, g, 4800, t, 128, &fires);
        printf("  SNAP %.2f: %d after the first block, %d after the second, %d in all, onset %ld\n", strength,
               after_first, *fires_early, fires, onset(t));
        return std::make_pair(after_first, fires);
    };
    int early = 0;
    const auto low = take(0.1f, &early);
    CHECK(low.first == 1);
    CHECK(early == 2);
    CHECK(low.second == 2);
    const auto full = take(1.0f, &early);
    CHECK(full.first == 0);
    CHECK(early == 0);
    CHECK(full.second == 1);
}

void test_grid_merges_same_slot() {
    printf("\n--- grid_merges_same_slot ---\n");
    auto take = [](bool soft_first) {
        Rig r;
        r.set("grid", 1.0f);
        r.set("grid_strength", 1.0f);
        FakeGrid g{6000, 3256};
        Take t;
        int fires = 0;
        if (soft_first) r.strike(0.3f);
        else r.strike(1.0f);
        render_grid(r, g, 256, t, 256, &fires);
        if (soft_first) r.strike(1.0f);
        render_grid(r, g, 9600, t, 256, &fires);
        return std::make_pair(t, fires);
    };
    const auto merged = take(true);
    const auto lone = take(false);
    const float merged_peak = merged.first.peak(), lone_peak = lone.first.peak();
    printf("  0.3 then 1.0: %d fired, onset %ld, peak %.4f; lone 1.0: onset %ld, peak %.4f\n", merged.second,
           onset(merged.first), merged_peak, onset(lone.first), lone_peak);
    CHECK(merged.second == 1);
    CHECK(lone.second == 1);
    CHECK(merged_peak >= 0.9f * lone_peak);
    const long at = onset(merged.first);
    CHECK(at >= 3256 - 32 && at <= 3256 + 32);
}

void test_grid_pulsar_stops_flushes() {
    printf("\n--- grid_pulsar_stops_flushes ---\n");
    for (bool grid_off : {false, true}) {
        Rig r;
        r.set("grid", 1.0f);
        r.set("grid_strength", 1.0f);
        FakeGrid g{6000, 3256};
        r.strike(1.0f);
        Take t;
        int fires = 0;
        render_grid(r, g, 256, t, 256, &fires);
        const int pending = r.e->cowbell_state.pending_count;
        if (grid_off) r.set("grid", 0.0f);
        else g.running = false;
        render_grid(r, g, 4800, t, 256, &fires);
        const long at = onset(t);
        printf("  %s: pending %d after the first block, then onset %ld, %d fired\n",
               grid_off ? "GRID off" : "Pulsar stops", pending, at, fires);
        CHECK(pending == 1);
        CHECK(at >= 256 && at < 256 + 64);
        CHECK(fires == 1);
        CHECK(r.e->cowbell_state.pending_count == 0);
    }
}

// Pulsar on the DJ graph at 120 bpm and 48 kHz with no tempo drift, unswung and at swing 0.5.
void test_pulsar_exports_boundaries() {
    printf("\n--- pulsar_exports_boundaries ---\n");
    for (float swing : {0.0f, 0.5f}) {
        OrpheusEngine* e = orpheus_engine_create(48000.0f);
        if (!load_dj_graph(e)) {
            CHECK(false);
            orpheus_engine_destroy(e);
            return;
        }
        e->pulsar_playing.store(1);
        e->pulsar_mix.store(1.0f);
        setup_fixture_baseline(e);
        e->pulsar_genre_swing.store(swing);  // the clock's swing floor
        for (auto& m : e->pulsar_track_macros) m.complexity_swing_max.store(0.0f);
        e->pulsar_energy.store(1.0f);  // no elastic tempo
        pin_pulsar_rngs(e);
        trigger_vibe_load(e);
        e->clock_bpm.store(120.0f);
        const int64_t shift = (int64_t)std::lround(swing * 0.5f * 6000.0f);

        constexpr int kBlock = 256;
        std::vector<float> stereo(2 * kBlock);
        std::vector<int64_t> at, steps;
        int64_t pos = 0;
        bool running = true, sps_ok = true, prediction_ok = true, since_ok = true;
        int64_t predicted_at = -1, predicted_step = -1;
        for (int b = 0; b < 3 * 48000 / kBlock; ++b) {
            orpheus_engine_process(e, stereo.data(), kBlock);
            const PulsarGridExport& g = e->pulsar_grid;
            running = running && g.running;
            sps_ok = sps_ok && std::fabs(g.samples_per_step - 6000.0f) <= 1.0f;
            // Frames since the last line before this block; 0 in the load's own block.
            if (b == 0) {
                since_ok = since_ok && g.samples_since_last == 0.0f;
            } else if (!at.empty()) {
                since_ok = since_ok && std::fabs(g.samples_since_last - (float)(pos - at.back())) <= 1.0f;
            }
            for (int k = 0; k < g.boundary_count; ++k) {
                at.push_back(pos + g.boundary_samples[k]);
                steps.push_back(g.boundary_steps[k]);
            }
            // Last block's samples_to_next and next_step name this block's first line, or the
            // same line again when none falls here.
            const int64_t first_at = g.boundary_count ? pos + g.boundary_samples[0]
                                                      : pos + kBlock + (int64_t)std::lround(g.samples_to_next);
            const int64_t first_step = g.boundary_count ? g.boundary_steps[0] : g.next_step;
            if (b > 0 && predicted_at >= 0) {
                prediction_ok = prediction_ok && std::llabs(first_at - predicted_at) <= 1 &&
                                first_step == predicted_step;
            }
            pos += kBlock;
            predicted_at = pos + (int64_t)std::lround(g.samples_to_next);
            predicted_step = g.next_step;
        }
        // Spacing starts at the second natural line. The first sits one frame early (5999 unswung):
        // load_vibe zeroes the accumulator, so it reaches a threshold one frame before a step
        // has passed. That is Pulsar's own clock, not the injected line at frame 0.
        // Swung pairs alternate sps + shift and sps - shift.
        bool spaced = at.size() >= 20, counted = at.size() >= 20;
        for (size_t i = 2; i < at.size(); ++i) {
            const int64_t d = at[i] - at[i - 1];
            spaced = spaced && (d == 6000 + shift || d == 6000 - shift);
            if (i > 2) spaced = spaced && d + (at[i - 1] - at[i - 2]) == 12000;
        }
        for (size_t i = 1; i < steps.size(); ++i) counted = counted && steps[i] == steps[i - 1] + 1;
        // Swung pairs open long: the first step after load is sps + shift (less the one early
        // frame), then a short one.
        const bool long_first = at.size() > 2 && std::llabs(at[1] - at[0] - (6000 + shift)) <= 1 &&
                                at[2] - at[1] == 6000 - shift;
        CHECK(long_first);
        printf("  swing %.1f: %zu lines at %lld, %lld, %lld, %lld; running %d, samples_per_step %.2f; "
               "spacing %d, counter %d, next-line prediction %d, since-last %d\n", swing, at.size(),
               at.size() > 0 ? (long long)at[0] : -1LL, at.size() > 1 ? (long long)at[1] : -1LL,
               at.size() > 2 ? (long long)at[2] : -1LL, at.size() > 3 ? (long long)at[3] : -1LL, (int)running,
               e->pulsar_grid.samples_per_step, (int)spaced, (int)counted, (int)prediction_ok, (int)since_ok);
        CHECK(running);
        CHECK(sps_ok);
        CHECK(spaced);
        CHECK(counted);
        CHECK(prediction_ok);
        CHECK(since_ok);

        e->pulsar_playing.store(0);
        orpheus_engine_process(e, stereo.data(), kBlock);
        CHECK(!e->pulsar_grid.running);
        orpheus_engine_destroy(e);
    }
}

// ── Waking from silence ─────────────────────────────────────────

// Fundamentals of the left and right outputs over 16384 frames from `begin`.
void side_f0s(const Take& t, size_t begin, float expected, float* left, float* right) {
    *left = lowest_partial_hz(t.left.data() + begin, 16384, 48000.0f, 0.3f * expected, 3.0f * expected);
    *right = lowest_partial_hz(t.right.data() + begin, 16384, 48000.0f, 0.3f * expected, 3.0f * expected);
}

// Two strikes 100 frames apart, from silence, on STRING (two voices, one per side). The first
// voice must keep the root, not the note the resonator was primed on.
void test_wake_two_strikes_keep_pitch() {
    printf("\n--- wake_two_strikes_keep_pitch ---\n");
    constexpr float kD5 = 587.33f;  // the string model's lowest partial sits near, not on, the root
    const CowbellParams p = cowbell_macros(0, 0.55f, 0.5f, 0.6f);  // STRING: a pair of voices
    auto render_resonator = [&p](bool both_strikes) {
        OrpheusCowbellResonator reso;
        reso.init(48000.0f);
        reso.set_model(p.model);
        reso.set_polyphony(p.polyphony);
        reso.set_structure(p.structure);
        reso.set_damping(p.damping);
        reso.set_brightness(p.brightness);
        reso.set_position(0.5f);
        reso.set_note(74.0f);
        reso.strike(1.0f, 0);
        if (both_strikes) reso.strike(1.0f, 100);
        Take t;
        float l[128], r[128];
        for (int b = 0; b < 375; ++b) {
            reso.process(nullptr, l, r, 128);
            t.left.insert(t.left.end(), l, l + 128);
            t.right.insert(t.right.end(), r, r + 128);
        }
        return t;
    };
    // One lone strike is voice 0 on the note: the pitch both voices must then share.
    const Take lone = render_resonator(false);
    const std::vector<float> lone_mono = lone.mono();  // a lone strike sounds on one side only
    const float expected = lowest_partial_hz(lone_mono.data() + 2400, 16384, 48000.0f, 0.3f * kD5, 3.0f * kD5);
    printf("  lone voice: %.2f Hz\n", expected);
    {
        // The resonator alone, both strikes in one block at offsets 0 and 100.
        const Take t = render_resonator(true);
        float fl = 0.0f, fr = 0.0f;
        side_f0s(t, 2400, kD5, &fl, &fr);
        printf("  resonator, one block: left %.2f Hz (%.1f cents), right %.2f Hz (%.1f cents)\n", fl,
               cents(fl, expected), fr, cents(fr, expected));
        CHECK(std::fabs(cents(fl, expected)) <= 10.0f);
        CHECK(std::fabs(cents(fr, expected)) <= 10.0f);
    }
    {
        // The unit, woken from idle after the root moved from C to D: a 100-frame block, then
        // the second strike.
        Rig r;
        r.set("body", 0.0f);  // STRING: a pair of voices
        Take idle;
        r.render(48000, idle);
        CHECK(r.e->cowbell_state.idle);
        r.e->pulsar_root_note.store(2);
        r.strike(1.0f);
        Take t;
        r.render(100, t, 100);
        r.strike(1.0f);
        r.render(48000, t);
        float fl = 0.0f, fr = 0.0f;
        side_f0s(t, 2400, kD5, &fl, &fr);
        printf("  unit after idle, 100 frames apart: left %.2f Hz (%.1f cents), right %.2f Hz (%.1f cents)\n", fl,
               cents(fl, expected), fr, cents(fr, expected));
        CHECK(std::fabs(cents(fl, expected)) <= 10.0f);
        CHECK(std::fabs(cents(fr, expected)) <= 10.0f);
    }
}

// The first strike after idle on a two-voice body is narrowed from its first frame, not ramped in from
// full width over the block.
void test_wake_width_from_strike_polyphony() {
    printf("\n--- wake_width_from_strike_polyphony ---\n");
    for (bool from_idle : {false, true}) {
        Rig r;
        if (from_idle) {
            Take idle;  // METAL idles at one voice
            r.render(48000, idle);
            CHECK(r.e->cowbell_state.idle);
        }
        r.set("body", 0.0f);  // STRING: two voices
        r.strike(1.0f);
        Take t;
        r.render(128, t);
        const float db = side_db(t, 0, 128);
        printf("  %s: first block L/R %.2f dB apart\n", from_idle ? "after idle at one voice" : "fresh", db);
        CHECK(db < 3.0f);
    }
}

// -- MORE: a boost past max, and the duck it keys on the DJ bus ---------------------------------

// A strike at `velocity` on a fresh rig with `level` and `more`, rendered for 0.5 s.
Take more_take(float level, float more, float velocity, bool write_more = true, float* duck_min = nullptr,
               float* duck_end = nullptr, bool* pre_peak_over_knee = nullptr) {
    Rig r;
    r.e->pulsar_root_note.store(2);
    r.set("ring", 0.7f);
    r.set("level", level);
    if (write_more) r.set("more", more);
    r.strike(velocity);
    Take t;
    float lowest = 1.0f;
    for (int b = 0; b < 188; ++b) {
        r.render(128, t);
        lowest = std::min(lowest, r.e->cowbell_state.duck_gain);
        if (pre_peak_over_knee && r.e->cowbell_state.last_pre_ceiling_peak > 0.8f) *pre_peak_over_knee = true;
    }
    if (duck_min) *duck_min = lowest;
    if (duck_end) *duck_end = r.e->cowbell_state.duck_gain;
    return t;
}

uint64_t take_hash(const Take& t) {
    uint64_t h = 1469598103934665603ull;
    for (const std::vector<float>* ch : {&t.left, &t.right}) {
        for (float v : *ch) {
            uint32_t b;
            std::memcpy(&b, &v, 4);
            h = (h ^ b) * 1099511628211ull;
        }
    }
    return h;
}

void test_more_zero_is_bit_identical() {
    printf("\n--- more_zero_is_bit_identical ---\n");
    // Never written, written 0, and raised then returned to 0 before the strike all render the same.
    const Take never = more_take(0.7f, 0.0f, 1.0f, false);
    const Take zero = more_take(0.7f, 0.0f, 1.0f, true);
    CHECK(never.left == zero.left);
    CHECK(never.right == zero.right);
    Rig r;
    r.e->pulsar_root_note.store(2);
    r.set("ring", 0.7f);
    r.set("more", 0.8f);
    Take warm;
    r.render(1024, warm);
    r.set("more", 0.0f);
    r.render(1024, warm);
    r.strike(1.0f);
    Take back;
    r.render(24000, back);
    Rig fresh;
    fresh.e->pulsar_root_note.store(2);
    fresh.set("ring", 0.7f);
    fresh.strike(1.0f);
    Take ref;
    fresh.render(24000, ref);
    CHECK(back.left == ref.left);
    CHECK(back.right == ref.right);
    // The fixed-seed hash of the render before MORE existed, on the dev Mac: ae5f2ca34f4ccac1.
    uint64_t h = 1469598103934665603ull;
    for (float lv : {0.7f, 1.0f, 0.3f}) {
        Rig g;
        g.e->pulsar_root_note.store(2);
        g.set("ring", 0.7f);
        g.set("level", lv);
        g.strike(1.0f);
        Take t;
        g.render(24000, t);
        for (const std::vector<float>* ch : {&t.left, &t.right}) {
            for (float v : *ch) {
                uint32_t b;
                std::memcpy(&b, &v, 4);
                h = (h ^ b) * 1099511628211ull;
            }
        }
    }
    // Printed, not checked: the hash shifts with the compiler, the arch and FP contraction.
    printf("  render hash %016llx\n", (unsigned long long)h);
}

// The shipped default is an acoustic cowbell: the METAL body, ringing on, struck hard.
void test_default_voicing() {
    printf("\n--- default_voicing ---\n");
    Rig r(48000.0f, false);
    CHECK(r.e->cowbell_body.load() == 2);
    CHECK(r.e->cowbell_tone.load() == 0.0f);
    CHECK(r.e->cowbell_ring.load() == 0.48f);
    CHECK(r.e->cowbell_intensity.load() == 0.85f);
    r.e->pulsar_root_note.store(2);
    r.strike(1.0f);
    Take t;
    r.render(72000, t);
    const std::vector<float> m = t.mono();
    const float early = rms(m, 0, 4800);
    const float late = rms(m, 57600, 72000);
    printf("  first 100 ms RMS %.4f, 1.2-1.5 s RMS %.6f\n", early, late);
    CHECK(early > 1e-3f);
    CHECK(late < 1e-2f * early);  // down 40 dB by 1.2 s: it rings, then it is gone
}

void test_more_boosts_the_bell_9db() {
    printf("\n--- more_boosts_the_bell_9db ---\n");
    // A soft strike at a low level stays under the ceiling's knee even boosted.
    bool over = false;
    const Take base = more_take(0.3f, 0.0f, 0.3f);
    const Take more = more_take(0.3f, 1.0f, 0.3f, true, nullptr, nullptr, &over);
    const float db = 20.0f * std::log10(more.peak() / base.peak());
    printf("  peak %.4f -> %.4f: %+.2f dB\n", base.peak(), more.peak(), db);
    CHECK(!over);
    CHECK(base.peak() > 1e-3f);
    CHECK(std::fabs(db - 8.94f) <= 0.5f);
    // Half way is half the boost (x1.9).
    const Take half = more_take(0.3f, 0.5f, 0.3f);
    const float half_db = 20.0f * std::log10(half.peak() / base.peak());
    CHECK(std::fabs(half_db - 5.58f) <= 0.5f);
}

void test_more_ceiling_still_tames_peaks() {
    printf("\n--- more_ceiling_still_tames_peaks ---\n");
    const Take loud = more_take(1.0f, 1.0f, 1.0f);
    printf("  full level, full more: peak %.4f\n", loud.peak());
    CHECK(loud.peak() <= kTestCeiling);
}

void test_more_zero_never_ducks() {
    printf("\n--- more_zero_never_ducks ---\n");
    float lowest = 0.0f, end = 0.0f;
    more_take(1.0f, 0.0f, 1.0f, true, &lowest, &end);
    printf("  duck gain min %.6f end %.6f\n", lowest, end);
    CHECK(lowest == 1.0f);
    CHECK(end == 1.0f);
}

void test_more_duck_depth_and_release() {
    printf("\n--- more_duck_depth_and_release ---\n");
    float lowest = 0.0f, end = 0.0f;
    more_take(1.0f, 1.0f, 1.0f, true, &lowest, &end);
    const float db = 20.0f * std::log10(lowest);
    printf("  duck at full MORE: %.2f dB, after 0.5 s %.4f\n", db, end);
    CHECK(std::fabs(db + 12.0f) <= 0.5f);
    float half = 0.0f;
    more_take(1.0f, 0.5f, 1.0f, true, &half, nullptr);
    CHECK(std::fabs(20.0f * std::log10(half) + 6.0f) <= 0.5f);
    // A silent bell keys nothing, whatever MORE is.
    float silent = 0.0f;
    more_take(0.0f, 1.0f, 1.0f, true, &silent, nullptr);
    CHECK(silent == 1.0f);
}

}  // namespace

bool run_cowbell_unit_tests() {
    g_pass = g_fail = 0;
    printf("\n########## Cowbell unit tests ##########\n");
    test_macros_defaults();
    test_macros_bodies();
    test_macros_ring_polyphony();
    test_macros_grid_in_range();
    test_macros_monotonic();
    test_body_port_validates();
    test_strike_round_trip();
    test_strike_parity_constants();
    test_strike_bad_port_values();
    test_macros_boundaries();
    test_strike_clamps();
    test_unit_strike_rings_at_root();
    test_unit_octave_up();
    test_unit_louder_strike_louder();
    test_unit_slots_keep_loudest();
    test_unit_slots_two_threads();
    test_unit_graph_swap_drops_stale_strikes();
    test_unit_level_zero_silent();
    test_unit_idle_skips_and_wakes();
    test_unit_disabled_zeroes();
    test_unit_bad_ports();
    test_unit_offsets_sweep_glides();
    test_tap_releases();
    test_synth_gate_sustains();
    test_synth_held_note_every_body();
    test_bend_bends_ringing_voices();
    test_bend_sweep_glides();
    test_snap_to_scale_major();
    test_bend_scale_snaps_held_note();
    test_bend_scale_free_while_pulsar_stopped();
    test_bend_scale_respects_note_cap();
    test_bend_scale_glide_has_no_click();
    test_body_change_waits_for_strike();
    test_body_change_mid_ring_identical();
    test_body_change_ring_top_falls_quiet();
    test_body_change_adopted_by_gate_or_idle();
    test_body_change_hit_attacks_at_full_level();
    test_full_block_of_hits();
    test_fill_kinds_differ();
    test_marble_rings_like_a_tap();
    test_colour_shapes_brightness();
    test_fill_burst_storm_bounded();
    test_tap_peaks_below_knee();
    test_sweep_tap_health();
    test_sweep_ring_is_the_tail();
    test_sweep_ring_top_falls_quiet();
    test_sweep_metal_tone_distinct();
    test_level_trim_spread();
    test_burst_pool_full_replaces_quietest();
    test_stereo_width();
    test_kotlin_engine_ordinal_mapping();
    test_removed_ports_are_no_ops();
    test_grid_off_plays_now();
    test_grid_full_pull_lands_on_line();
    test_grid_partial_pull();
    test_grid_across_blocks();
    test_grid_just_after_plays_now();
    test_grid_one_strike_per_slot();
    test_grid_low_snap_passes_through();
    test_grid_merges_same_slot();
    test_grid_pulsar_stops_flushes();
    test_pulsar_exports_boundaries();
    test_wake_two_strikes_keep_pitch();
    test_wake_width_from_strike_polyphony();
    test_more_zero_is_bit_identical();
    test_default_voicing();
    test_more_boosts_the_bell_9db();
    test_more_ceiling_still_tames_peaks();
    test_more_zero_never_ducks();
    test_more_duck_depth_and_release();
    printf("\n  cowbell_unit: %d passed, %d failed\n", g_pass, g_fail);
    g_suite_passes = g_pass;
    g_suite_fails = g_fail;
    return g_fail == 0;
}
