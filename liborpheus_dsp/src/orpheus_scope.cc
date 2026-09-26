#include "orpheus_scope.h"
#include "../include/orpheus_dsp.h"
#include "orpheus_engine.h"
#include <algorithm>
#include <cmath>

namespace {

constexpr int   kMaxPoints   = ORPHEUS_SCOPE_MAX_POINTS;
constexpr float kMinWindowMs = 1.0f;
constexpr float kMaxWindowMs = 100.0f;

// The trigger search looks back four windows (at least 80 ms). A fifth repeats every two root
// periods and its latest crossing can sit one root period back, so it needs three root periods
// in view: at the 40 ms default, 160 ms holds a fifth on a 20 Hz root and a lone tone to ~12.5 Hz.
constexpr double kScanWindows = 4.0;
constexpr double kMinScanMs   = 20.0;

// Trigger path: a zero-phase triangle whose first null sits here, so crossings follow the bass
// and low mids rather than hats. Tones above ~1.4 kHz fall below the hysteresis and stay untriggered.
constexpr float kTriggerNullHz  = 2000.0f;
constexpr int   kMaxTriggerHalf = 256;

constexpr float  kSilencePeak     = 0.001f;  // -60 dBFS over the whole span reads as silence
constexpr double kHysteresis      = 0.25;    // of the trigger path's peak
constexpr double kHysteresisFloor = 0.05;    // of the raw peak, so broadband noise rarely arms

// A crossing triggers when its window correlates this well with an earlier crossing's window:
// a steady tone scores ~1, noise scores near 0. The first earlier crossing scoring kClearRepeat
// or more marks one full period, so a chord's in-between crossings don't count as repeats.
constexpr double kMinRepeat   = 0.6;
constexpr double kClearRepeat = 0.98;
constexpr int    kMaxEarlier  = 8;
constexpr int    kMaxAttempts = 3;

// Span one read may cover; the rest of the ring is the writer's headroom while the read runs.
constexpr uint32_t kMaxSpan = ScopeRing::kSize - 8192;

inline bool is_finite(float x) { return scope_is_finite(x); }

inline float at(const ScopeRing& r, uint32_t abs_index) {
    return r.buf[abs_index & ScopeRing::kMask].load(std::memory_order_relaxed);
}

// True when nothing from base onwards was overwritten while it was read. Call after the reads:
// the fence pairs with the writer's, so any overwritten sample we saw implies we see its claim.
inline bool intact(const ScopeRing& r, uint32_t base) {
    std::atomic_thread_fence(std::memory_order_acquire);
    return r.claimed.load(std::memory_order_relaxed) - base <= ScopeRing::kSize;
}

// Streams y[k] for k in [k_begin, k_end): a triangle of half-width lt centred on k, built from
// two running boxes. Reads x over [k_begin - lt + 1, k_end + lt - 2].
template <typename F>
void trigger_path(const ScopeRing& r, uint32_t base, int lt, int k_begin, int k_end, F&& f) {
    double hist[kMaxTriggerHalf];  // the first box's last lt values
    std::fill(hist, hist + lt, 0.0);
    double b1 = 0.0, b2 = 0.0;
    int hi = 0;
    const double inv = 1.0 / (static_cast<double>(lt) * lt);
    const int s = k_begin - lt + 1;
    const int m_end = k_end + lt - 1;
    for (int m = s; m < m_end; ++m) {
        const double x_old = (m - lt >= s) ? at(r, base + static_cast<uint32_t>(m - lt)) : 0.0;
        b1 += at(r, base + static_cast<uint32_t>(m)) - x_old;
        b2 += b1 - hist[hi];
        hist[hi] = b1;
        if (++hi == lt) hi = 0;
        const int k = m - (lt - 1);
        if (k >= k_begin) f(k, b2 * inv);
    }
}

// Point j is a triangle-weighted mean around start + (j + 0.5) * step: a box average run twice,
// which keeps the box's nulls at multiples of the point rate with half its sidelobe height.
void decimate(const ScopeRing& r, uint32_t base, double start, double step, float* out, int n) {
    const double h = std::max(step, 1.0);
    const double inv_h = 1.0 / h;
    for (int j = 0; j < n; ++j) {
        const double c = start + (j + 0.5) * step;
        const int k0 = static_cast<int>(std::ceil(c - h));
        const int k1 = static_cast<int>(std::floor(c + h));
        double acc = 0.0, wsum = 0.0;
        for (int k = k0; k <= k1; ++k) {
            const double w = 1.0 - std::fabs(k - c) * inv_h;
            if (w <= 0.0) continue;
            acc += w * at(r, base + static_cast<uint32_t>(k));
            wsum += w;
        }
        out[j] = wsum > 0.0 ? static_cast<float>(acc / wsum) : 0.0f;
    }
}

double correlation(const float* a, const float* b, int n) {
    double ma = 0.0, mb = 0.0;
    for (int i = 0; i < n; ++i) { ma += a[i]; mb += b[i]; }
    ma /= n;
    mb /= n;
    double sab = 0.0, saa = 0.0, sbb = 0.0;
    for (int i = 0; i < n; ++i) {
        const double da = a[i] - ma, db = b[i] - mb;
        sab += da * db;
        saa += da * da;
        sbb += db * db;
    }
    const double denom = std::sqrt(saa * sbb);
    return denom > 1e-18 ? sab / denom : 0.0;
}

}  // namespace

bool scope_ring_copy_latest(const ScopeRing& r, float* dst, int len) {
    if (!dst || len <= 0 || static_cast<uint32_t>(len) > ScopeRing::kSize) return false;
    const uint32_t w0 = r.published.load(std::memory_order_acquire);
    const uint32_t base = w0 - static_cast<uint32_t>(len);
    for (int i = 0; i < len; ++i) dst[i] = at(r, base + static_cast<uint32_t>(i));
    return intact(r, base);
}

int scope_read(const ScopeRing& r, float sample_rate, float window_ms, float* out, int n) {
    if (!out || n <= 0 || n > kMaxPoints) return -1;
    if (!is_finite(sample_rate) || !(sample_rate > 0.0f)) return -1;
    if (!is_finite(window_ms) || !(window_ms > 0.0f)) return -1;

    const int lt = std::clamp(static_cast<int>(std::lround(sample_rate / kTriggerNullHz)),
                              2, kMaxTriggerHalf);
    const double ms = std::clamp(window_ms, kMinWindowMs, kMaxWindowMs);
    // Keeps the span inside kMaxSpan; only rates above ~80 kHz reach it below 100 ms.
    const double window = std::min(ms * 0.001 * sample_rate, (kMaxSpan - 2.0 * lt - 16.0) / 3.0);
    const double step = window / n;
    const double h = std::max(step, 1.0);

    // Span layout, oldest first: head (kernel and trigger-path lead-in), scan (where crossings
    // may sit), tail (a full window after the latest allowed start).
    const int head = std::max(lt - 1, static_cast<int>(std::ceil(h))) + 2;
    const int tail = std::max(static_cast<int>(std::ceil(window + h)) + 2, lt + 2);
    const double min_scan = std::max(window, kMinScanMs * 0.001 * sample_rate);
    const int scan = std::min(static_cast<int>(std::ceil(kScanWindows * min_scan)),
                              static_cast<int>(kMaxSpan) - head - tail);
    const int span = head + scan + tail;
    const double t_max = span - tail;  // latest window start that leaves a full window
    const int y_end = span - lt + 1;   // the trigger path runs this far to confirm late crossings

    float win[kMaxPoints];
    float cmp[kMaxPoints];
    for (int attempt = 0; attempt < kMaxAttempts; ++attempt) {
        const uint32_t w0 = r.published.load(std::memory_order_acquire);
        const uint32_t base = w0 - static_cast<uint32_t>(span);

        float peak_x = 0.0f;
        for (int k = 0; k < span; ++k) {
            peak_x = std::max(peak_x, std::fabs(at(r, base + static_cast<uint32_t>(k))));
        }

        // The newest kMaxEarlier + 1 confirmed rising crossings at or before t_max, with the
        // trigger path's slope through each.
        constexpr int kCap = kMaxEarlier + 1;
        double cross[kCap];
        double slope[kCap];
        int crossings = 0;
        if (peak_x >= kSilencePeak) {
            double peak_y = 0.0;
            trigger_path(r, base, lt, head, y_end,
                         [&](int, double y) { peak_y = std::max(peak_y, std::fabs(y)); });
            const double hyst = std::max(kHysteresis * peak_y, kHysteresisFloor * peak_x);

            // Schmitt trigger: arm below -hyst, mark the next rising zero crossing, confirm it
            // once the path reaches +hyst, so a wiggle through zero doesn't count.
            enum { kIdle, kArmed, kRising } state = kIdle;
            double y_prev2 = 0.0, y_prev = 0.0, pending = 0.0, pending_slope = 0.0;
            // The slope at the crossing itself: central differences at the samples either side,
            // blended by where the crossing falls between them. One difference alone is off by
            // half the curvature times the sub-sample offset, enough to swap near-tied crossings.
            double frac = 0.0, d_before = 0.0, d_across = 0.0, y_at = 0.0;
            bool slope_owed = false;
            // Starts two samples early (head >= lt + 1 keeps them inside the span) so the first
            // crossing's difference before it uses real samples, not the zero history.
            trigger_path(r, base, lt, head - 2, y_end, [&](int k, double y) {
                if (k < head) {
                    y_prev2 = y_prev;
                    y_prev = y;
                    return;
                }
                if (slope_owed) {  // the difference after the crossing has just arrived
                    const double d_after = y - y_at;
                    pending_slope = 0.5 * ((1.0 - frac) * d_before + d_across + frac * d_after);
                    slope_owed = false;
                }
                switch (state) {
                    case kIdle:
                        if (y <= -hyst) state = kArmed;
                        break;
                    case kArmed:
                        if (y >= 0.0) {
                            frac = y_prev / (y_prev - y);
                            pending = (k - 1) + frac;
                            d_before = y_prev - y_prev2;
                            d_across = y - y_prev;
                            y_at = y;
                            slope_owed = true;
                            state = kRising;
                        }
                        break;
                    case kRising:
                        if (y >= hyst) {
                            if (pending <= t_max) {
                                cross[crossings % kCap] = pending;
                                slope[crossings % kCap] = pending_slope;
                                ++crossings;
                            }
                            state = kIdle;
                        } else if (y <= -hyst) {
                            state = kArmed;
                        }
                        break;
                }
                y_prev2 = y_prev;
                y_prev = y;
            });
        }

        // Triggered only when the latest crossing's window repeats an earlier one's. That repeat
        // spans one period; of the crossings inside it the steepest starts the window, so the
        // same phase wins every read even when a chord crosses zero several times per period.
        bool triggered = false;
        if (crossings >= 2) {
            auto back = [&](int i) { return (crossings - 1 - i) % kCap; };  // 0 = newest
            decimate(r, base, cross[back(0)], step, win, n);
            const int earlier = std::min(crossings - 1, kMaxEarlier);
            double best = -1.0;
            int period = 0;  // in crossings
            for (int i = 1; i <= earlier; ++i) {
                decimate(r, base, cross[back(i)], step, cmp, n);
                const double c = correlation(win, cmp, n);
                if (c > best) { best = c; period = i; }
                if (c >= kClearRepeat) break;
            }
            triggered = best >= kMinRepeat;
            if (triggered) {
                int steepest = 0;
                for (int i = 1; i < period; ++i) {
                    if (slope[back(i)] > slope[back(steepest)]) steepest = i;
                }
                if (steepest != 0) decimate(r, base, cross[back(steepest)], step, win, n);
            }
        }
        if (!triggered) decimate(r, base, t_max, step, win, n);

        if (intact(r, base)) {
            std::copy(win, win + n, out);
            return triggered ? 1 : 0;
        }
    }
    return -1;
}

// C API, declared in orpheus_dsp.h.
int orpheus_engine_get_scope(OrpheusEngine* engine, float* out, int num_points, float window_ms) {
    if (!engine) return -1;
    return scope_read(engine->scope_ring, engine->sample_rate, window_ms, out, num_points);
}
