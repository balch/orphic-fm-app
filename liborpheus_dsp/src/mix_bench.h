// Mix bench: turns Pulsar's measurement taps into one fixed-size summary per audio block and
// hands them to a reader thread through a lock-free SPSC ring. The audio thread writes, one
// reader drains; the writer never blocks and drops the newest summary when the ring is full.
#pragma once

#include "mix_meter.h"
#include "orpheus_engine.h"
#include "stem_capture.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

// Channel order everywhere: 0-7 tracks, 8 STORM, 9 STREET, 10 SPEECH, 11 DRY (sum of 0-10),
// 12 OUT (the engine's final stereo output).
constexpr int kBenchTracks = 8;
constexpr int kBenchStorm = 8, kBenchStreet = 9, kBenchSpeech = 10, kBenchDry = 11, kBenchOut = 12;
constexpr int kBenchChannels = 13;

// Block sums of one stereo signal. wave_* is the min and max of the mid signal (L+R)/2.
struct BenchChannel {
    float k_energy, e_l, e_r, e_lr, e_sub, e_low, peak, wave_min, wave_max;
};

struct BenchSummary {
    int32_t frames;
    int32_t section;
    float pre_limit_peak, e_pre_limit, e_post_limit, e_pre_hpf, e_post_hpf;
    int32_t over_knee, samples;
    BenchChannel ch[kBenchChannels];
};

// Floats per flattened summary, ints stored as float. A literal: Kotlin parses it from this header.
constexpr int kBenchSummaryFloats = 126;
static_assert(kBenchSummaryFloats == 9 + kBenchChannels * static_cast<int>(sizeof(BenchChannel) / sizeof(float)),
              "kBenchSummaryFloats must match the BenchSummary layout");
static_assert(sizeof(BenchSummary) == kBenchSummaryFloats * sizeof(float), "BenchSummary must have no padding");

// Power-of-two SPSC ring of summaries with free-running indices, as in orpheus_record_ring.h.
class BenchRing {
public:
    static constexpr uint32_t kCapacity = 1024;
    static_assert((kCapacity & (kCapacity - 1)) == 0, "capacity must be a power of two");

    BenchRing() : buf_(new BenchSummary[kCapacity]) {}

    // Audio thread. Never overwrites unread data: a full ring drops this summary and counts it.
    void push(const BenchSummary& s) {
        const uint32_t w = write_.load(std::memory_order_relaxed);
        const uint32_t r = read_.load(std::memory_order_acquire);
        if (w - r >= kCapacity) {
            dropped_.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        buf_[w & kMask] = s;
        write_.store(w + 1, std::memory_order_release);
    }

    // Reader thread. Flattens up to max summaries into out; returns the count.
    int drain(float* out, int max_summaries) {
        if (!out || max_summaries <= 0) return 0;
        const uint32_t r = read_.load(std::memory_order_relaxed);
        const uint32_t w = write_.load(std::memory_order_acquire);
        const uint32_t n = std::min(static_cast<uint32_t>(max_summaries), w - r);
        for (uint32_t i = 0; i < n; i++) flatten(buf_[(r + i) & kMask], out + static_cast<size_t>(i) * kBenchSummaryFloats);
        read_.store(r + n, std::memory_order_release);
        return static_cast<int>(n);
    }

    // Monotonic and never reset.
    uint64_t dropped() const { return dropped_.load(std::memory_order_relaxed); }

private:
    static constexpr uint32_t kMask = kCapacity - 1;

    static void flatten(const BenchSummary& s, float* f) {
        f[0] = static_cast<float>(s.frames);
        f[1] = static_cast<float>(s.section);
        std::memcpy(f + 2, &s.pre_limit_peak, 5 * sizeof(float));
        f[7] = static_cast<float>(s.over_knee);
        f[8] = static_cast<float>(s.samples);
        std::memcpy(f + 9, s.ch, sizeof(s.ch));
    }

    std::unique_ptr<BenchSummary[]> buf_;
    std::atomic<uint32_t> write_{0};
    std::atomic<uint32_t> read_{0};
    std::atomic<uint64_t> dropped_{0};
};

class MixBench {
public:
    explicit MixBench(float sample_rate)
        : taps_(new OrpheusEngine::PulsarMeasureTaps()),
          k_(kBenchChannels * 2, mix_meter::KWeight(sample_rate)),
          bands_(kBenchChannels * 2, mix_meter::Bands(sample_rate)) {}

    OrpheusEngine::PulsarMeasureTaps* taps() { return taps_.get(); }

    // Audio thread, once per graph chunk after Pulsar has written the taps. Zeroes the taps after
    // reading them, since Pulsar skips the writes for silent tracks and absent voices.
    void on_block(OrpheusEngine* e, const float* out, int frames) {
        if (frames <= 0) return;
        if (frames > kMaxFrames) frames = kMaxFrames;
        OrpheusEngine::PulsarMeasureTaps& t = *taps_;
        BenchSummary s{};
        s.frames = frames;
        s.section = pulsar_current_section(e->pulsar_state);
        Acc acc[kBenchChannels];
        mix_meter::BusStats bus;
        for (int i = 0; i < frames; i++) {
            double dl = 0, dr = 0;
            for (int c = 0; c < kBenchDry; c++) {
                const double l = c < kBenchTracks ? t.track_l[c][i] : t.source_l[c - kBenchTracks][i];
                const double r = c < kBenchTracks ? t.track_r[c][i] : t.source_r[c - kBenchTracks][i];
                add(acc[c], c, l, r);
                dl += l;
                dr += r;
            }
            add(acc[kBenchDry], kBenchDry, dl, dr);
            add(acc[kBenchOut], kBenchOut, out[2 * i], out[2 * i + 1]);
            bus.add(t.pre_limit_l[i], t.pre_hpf_l[i], t.post_hpf_l[i], soft_limit);
            bus.add(t.pre_limit_r[i], t.pre_hpf_r[i], t.post_hpf_r[i], soft_limit);
        }
        s.pre_limit_peak = static_cast<float>(bus.pre_limit_peak);
        s.e_pre_limit = static_cast<float>(bus.e_pre_limit);
        s.e_post_limit = static_cast<float>(bus.e_post_limit);
        s.e_pre_hpf = static_cast<float>(bus.e_pre_hpf);
        s.e_post_hpf = static_cast<float>(bus.e_post_hpf);
        s.over_knee = static_cast<int32_t>(bus.over_knee);
        s.samples = static_cast<int32_t>(bus.samples);
        for (int c = 0; c < kBenchChannels; c++) acc[c].store(s.ch[c]);
        ring_.push(s);
        if (StemCapture* sc = e->stem_capture.load(std::memory_order_acquire)) sc->on_block(t, out, frames);
        clear_taps(frames);
    }

    // Reader thread.
    int drain(float* out, int max_summaries) { return ring_.drain(out, max_summaries); }
    uint64_t dropped() const { return ring_.dropped(); }

private:
    // The shared meter sums plus the block's mid-signal range.
    struct Acc {
        mix_meter::StereoStats st;
        double wave_min = HUGE_VAL, wave_max = -HUGE_VAL;
        void store(BenchChannel& c) const {
            c = {static_cast<float>(st.k_energy), static_cast<float>(st.e_l), static_cast<float>(st.e_r),
                 static_cast<float>(st.e_lr), static_cast<float>(st.e_sub), static_cast<float>(st.e_low),
                 static_cast<float>(st.peak), static_cast<float>(wave_min), static_cast<float>(wave_max)};
        }
    };

    void add(Acc& a, int c, double l, double r) {
        const double kl = k_[2 * c].process(l), kr = k_[2 * c + 1].process(r);
        mix_meter::accumulate(a.st, l, r, kl, kr, bands_[2 * c], bands_[2 * c + 1]);
        const double mid = 0.5 * (l + r);
        a.wave_min = std::min(a.wave_min, mid);
        a.wave_max = std::max(a.wave_max, mid);
    }

    void clear_taps(int frames) {
        OrpheusEngine::PulsarMeasureTaps& t = *taps_;
        const size_t bytes = static_cast<size_t>(frames) * sizeof(float);
        for (int c = 0; c < kBenchTracks; c++) { std::memset(t.track_l[c], 0, bytes); std::memset(t.track_r[c], 0, bytes); }
        for (int c = 0; c < OrpheusEngine::PulsarMeasureTaps::kMeasureSources; c++) {
            std::memset(t.source_l[c], 0, bytes);
            std::memset(t.source_r[c], 0, bytes);
        }
        for (float* p : {t.pre_hpf_l, t.pre_hpf_r, t.post_hpf_l, t.post_hpf_r, t.pre_limit_l, t.pre_limit_r})
            std::memset(p, 0, bytes);
    }

    std::unique_ptr<OrpheusEngine::PulsarMeasureTaps> taps_;
    std::vector<mix_meter::KWeight> k_;      // [channel * 2 + side], persists across blocks
    std::vector<mix_meter::Bands> bands_;
    BenchRing ring_;
};
