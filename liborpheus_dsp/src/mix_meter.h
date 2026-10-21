#pragma once
// Loudness and low-band meter maths shared by the vibe measurement harness and the mix bench.

#include <algorithm>
#include <cmath>
#include <vector>

namespace mix_meter {

constexpr double kSilentDb = -120.0;
constexpr double kSubHz = 40.0;
constexpr double kLowHz = 120.0;

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    double process(double x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

inline Biquad normalized(double b0, double b1, double b2, double a0, double a1, double a2) {
    Biquad q;
    q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0; q.a1 = a1 / a0; q.a2 = a2 / a0;
    return q;
}

// ITU-R BS.1770 K-weighting: +4 dB shelf above ~1.5 kHz, then a 38 Hz high-pass.
struct KWeight {
    Biquad shelf, hp;
    explicit KWeight(double fs) {
        const double a = std::pow(10.0, 4.0 / 40.0);
        double w = 2.0 * M_PI * 1500.0 / fs;
        double alpha = std::sin(w) / (2.0 * M_SQRT1_2);
        const double c = std::cos(w), sa = 2.0 * std::sqrt(a) * alpha;
        shelf = normalized(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c),
                           a * ((a + 1) + (a - 1) * c - sa), (a + 1) - (a - 1) * c + sa,
                           2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa);
        w = 2.0 * M_PI * 38.0 / fs;
        alpha = std::sin(w) / (2.0 * 0.5);
        const double c2 = std::cos(w);
        hp = normalized((1 + c2) / 2, -(1 + c2), (1 + c2) / 2, 1 + alpha, -2 * c2, 1 - alpha);
    }
    double process(double x) { return hp.process(shelf.process(x)); }
};

inline Biquad low_pass(double fs, double hz) {
    const double w = 2.0 * M_PI * hz / fs, alpha = std::sin(w) / (2.0 * M_SQRT1_2), c = std::cos(w);
    return normalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha);
}

inline Biquad high_pass(double fs, double hz) {
    const double w = 2.0 * M_PI * hz / fs, alpha = std::sin(w) / (2.0 * M_SQRT1_2), c = std::cos(w);
    return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
}

// Splits one channel's bottom end: "sub" is below 40 Hz (rumble and DC the 35 Hz HPF mostly removes),
// "low" is 40-120 Hz, what the kick and bass fight over.
struct Bands {
    Biquad sub, low_hp, low_lp;
    explicit Bands(double fs) : sub(low_pass(fs, kSubHz)), low_hp(high_pass(fs, kSubHz)), low_lp(low_pass(fs, kLowHz)) {}
    void process(double x, double& sub_out, double& low_out) {
        sub_out = sub.process(x);
        low_out = low_lp.process(low_hp.process(x));
    }
};

// One stereo signal's running sums over a window: K-weighted energy, per-side and cross energy,
// sub and low band energy (both sides), and the sample peak.
struct StereoStats {
    double k_energy = 0, e_l = 0, e_r = 0, e_lr = 0, e_sub = 0, e_low = 0, peak = 0;
    long frames = 0;
    void add(const StereoStats& o) {
        k_energy += o.k_energy; e_l += o.e_l; e_r += o.e_r; e_lr += o.e_lr; e_sub += o.e_sub; e_low += o.e_low;
        peak = std::max(peak, o.peak); frames += o.frames;
    }
};

// One frame into s: raw l and r, their K-weighted values kl and kr, and each side's band splitter.
inline void accumulate(StereoStats& s, double l, double r, double kl, double kr, Bands& bl, Bands& br) {
    double sub_l, low_l, sub_r, low_r;
    bl.process(l, sub_l, low_l);
    br.process(r, sub_r, low_r);
    s.k_energy += kl * kl + kr * kr;
    s.e_sub += sub_l * sub_l + sub_r * sub_r;
    s.e_l += l * l;
    s.e_r += r * r;
    s.e_lr += l * r;
    s.e_low += low_l * low_l + low_r * low_r;
    s.peak = std::max(s.peak, std::max(std::fabs(l), std::fabs(r)));
    s.frames++;
}

// Pulsar's bus limiter (soft_limit) is linear below this level and saturates above it.
constexpr float kKnee = 0.5f;

// The Pulsar bus around its limiter and its 35 Hz HPF, one sample of one side at a time.
struct BusStats {
    double pre_limit_peak = 0, e_pre_limit = 0, e_post_limit = 0, e_pre_hpf = 0, e_post_hpf = 0;
    long over_knee = 0, samples = 0;
    // limit is the bus limiter (soft_limit), a parameter so this header stays free of the voice code.
    template <typename Limiter>
    void add(float pre_limit, double pre_hpf, double post_hpf, Limiter limit) {
        const float post = limit(pre_limit);
        pre_limit_peak = std::max(pre_limit_peak, static_cast<double>(std::fabs(pre_limit)));
        e_pre_limit += static_cast<double>(pre_limit) * pre_limit;
        e_post_limit += static_cast<double>(post) * post;
        e_pre_hpf += pre_hpf * pre_hpf;
        e_post_hpf += post_hpf * post_hpf;
        if (std::fabs(pre_limit) > kKnee) over_knee++;
        samples++;
    }
};

inline double db_power(double p) { return p > 1e-30 ? 10.0 * std::log10(p) : kSilentDb; }
inline double db_amp(double a) { return a > 1e-15 ? 20.0 * std::log10(a) : kSilentDb; }
inline double lufs(double mean_power) { return mean_power > 1e-30 ? -0.691 + 10.0 * std::log10(mean_power) : kSilentDb; }

// BS.1770 gated integrated loudness over 400 ms block powers (mean zL^2 + mean zR^2).
inline double integrated_lufs(const std::vector<double>& blocks) {
    double sum = 0.0;
    int n = 0;
    for (double p : blocks) if (lufs(p) > -70.0) { sum += p; n++; }
    if (n == 0) return kSilentDb;
    const double gate = lufs(sum / n) - 10.0;
    sum = 0.0;
    n = 0;
    for (double p : blocks) if (lufs(p) > -70.0 && lufs(p) > gate) { sum += p; n++; }
    return n ? lufs(sum / n) : kSilentDb;
}

}  // namespace mix_meter
