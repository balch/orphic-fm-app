// Resonator spike: audition clips and a CPU table that compare REZO (OrpheusResonator) with the
// new six-model resonator. Manual suites: they skip unless named on the command line.
#include "test_harness.h"
#include "../src/cowbell_macros.h"
#include "../src/orpheus_cowbell_resonator.h"
#include "../include/orpheus_dsp.h"
#include "../src/orpheus_engine.h"
#include "../src/orpheus_resonator.h"
#include "../src/orpheus_units.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr int kBlock = 128;
constexpr double kSweepSeconds = 8.0;
constexpr double kHeldSeconds = 10.0;
constexpr double kStrikeSeconds = 6.0;
constexpr float kHeldNote = 50.0f;
constexpr float kListenRms = 0.1f;      // -20 dBFS, the common level of every audition clip
constexpr float kTargetPeak = 0.5f;     // REZO's output ceiling for the held clips (below its fold-back)
constexpr float kMaxClipPeak = 0.98f;   // make-up gain never pushes a clip past this
const char* const kDir = "liborpheus_dsp/test/output/cowbell_spike";

enum Param { kStructure, kBrightness, kDamping, kPosition };

struct Clip {
    std::string slug, title;
    std::string pair;    // base name shared by a REZO / new pair: they get the same input level
    bool rezo = false;
    int model = 0;       // REZO mode, or new model
    int polyphony = 1;   // new unit only
    int sr = 48000;
    float note = kHeldNote;
    float p[4] = {0.5f, 0.5f, 0.5f, 0.5f};
    int sweep = -1;      // Param swept 0 > 1 > 0 over kSweepSeconds, or -1
    bool strikes = false;  // 8 strikes, 0.5 s apart, velocity 0.3 to 1.0, no held input
};

std::string fmt(const char* f, ...) __attribute__((format(printf, 1, 2)));
std::string fmt(const char* f, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, f);
    vsnprintf(buf, sizeof buf, f, ap);
    va_end(ap);
    return buf;
}

// Held input: polyBLEP saw.
struct Saw {
    float phase = 0.0f, dt = 0.0f;
    void init(float hz, float sr) { phase = 0.0f; dt = hz / sr; }
    float next(float amp) {
        float t = phase;
        float blep = 0.0f;
        if (t < dt) { float x = t / dt; blep = x + x - x * x - 1.0f; }
        else if (t > 1.0f - dt) { float x = (t - 1.0f) / dt; blep = x * x + x + x + 1.0f; }
        float y = 2.0f * t - 1.0f - blep;
        phase += dt;
        if (phase >= 1.0f) phase -= 1.0f;
        return y * amp;
    }
};

float midi_hz(float midi) { return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f); }

// 0 > 1 > 0 over the sweep, then held at the end value.
float sweep_value(double t) {
    if (t >= kSweepSeconds) return 0.0f;
    const double x = t / kSweepSeconds;
    return static_cast<float>(1.0 - std::fabs(2.0 * x - 1.0));
}

struct Render {
    std::vector<float> pcm;   // interleaved stereo
    bool model_ok = true;
    bool model_from_init = true;   // the model sounded without a strike at frame 0
};

Render render_clip(const Clip& c, bool conditioning, float amp) {
    Render r;
    const double secs = c.strikes ? kStrikeSeconds : kHeldSeconds;
    const int total = static_cast<int>(secs * c.sr);
    r.pcm.assign(static_cast<size_t>(total) * 2, 0.0f);
    Saw saw;
    saw.init(midi_hz(c.note), static_cast<float>(c.sr));
    auto params_at = [&](double t, float* out) {
        for (int i = 0; i < 4; i++) out[i] = c.p[i];
        if (c.sweep >= 0) out[c.sweep] = sweep_value(t);
    };

    if (c.rezo) {
        auto reso = std::make_unique<OrpheusResonator>();
        reso->init(static_cast<float>(c.sr));
        // The engine's strum() writes these two; strum() itself would also inject an impulse.
        const float f = std::max(0.00001f, std::min(midi_hz(c.note) / static_cast<float>(c.sr), 0.49f));
        reso->modal.frequency = f;
        reso->string.frequency = f;
        for (int b0 = 0; b0 < total; b0 += kBlock) {
            float p[4];
            params_at(static_cast<double>(b0) / c.sr, p);
            reso->modal.structure = p[kStructure];
            reso->modal.brightness = p[kBrightness];
            reso->modal.damping = p[kDamping];
            reso->modal.position = p[kPosition];
            reso->string.brightness = p[kBrightness];
            reso->string.damping = p[kDamping];
            reso->string.position = p[kPosition];
            const int n = std::min(kBlock, total - b0);
            for (int i = 0; i < n; i++) {
                reso->process(saw.next(amp), c.model);
                r.pcm[static_cast<size_t>(b0 + i) * 2] = reso->out_l;
                r.pcm[static_cast<size_t>(b0 + i) * 2 + 1] = reso->out_r;
            }
        }
        return r;
    }

    auto reso = std::make_unique<OrpheusCowbellResonator>();
    // Model and polyphony set before init() are the ones the voices are built with.
    reso->set_model(c.model);
    reso->set_polyphony(c.polyphony);
    reso->set_note(c.note);
    float p0[4];
    params_at(0.0, p0);
    reso->set_structure(p0[kStructure]);
    reso->set_brightness(p0[kBrightness]);
    reso->set_damping(p0[kDamping]);
    reso->set_position(p0[kPosition]);
    reso->init(static_cast<float>(c.sr));
    reso->set_output_conditioning(conditioning);
    bool strike_at_zero = false;
    if (reso->model() != c.model) {
        r.model_from_init = false;
        strike_at_zero = true;
    }

    std::vector<float> in(kBlock), out(kBlock), aux(kBlock);
    int next_strike = 0;
    for (int b0 = 0; b0 < total; b0 += kBlock) {
        float p[4];
        params_at(static_cast<double>(b0) / c.sr, p);
        reso->set_structure(p[kStructure]);
        reso->set_brightness(p[kBrightness]);
        reso->set_damping(p[kDamping]);
        reso->set_position(p[kPosition]);
        const int n = std::min(kBlock, total - b0);
        if (strike_at_zero && b0 == 0) reso->strike(0.0f, 0);
        if (c.strikes) {
            while (next_strike < 8) {
                const int at = static_cast<int>(next_strike * 0.5 * c.sr);
                if (at >= b0 + n) break;
                reso->strike(0.3f + 0.7f * static_cast<float>(next_strike) / 7.0f, at - b0);
                next_strike++;
            }
        } else {
            for (int i = 0; i < n; i++) in[static_cast<size_t>(i)] = saw.next(amp);
        }
        reso->process(c.strikes ? nullptr : in.data(), out.data(), aux.data(), n);
        // Rings sends odd voices to aux and even ones to out, so both channels are the audio.
        for (int i = 0; i < n; i++) {
            r.pcm[static_cast<size_t>(b0 + i) * 2] = out[static_cast<size_t>(i)];
            r.pcm[static_cast<size_t>(b0 + i) * 2 + 1] = aux[static_cast<size_t>(i)];
        }
    }
    r.model_ok = reso->model() == c.model;
    return r;
}

float peak_of(const std::vector<float>& v) {
    float pk = 0.0f;
    for (float x : v) pk = std::max(pk, std::fabs(x));
    return pk;
}

float rms_of(const std::vector<float>& v) {
    double acc = 0.0;
    for (float x : v) acc += static_cast<double>(x) * x;
    return v.empty() ? 0.0f : static_cast<float>(std::sqrt(acc / static_cast<double>(v.size())));
}

// Input level that keeps REZO's output peak at kTargetPeak across the clip's whole sweep. Its
// soft clip folds back above 0.5, so a louder input would be judged on distortion.
float find_rezo_amp(const Clip& rezo, float* peak_out) {
    float amp = 0.002f;   // linear at this level
    float pk = 0.0f;
    for (int i = 0; i < 12; i++) {
        pk = peak_of(render_clip(rezo, true, amp).pcm);
        if (pk <= kTargetPeak && pk >= 0.97f * kTargetPeak) break;
        amp *= 0.985f * kTargetPeak / pk;
    }
    *peak_out = pk;
    return amp;
}

bool all_finite(const std::vector<float>& v) {
    // Bit test: the release build is -ffast-math, which folds std::isfinite to true.
    for (float x : v) {
        uint32_t bits;
        std::memcpy(&bits, &x, sizeof bits);
        if (((bits >> 23) & 0xFFu) == 0xFFu) return false;
    }
    return true;
}

const char* const kNewModelNames[6] = {"modal", "sympathetic", "string", "FM",
                                       "quantized sympathetic", "string and reverb"};

std::vector<Clip> build_clips() {
    std::vector<Clip> clips;
    auto sweep_pair = [&](const char* base, const char* what, const char* rezo_name, int model,
                          int sweep, float structure, float brightness, float damping,
                          float position) {
        for (bool rezo : {true, false}) {
            Clip c;
            c.rezo = rezo;
            c.model = model;
            c.p[kStructure] = structure;
            c.p[kBrightness] = brightness;
            c.p[kDamping] = damping;
            c.p[kPosition] = position;
            c.sweep = sweep;
            c.pair = base;
            c.slug = fmt("%s_%s", base, rezo ? "rezo" : "new");
            c.title = rezo ? fmt("REZO %s, %s sweep", rezo_name, what)
                           : fmt("New resonator %s, %s sweep", kNewModelNames[model], what);
            clips.push_back(c);
        }
    };
    sweep_pair("bar_structure", "structure", "bar", 0, kStructure, 0.0f, 0.48f, 0.65f, 0.72f);
    sweep_pair("bar_position", "position", "bar", 0, kPosition, 0.39f, 0.48f, 0.65f, 0.0f);
    sweep_pair("sitar_structure", "structure", "sitar", 1, kStructure, 0.0f, 0.76f, 0.36f, 0.53f);
    sweep_pair("sitar_position", "position", "sitar", 1, kPosition, 0.14f, 0.76f, 0.36f, 0.0f);

    for (int m = 0; m < 6; m++) {
        Clip c;
        c.model = m;
        c.polyphony = 4;
        c.note = 69.0f;
        c.p[kStructure] = 0.5f;
        c.p[kBrightness] = 0.6f;
        c.p[kDamping] = 0.6f;
        c.p[kPosition] = 0.5f;
        c.strikes = true;
        c.slug = fmt("strikes_model%d_new", m);
        c.title = fmt("New resonator %s, 8 strikes rising in velocity", kNewModelNames[m]);
        clips.push_back(c);
    }

    Clip k;   // the same render as bar_structure_new, at 44.1 kHz
    k.model = 0;
    k.sr = 44100;
    k.p[kStructure] = 0.0f;
    k.p[kBrightness] = 0.48f;
    k.p[kDamping] = 0.65f;
    k.p[kPosition] = 0.72f;
    k.sweep = kStructure;
    k.pair = "bar_structure";   // same input level as the 48 kHz pair
    k.slug = "bar_structure_new_44k";
    k.title = "New resonator modal, structure sweep, 44.1 kHz";
    clips.push_back(k);
    return clips;
}

const char* sweep_name(int s) {
    return s == kStructure ? "structure" : (s == kPosition ? "position" : "none");
}

std::string settings_of(const Clip& c) {
    auto val = [&](Param p, const char* name) {
        return c.sweep == p ? fmt("%s=sweep0-1-0", name) : fmt("%s=%.2f", name, c.p[p]);
    };
    std::string s = fmt("resonator=%s model=%d", c.rezo ? "REZO" : "new", c.model);
    if (!c.rezo) s += fmt(" polyphony=%d", c.polyphony);
    s += fmt(" sr=%d note=%.0f ", c.sr, c.note);
    s += val(kStructure, "structure") + " " + val(kBrightness, "brightness") + " " +
         val(kDamping, "damping") + " " + val(kPosition, "position");
    s += c.strikes ? " input=8strikes0.5s_vel0.3-1.0" : " input=saw50";
    s += c.rezo ? " channels=out_l,out_r" : " channels=out,aux";
    return s;
}

}  // namespace

bool run_cowbell_spike() {
    printf("\n=== Cowbell resonator spike: audition clips ===\n");
    if (!g_filtered_run) {
        printf("  skipped: name the suite on the command line\n");
        return true;
    }
    std::error_code ec;
    std::filesystem::create_directories(kDir, ec);
    if (ec) {
        fprintf(stderr, "Cannot create %s: %s\n", kDir, ec.message().c_str());
        return false;
    }
    FILE* manifest = fopen((std::string(kDir) + "/manifest.tsv").c_str(), "w");
    if (!manifest) {
        fprintf(stderr, "Cannot open manifest in %s\n", kDir);
        return false;
    }
    fprintf(manifest, "file\ttitle\tsettings\tinput_amp\tpeak\trms\traw_peak\tgain\tgain_db\tgain_limited\tfinal_peak\tfinal_rms\n");

    bool ok = true;
    int written = 0;
    std::vector<std::string> over_knee;
    const std::vector<Clip> clips = build_clips();

    // Per pair: the input level that keeps REZO's output peak at or below 0.5.
    std::map<std::string, float> amps;
    printf("  Held-pair input levels (REZO peak at that level; REZO peak at the old 0.25 level)\n");
    for (const Clip& c : clips) {
        if (!c.rezo || c.strikes) continue;
        float pk = 0.0f;
        const float amp = find_rezo_amp(c, &pk);
        amps[c.pair] = amp;
        printf("    %-16s amp %.5f  REZO peak %.4f  (at 0.25: %.4f)\n", c.pair.c_str(), amp, pk,
               peak_of(render_clip(c, true, 0.25f).pcm));
    }

    // Strikes are peaky: at -20 dBFS RMS most would pass full scale. All six share the highest
    // RMS at which none passes kMaxClipPeak, so they stay level-matched with each other.
    float strike_rms = kListenRms;
    for (const Clip& c : clips) {
        if (!c.strikes) continue;
        const Render r = render_clip(c, true, 0.0f);
        const float pk = peak_of(r.pcm);
        if (pk > 1e-9f) strike_rms = std::min(strike_rms, rms_of(r.pcm) * kMaxClipPeak / pk);
    }
    printf("  Strike clips share a listening RMS of %.4f (%.1f dBFS)\n", strike_rms,
           20.0 * std::log10(static_cast<double>(strike_rms)));

    for (const Clip& c : clips) {
        const float amp = c.strikes ? 0.0f : amps.at(c.pair);
        const Render r = render_clip(c, true, amp);
        const float peak = peak_of(r.pcm);
        const float rms = rms_of(r.pcm);
        std::string raw = "-";
        if (!c.rezo) {
            const float rp = peak_of(render_clip(c, false, amp).pcm);
            raw = fmt("%.4f", rp);
            if (rp > 0.9f) over_knee.push_back(c.slug);
        }
        // Common listening level: the manifest keeps the true levels and the gain applied.
        const float target = c.strikes ? strike_rms : kListenRms;
        float gain = rms > 1e-9f ? target / rms : 1.0f;
        bool limited = false;
        if (gain * peak > kMaxClipPeak) { gain = kMaxClipPeak / peak; limited = true; }
        std::vector<float> scaled(r.pcm.size());
        for (size_t i = 0; i < scaled.size(); i++) scaled[i] = r.pcm[i] * gain;
        const std::string file = c.slug + ".wav";
        write_wav((std::string(kDir) + "/" + file).c_str(), scaled.data(),
                  static_cast<int>(r.pcm.size() / 2), c.sr);
        written++;
        fprintf(manifest, "%s\t%s\t%s\t%s\t%.4f\t%.4f\t%s\t%.2f\t%.2f\t%s\t%.4f\t%.4f\n", file.c_str(),
                c.title.c_str(), settings_of(c).c_str(),
                c.strikes ? "-" : fmt("%.5f", amp).c_str(), peak, rms, raw.c_str(), gain,
                20.0 * std::log10(static_cast<double>(gain)), limited ? "yes" : "no",
                peak_of(scaled), rms_of(scaled));
        printf("  %s in %.5f peak %.4f rms %.4f raw %s gain %.2f%s\n", file.c_str(), amp, peak, rms,
               raw.c_str(), gain, limited ? " (LIMITED by peak)" : "");

        if (!all_finite(r.pcm)) { printf("  FLAGGED %s: non-finite sample\n", file.c_str()); ok = false; }
        else if (peak < 1e-4f) { printf("  FLAGGED %s: silent\n", file.c_str()); ok = false; }
        else if (!c.rezo && peak > 1.0f) { printf("  FLAGGED %s: peak above 1.0\n", file.c_str()); ok = false; }
        if (!c.rezo && !r.model_ok) { printf("  FLAGGED %s: wrong model sounding\n", file.c_str()); ok = false; }
    }
    fclose(manifest);
    printf("Wrote %d clips and manifest.tsv to %s\n", written, kDir);
    printf("New-unit clips with raw peak above 0.9:");
    if (over_knee.empty()) printf(" none");
    for (const std::string& s : over_knee) printf(" %s", s.c_str());
    printf("\nCowbell resonator spike: %s\n", ok ? "OK" : "FLAGGED");
    return ok;
}

namespace {

constexpr int kBenchSr = 48000;
constexpr int kBenchBlock = 256;
constexpr int kBenchSeconds = 10;
constexpr int kBenchReps = 3;   // best of N, to keep scheduler noise out

double realtime_percent(double wall_seconds) { return wall_seconds / kBenchSeconds * 100.0; }

// Triangle 0 > 1 > 0 over the whole bench, per block.
float bench_sweep(int block) {
    const double x = static_cast<double>(block) / (kBenchSeconds * kBenchSr / kBenchBlock);
    return static_cast<float>(1.0 - std::fabs(2.0 * x - 1.0));
}

constexpr float kBenchHeldAmp = 0.02f;   // the cost does not depend on it

double bench_rezo(bool sweep, volatile float* sink) {
    double best = 1e9;
    for (int rep = 0; rep < kBenchReps; rep++) {
        auto reso = std::make_unique<OrpheusResonator>();
        reso->init(static_cast<float>(kBenchSr));
        const float f = midi_hz(kHeldNote) / static_cast<float>(kBenchSr);
        reso->modal.frequency = reso->string.frequency = f;
        reso->modal.structure = 0.39f;
        reso->modal.brightness = reso->string.brightness = 0.48f;
        reso->modal.damping = reso->string.damping = 0.65f;
        reso->modal.position = reso->string.position = 0.72f;
        Saw saw;
        saw.init(midi_hz(kHeldNote), static_cast<float>(kBenchSr));
        float acc = 0.0f;
        const auto t0 = std::chrono::steady_clock::now();
        for (int b = 0; b < kBenchSeconds * kBenchSr / kBenchBlock; b++) {
            if (sweep) reso->modal.structure = bench_sweep(b);
            for (int i = 0; i < kBenchBlock; i++) {
                reso->process(saw.next(kBenchHeldAmp), 0);
                acc += reso->out_l;
            }
        }
        const std::chrono::duration<double> dt = std::chrono::steady_clock::now() - t0;
        *sink = *sink + acc;
        best = std::min(best, dt.count());
    }
    return realtime_percent(best);
}

// held = a held saw at note 50 with structure sweeping (the replacement case); otherwise
// strikes every 125 ms at note 69 and no input.
double bench_new(int model, int polyphony, bool held, volatile float* sink) {
    double best = 1e9;
    for (int rep = 0; rep < kBenchReps; rep++) {
        auto reso = std::make_unique<OrpheusCowbellResonator>();
        reso->set_model(model);
        reso->set_polyphony(polyphony);
        reso->set_note(held ? kHeldNote : 69.0f);
        reso->set_structure(0.5f);
        reso->set_brightness(0.6f);
        reso->set_damping(0.6f);
        reso->set_position(0.5f);
        reso->init(static_cast<float>(kBenchSr));
        std::vector<float> in(kBenchBlock), out(kBenchBlock), aux(kBenchBlock);
        Saw saw;
        saw.init(midi_hz(kHeldNote), static_cast<float>(kBenchSr));
        constexpr int kStrikeEvery = kBenchSr / 8;   // 125 ms
        float acc = 0.0f;
        const auto t0 = std::chrono::steady_clock::now();
        for (int b = 0; b < kBenchSeconds * kBenchSr / kBenchBlock; b++) {
            const int b0 = b * kBenchBlock;
            if (held) {
                reso->set_structure(bench_sweep(b));
                for (int i = 0; i < kBenchBlock; i++) in[static_cast<size_t>(i)] = saw.next(kBenchHeldAmp);
            } else {
                for (int s = (b0 + kStrikeEvery - 1) / kStrikeEvery * kStrikeEvery;
                     s < b0 + kBenchBlock; s += kStrikeEvery) {
                    reso->strike(0.8f, s - b0);
                }
            }
            reso->process(held ? in.data() : nullptr, out.data(), aux.data(), kBenchBlock);
            acc += out[0] + out[kBenchBlock - 1] + aux[0] + aux[kBenchBlock - 1];
        }
        const std::chrono::duration<double> dt = std::chrono::steady_clock::now() - t0;
        *sink = *sink + acc;
        best = std::min(best, dt.count());
    }
    return realtime_percent(best);
}

// The unit under the fill storm: per 128-frame block 45 SEEDS and 5 body hits pushed (the slots
// keep the loudest 8), velocities 0.2..1, LEVEL 0.8. The same load as the unit test's storm.
double bench_fills(volatile float* sink) {
    constexpr int kStormBlock = 128;
    constexpr int kSeedsKind = 3;
    double best = 1e9;
    for (int rep = 0; rep < kBenchReps; rep++) {
        OrpheusEngine* engine = orpheus_engine_create(static_cast<float>(kBenchSr));
        auto unit = std::make_unique<GraphUnit>();
        unit->type = UNIT_COWBELL;
        unit_init(unit.get(), static_cast<float>(kBenchSr));
        const char* uri = "org.balch.orpheus.plugins.cowbell";
        orpheus_engine_set_port(engine, uri, "engine", -1.0f);
        orpheus_engine_set_port(engine, uri, "level", 0.8f);
        uint32_t seed = 7u;
        auto velocity = [&seed] {
            seed = seed * 1664525u + 1013904223u;
            return 0.2f + 0.8f * static_cast<float>(seed >> 8) / 16777216.0f;
        };
        float acc = 0.0f;
        const auto t0 = std::chrono::steady_clock::now();
        for (int b = 0; b < kBenchSeconds * kBenchSr / kStormBlock; b++) {
            for (int i = 0; i < 45; i++) cowbell_push_strike(engine, cowbell_encode_strike(velocity(), kSeedsKind, 0));
            for (int i = 0; i < 5; i++) cowbell_push_strike(engine, cowbell_encode_strike(velocity(), 0, 0));
            unit_process_cowbell(unit.get(), engine, kStormBlock, static_cast<float>(kBenchSr));
            acc += unit->output_buffers[OPORT_OUT][0] + unit->output_buffers[OPORT_OUT_RIGHT][kStormBlock - 1];
        }
        const std::chrono::duration<double> dt = std::chrono::steady_clock::now() - t0;
        *sink = *sink + acc;
        best = std::min(best, dt.count());
        orpheus_engine_destroy(engine);
    }
    return realtime_percent(best);
}

}  // namespace

bool run_cowbell_bench() {
    printf("\n=== Cowbell resonator spike: CPU table ===\n");
    if (!g_filtered_run) {
        printf("  skipped: name the suite on the command line\n");
        return true;
    }
    std::error_code ec;
    std::filesystem::create_directories(kDir, ec);
    FILE* tsv = fopen((std::string(kDir) + "/bench.tsv").c_str(), "w");
    volatile float sink = 0.0f;

    printf("10 s at 48 kHz, 256-frame blocks, best of %d. realtime%% = wall time / audio time x 100;\n"
           "xREZO = that row over the first row (REZO bar, held saw, parameters fixed)\n",
           kBenchReps);
    printf("%-52s %9s %7s\n", "case", "realtime%", "xREZO");
    double rezo_pct = 0.0;
    auto row = [&](const std::string& name, double pct) {
        if (rezo_pct == 0.0) rezo_pct = pct;
        printf("%-52s %9.2f %7.2f\n", name.c_str(), pct, pct / rezo_pct);
        if (tsv) fprintf(tsv, "%s\t%.2f\t%.2f\n", name.c_str(), pct, pct / rezo_pct);
    };
    row("REZO mode 0 (bar)", bench_rezo(false, &sink));
    for (int m = 0; m < 6; m++) {
        for (int poly : {1, 2, 4}) {
            row(fmt("new model %d (%s) x%d, strikes", m, kNewModelNames[m], poly),
                bench_new(m, poly, false, &sink));
        }
    }
    // The replacement case: a held saw into the resonator with structure sweeping.
    row("REZO mode 0 (bar), held saw, structure sweep", bench_rezo(true, &sink));
    for (int m = 0; m < 2; m++) {
        row(fmt("new model %d (%s) x4, held saw, structure sweep", m, kNewModelNames[m]),
            bench_new(m, 4, true, &sink));
    }
    // The whole unit under the fill storm (resonator, exciter bookkeeping and 16 bursts).
    row("unit, fills (45 SEEDS + 5 body hits per 128 frames)", bench_fills(&sink));
    if (tsv) fclose(tsv);
    (void)sink;
    printf("Cowbell resonator spike CPU table: OK\n");
    return true;
}
