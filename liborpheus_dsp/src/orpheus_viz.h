#pragma once
#include <atomic>
#include <cstdint>

// Lock-free ring buffer for signal visualization.
// Audio thread writes one sample per block via write().
// UI thread reads via orpheus_engine_get_viz() (lock-free, acquire/release).
// Uses uint32_t for write_count so unsigned wrapping arithmetic keeps
// the (wc - rc) difference correct even after overflow (~529 days at 94 writes/sec).
struct VizRing {
    static constexpr int kVizBufSize = 480;  // ~5 sec at 94 writes/sec (48000/512)
    float buf[kVizBufSize] = {};
    std::atomic<uint32_t> write_count{0};  // monotonic counter, unsigned wrapping

    inline void write(float value) {
        uint32_t wc = write_count.load(std::memory_order_relaxed);
        buf[wc % kVizBufSize] = value;
        write_count.store(wc + 1, std::memory_order_release);
    }
};

// Visualization channel IDs — one ring buffer per channel.
// Add new channels at the end to avoid breaking existing code.
enum VizChannel {
    VIZ_LFO_OUTPUT = 0,
    VIZ_WARPS_CARRIER = 1,
    VIZ_WARPS_MOD = 2,
    VIZ_WARPS_OUT = 3,
    VIZ_DELAY_IN = 4,
    VIZ_DELAY_FB = 5,
    VIZ_DELAY_OUT = 6,
    VIZ_REVERB_IN = 7,
    VIZ_REVERB_OUT = 8,
    VIZ_FLUX_CV = 9,
    VIZ_RESO_IN = 10,
    VIZ_RESO_OUT = 11,
    VIZ_DRUM_OUT = 12,
    VIZ_GRAINS_IN = 13,
    VIZ_GRAINS_OUT = 14,
    VIZ_LFO_CH1 = 15,
    VIZ_LFO_CH2 = 16,
    VIZ_LFO_CH3 = 17,
    VIZ_BASS_OUT = 18,
    VIZ_MASTER_OUT = 19,
    VIZ_HORN_IN = 20,       // audio peak into Leslie effect
    VIZ_HORN_OUT = 21,      // audio peak out of Leslie effect
    VIZ_HORN_PHASE = 22,    // normalized horn rotor phase 0..1 (for animation)
    VIZ_WOOFER_PHASE = 23,  // normalized woofer rotor phase 0..1 (for animation)
    VIZ_DJ_OUT = 24,
    VIZ_TIDES_CH0 = 25,
    VIZ_TIDES_CH1 = 26,
    VIZ_TIDES_CH2 = 27,
    VIZ_TIDES_CH3 = 28,
    VIZ_PULSAR_TRACK_0 = 29,
    VIZ_PULSAR_TRACK_1 = 30,
    VIZ_PULSAR_TRACK_2 = 31,
    VIZ_PULSAR_TRACK_3 = 32,
    VIZ_PULSAR_TRACK_4 = 33,
    VIZ_PULSAR_TRACK_5 = 34,
    VIZ_PULSAR_TRACK_6 = 35,
    VIZ_PULSAR_TRACK_7 = 36,
    VIZ_BEAT_PHASE = 37,
    VIZ_PULSAR_VOID_GAIN = 38,  // Void Anomaly's live per-block gain (1.0 = idle/no duck)
    VIZ_CHANNEL_COUNT
};
