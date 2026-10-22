// Headless mix harness: replays a vibe's Kotlin port dump (VibePortDumpRenderHarness) into the
// DJ graph and reads it like a mix engineer reads meters. For the whole render and for each
// section: loudness (LUFS), peak, crest, stereo balance and correlation, how hard the Pulsar bus
// clipper works, what the 55 Hz HPF takes, and each track's loudness against the dry mix, its
// pan, its low-end share and how often it is audible. Writes <dir>/<slug>.json, prints a table.
// Manual suite: runs only when named or VIBE_PORTS is set.
//
//   VIBE_PORTS=features/pulsar/build/vibe-recordings/orphic_lore.ports orpheus_dsp_test vibe_measure
//
// VIBE_SECONDS (240), VIBE_OUTRO_AT, VIBE_SR / VIBE_BLOCK as in vibe_record.
// VIBE_DIST: the DJ mixer's DIST fader (drive and mix together), 0.30 as the app ships.
// VIBE_SET=symbol=value,...  Pulsar port overrides, re-applied after every section flip.
// VIBE_TRIM=3:-2.6,2:-4.4  per-track dB on the TRACKS fader (audition gain, capped at +3.5 dB).
// VIBE_TAG suffixes the output name. VIBE_WAV=1 also writes the mix WAV next to the JSON.
//
// Besides the 8 tracks it measures the storm, street and speech voices, which Pulsar mixes in
// outside them. Speech stays silent here: its clips come from the platform's TTS in Kotlin.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "test_vibe_replay.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "orpheus_voice.h"
#include "stmlib/utils/random.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

namespace {

using namespace vibe_replay;

constexpr int kTracks = 8;
// The 8 tracks, then the voices Pulsar mixes in outside them (PulsarMeasureTaps::source_l order).
constexpr int kChannels = kTracks + OrpheusEngine::PulsarMeasureTaps::kMeasureSources;
const char* const kChannelNames[kChannels] = {"KICK", "PERC", "HIHAT", "BASS", "KEYS", "PAD", "TEXT", "FX",
                                              "STORM", "STREET", "SPEECH"};
constexpr double kSilentDb = -120.0;

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    double process(double x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

Biquad normalized(double b0, double b1, double b2, double a0, double a1, double a2) {
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

Biquad low_pass(double fs, double hz) {
    const double w = 2.0 * M_PI * hz / fs, alpha = std::sin(w) / (2.0 * M_SQRT1_2), c = std::cos(w);
    return normalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha);
}

Biquad high_pass(double fs, double hz) {
    const double w = 2.0 * M_PI * hz / fs, alpha = std::sin(w) / (2.0 * M_SQRT1_2), c = std::cos(w);
    return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
}

// Splits one channel's bottom end: "sub" is below 40 Hz (rumble and DC the 55 Hz HPF removes),
// "low" is 40-120 Hz, what the kick and bass fight over.
struct Bands {
    Biquad sub, low_hp, low_lp;
    explicit Bands(double fs) : sub(low_pass(fs, 40.0)), low_hp(high_pass(fs, 40.0)), low_lp(low_pass(fs, 120.0)) {}
    void process(double x, double& s, double& l) { s = sub.process(x); l = low_lp.process(low_hp.process(x)); }
};

double db_power(double p) { return p > 1e-30 ? 10.0 * std::log10(p) : kSilentDb; }
double db_amp(double a) { return a > 1e-15 ? 20.0 * std::log10(a) : kSilentDb; }
double lufs(double mean_power) { return mean_power > 1e-30 ? -0.691 + 10.0 * std::log10(mean_power) : kSilentDb; }

// BS.1770 gated integrated loudness over 400 ms block powers (mean zL^2 + mean zR^2).
double integrated_lufs(const std::vector<double>& blocks) {
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

// One stereo signal's running sums for a window (whole render or one section).
struct StereoStats {
    double k_energy = 0, e_l = 0, e_r = 0, e_lr = 0, e_sub = 0, e_low = 0, peak = 0;
    long frames = 0;
    void add(const StereoStats& o) {
        k_energy += o.k_energy; e_l += o.e_l; e_r += o.e_r; e_lr += o.e_lr; e_sub += o.e_sub; e_low += o.e_low;
        peak = std::max(peak, o.peak); frames += o.frames;
    }
};

struct Window {
    StereoStats out, dry, track[kChannels];
    std::vector<double> out_blocks;         // 400 ms output block powers, for gated LUFS
    long audible_blocks[kChannels] = {};    // blocks a channel sits within 30 dB of the dry mix
    long mix_blocks = 0;                    // blocks where the dry mix itself is above -70
    double pre_limit_peak = 0, e_pre_limit = 0, e_post_limit = 0, e_pre_hpf = 0, e_post_hpf = 0;
    long over_knee = 0, samples = 0;
    double seconds = 0;
    double peak_at = 0;                     // render time of the output peak, in seconds
};

// Filters and the 100 ms sub-block sums that become 400 ms blocks.
struct Meter {
    KWeight out_k[2], dry_k[2];
    Bands out_b[2], dry_b[2];
    std::vector<KWeight> track_k;
    std::vector<Bands> track_b;
    explicit Meter(double fs)
        : out_k{KWeight(fs), KWeight(fs)}, dry_k{KWeight(fs), KWeight(fs)},
          out_b{Bands(fs), Bands(fs)}, dry_b{Bands(fs), Bands(fs)},
          track_k(kChannels * 2, KWeight(fs)), track_b(kChannels * 2, Bands(fs)) {}
};

struct SubBlock { double out = 0, dry = 0, track[kChannels] = {}; long frames = 0; };

void accumulate(StereoStats& s, double l, double r, double kl, double kr, Bands& bl, Bands& br) {
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

std::string json_escape(const std::string& s) {
    std::string o;
    for (char c : s) { if (c == '"' || c == '\\') o += '\\'; o += c; }
    return o;
}

void write_signal(FILE* f, const StereoStats& s, const StereoStats& ref, bool with_ref) {
    const double e = s.e_l + s.e_r;
    const double pan = e > 1e-30 ? (s.e_r - s.e_l) / e : 0.0;
    fprintf(f, "\"rms_dbfs\": %.2f, \"peak_dbfs\": %.2f, \"pan\": %.3f, \"sub_pct\": %.1f, \"low_pct\": %.1f",
            db_power(s.frames ? e / (2.0 * s.frames) : 0.0), db_amp(s.peak), pan,
            e > 1e-30 ? 100.0 * s.e_sub / e : 0.0, e > 1e-30 ? 100.0 * s.e_low / e : 0.0);
    if (with_ref)
        fprintf(f, ", \"rel_lu\": %.2f", ref.k_energy > 1e-30 && s.k_energy > 1e-30
                ? 10.0 * std::log10(s.k_energy / ref.k_energy) : kSilentDb);
}

void write_window(FILE* f, const Window& w, const char* indent) {
    const double out_e = w.out.e_l + w.out.e_r;
    const double out_rms = db_power(w.out.frames ? out_e / (2.0 * w.out.frames) : 0.0);
    const double corr = w.out.e_l > 1e-30 && w.out.e_r > 1e-30 ? w.out.e_lr / std::sqrt(w.out.e_l * w.out.e_r) : 0.0;
    fprintf(f, "%s\"seconds\": %.1f,\n", indent, w.seconds);
    fprintf(f, "%s\"out\": {\"lufs\": %.2f, \"peak_dbfs\": %.2f, \"rms_dbfs\": %.2f, \"crest_db\": %.2f, "
               "\"balance_db\": %.2f, \"correlation\": %.3f, \"sub_pct\": %.1f, \"low_pct\": %.1f, "
               "\"peak_at_s\": %.2f},\n", indent,
            integrated_lufs(w.out_blocks), db_amp(w.out.peak), out_rms, db_amp(w.out.peak) - out_rms,
            w.out.e_l > 1e-30 && w.out.e_r > 1e-30 ? 10.0 * std::log10(w.out.e_r / w.out.e_l) : 0.0, corr,
            out_e > 1e-30 ? 100.0 * w.out.e_sub / out_e : 0.0, out_e > 1e-30 ? 100.0 * w.out.e_low / out_e : 0.0,
            w.peak_at);
    fprintf(f, "%s\"bus\": {\"pre_limit_peak_dbfs\": %.2f, \"over_knee_pct\": %.2f, \"limiter_gr_db\": %.2f, "
               "\"hpf_loss_db\": %.2f},\n", indent,
            db_amp(w.pre_limit_peak), w.samples ? 100.0 * w.over_knee / w.samples : 0.0,
            w.e_pre_limit > 1e-30 ? 10.0 * std::log10(w.e_post_limit / w.e_pre_limit) : 0.0,
            w.e_pre_hpf > 1e-30 ? 10.0 * std::log10(w.e_post_hpf / w.e_pre_hpf) : 0.0);
    fprintf(f, "%s\"dry\": {", indent);
    write_signal(f, w.dry, w.dry, false);
    fprintf(f, "}");
    // "tracks" are the 8 Pulsar tracks; "sources" the storm, street and speech voices.
    for (int group = 0; group < 2; group++) {
        const int from = group ? kTracks : 0, to = group ? kChannels : kTracks;
        fprintf(f, ",\n%s\"%s\": [\n", indent, group ? "sources" : "tracks");
        for (int t = from; t < to; t++) {
            fprintf(f, "%s  {\"track\": %d, \"name\": \"%s\", ", indent, t, kChannelNames[t]);
            write_signal(f, w.track[t], w.dry, true);
            fprintf(f, ", \"audible_pct\": %.1f}%s\n",
                    w.mix_blocks ? 100.0 * w.audible_blocks[t] / w.mix_blocks : 0.0, t + 1 < to ? "," : "");
        }
        fprintf(f, "%s]", indent);
    }
}

void print_window(const char* label, const Window& w) {
    const double rel_den = w.dry.k_energy;
    printf("  %-10s %5.0fs  LUFS %6.1f  peak %6.1f  pre-limit %+5.1f dBFS  knee %5.1f%%  GR %5.1f dB |",
           label, w.seconds, integrated_lufs(w.out_blocks), db_amp(w.out.peak), db_amp(w.pre_limit_peak),
           w.samples ? 100.0 * w.over_knee / w.samples : 0.0,
           w.e_pre_limit > 1e-30 ? 10.0 * std::log10(w.e_post_limit / w.e_pre_limit) : 0.0);
    for (int t = 0; t < kChannels; t++) {
        const double e = w.track[t].k_energy;
        if (e > 1e-30 && rel_den > 1e-30) printf(" %6.1f", 10.0 * std::log10(e / rel_den));
        else printf("      -");
    }
    printf("\n");
}

struct Run {
    Window whole;
    std::map<int, Window> sections;
    std::vector<float> pcm;
};

void close_block(Run& run, int section, std::vector<SubBlock>& subs, Window& sec) {
    // A 400 ms block from the last four 100 ms sub-blocks, stepped every 100 ms (75% overlap).
    if (subs.size() < 4) return;
    SubBlock b;
    for (size_t i = subs.size() - 4; i < subs.size(); i++) {
        b.out += subs[i].out; b.dry += subs[i].dry; b.frames += subs[i].frames;
        for (int t = 0; t < kChannels; t++) b.track[t] += subs[i].track[t];
    }
    const double p = b.out / b.frames;
    run.whole.out_blocks.push_back(p);
    sec.out_blocks.push_back(p);
    if (lufs(b.dry / b.frames) > -70.0) {
        run.whole.mix_blocks++;
        sec.mix_blocks++;
        for (int t = 0; t < kChannels; t++)
            if (b.track[t] > b.dry * 1e-3) { run.whole.audible_blocks[t]++; sec.audible_blocks[t]++; }
    }
    (void)section;
}

bool measure(const PortDump& d, const std::vector<float>& trims_db, float dist, int sr, int block, int seconds,
             Run& run, std::vector<int>& section_order) {
    OrpheusEngine* e = orpheus_engine_create(static_cast<float>(sr));
    if (!load_dj_graph(e)) { orpheus_engine_destroy(e); return false; }
    auto taps = std::make_unique<OrpheusEngine::PulsarMeasureTaps>();
    e->pulsar_measure_taps = taps.get();
    stmlib::Random::Seed(static_cast<uint32_t>(d.seed));
    e->clock_bpm.store(d.bpm, std::memory_order_relaxed);
    apply(e, d.base);
    apply(e, d.overrides);
    const char* kDist = "org.balch.orpheus.plugins.distortion";
    orpheus_engine_set_port(e, kDist, "drive", dist);
    orpheus_engine_set_port(e, kDist, "mix", dist);
    auto trim = [&] {
        for (int t = 0; t < kTracks; t++)
            e->pulsar_track_audition_gain[t].store(
                std::min(1.5f, std::pow(10.0f, trims_db[t] / 20.0f)), std::memory_order_relaxed);
    };
    trim();
    orpheus_engine_set_port(e, kPulsarUri, "playing", 1.0f);

    Meter m(sr);
    const int blocks = seconds * sr / block;
    const char* env_outro = std::getenv("VIBE_OUTRO_AT");
    const int outro_block = env_outro ? static_cast<int>(std::atof(env_outro) * sr / block) : -1;
    const bool keep_pcm = std::getenv("VIBE_WAV") != nullptr;
    std::vector<float> buf(static_cast<size_t>(block) * 2);
    const long sub_frames = sr / 10;
    std::vector<SubBlock> subs(1);
    int section = -1;
    for (int b = 0; b < blocks; b++) {
        if (b == outro_block) orpheus_engine_set_port(e, kPulsarUri, "arrangement_outro_request", 1.0f);
        std::memset(taps.get(), 0, sizeof(OrpheusEngine::PulsarMeasureTaps));
        orpheus_engine_process(e, buf.data(), block);
        if (keep_pcm) run.pcm.insert(run.pcm.end(), buf.begin(), buf.end());

        const PulsarState* ps = e->pulsar_state;
        const int now = ps ? ps->section_state.current_section : -1;
        Window& sec = run.sections[now];
        if (std::find(section_order.begin(), section_order.end(), now) == section_order.end())
            section_order.push_back(now);
        StereoStats out, dry, tr[kChannels];
        for (int i = 0; i < block; i++) {
            const double l = buf[2 * i], r = buf[2 * i + 1];
            const double kl = m.out_k[0].process(l), kr = m.out_k[1].process(r);
            const double a = std::max(std::fabs(l), std::fabs(r));
            for (Window* w : {&run.whole, &sec})
                if (a > std::max(w->out.peak, out.peak)) w->peak_at = static_cast<double>(b) * block / sr + static_cast<double>(i) / sr;
            accumulate(out, l, r, kl, kr, m.out_b[0], m.out_b[1]);
            double dl = 0, dr = 0;
            SubBlock& sb = subs.back();
            sb.out += kl * kl + kr * kr;
            for (int t = 0; t < kChannels; t++) {
                const double tl = t < kTracks ? taps->track_l[t][i] : taps->source_l[t - kTracks][i];
                const double trr = t < kTracks ? taps->track_r[t][i] : taps->source_r[t - kTracks][i];
                dl += tl;
                dr += trr;
                const double tkl = m.track_k[2 * t].process(tl), tkr = m.track_k[2 * t + 1].process(trr);
                accumulate(tr[t], tl, trr, tkl, tkr, m.track_b[2 * t], m.track_b[2 * t + 1]);
                sb.track[t] += tkl * tkl + tkr * tkr;
            }
            const double dkl = m.dry_k[0].process(dl), dkr = m.dry_k[1].process(dr);
            accumulate(dry, dl, dr, dkl, dkr, m.dry_b[0], m.dry_b[1]);
            sb.dry += dkl * dkl + dkr * dkr;
            if (++sb.frames == sub_frames) {
                close_block(run, now, subs, sec);
                subs.emplace_back();
                if (subs.size() > 4) subs.erase(subs.begin());
            }
            for (int c = 0; c < 2; c++) {
                const float pre = c ? taps->pre_limit_r[i] : taps->pre_limit_l[i];
                const float post = soft_limit(pre);
                for (Window* w : {&run.whole, &sec}) {
                    w->pre_limit_peak = std::max(w->pre_limit_peak, static_cast<double>(std::fabs(pre)));
                    w->e_pre_limit += static_cast<double>(pre) * pre;
                    w->e_post_limit += static_cast<double>(post) * post;
                    const double ph = c ? taps->pre_hpf_r[i] : taps->pre_hpf_l[i];
                    const double qh = c ? taps->post_hpf_r[i] : taps->post_hpf_l[i];
                    w->e_pre_hpf += ph * ph;
                    w->e_post_hpf += qh * qh;
                    if (std::fabs(pre) > 0.5f) w->over_knee++;
                    w->samples++;
                }
            }
        }
        for (Window* w : {&run.whole, &sec}) {
            w->out.add(out);
            w->dry.add(dry);
            for (int t = 0; t < kChannels; t++) w->track[t].add(tr[t]);
            w->seconds += static_cast<double>(block) / sr;
        }

        if (now != section) {
            // Kotlin's per-section re-push, applied on the flip instead of after its 5 Hz poll.
            section = now;
            auto it = d.sections.find(section);
            if (it != d.sections.end()) apply(e, it->second);
            apply(e, d.overrides);
            trim();
        }
    }
    e->pulsar_measure_taps = nullptr;
    orpheus_engine_destroy(e);
    return run.whole.out.peak > 1e-4;
}

std::vector<std::string> section_names(const std::string& ports) {
    // The dump's .tsv sidecar lists the arrangement's section names in order.
    std::vector<std::string> names;
    std::ifstream in(std::filesystem::path(ports).replace_extension(".tsv"));
    std::string line;
    while (std::getline(in, line)) {
        if (line.rfind("sections\t", 0) != 0) continue;
        std::stringstream ss(line.substr(9));
        std::string n;
        while (std::getline(ss, n, ',')) names.push_back(n);
    }
    return names;
}

}  // namespace

bool run_vibe_measure() {
    printf("\n=== Vibe measure ===\n");
    const char* env_ports = std::getenv("VIBE_PORTS");
    if (!g_filtered_run && !env_ports) {
        printf("  skipped: name the suite on the command line or set VIBE_PORTS\n");
        return true;
    }
    const std::string ports = env_ports ? env_ports : "features/pulsar/build/vibe-recordings/orphic_lore.ports";
    PortDump dump;
    if (!parse_dump(ports, dump)) {
        printf("  FAIL: cannot read %s (run VibePortDumpRenderHarness first)\n", ports.c_str());
        return false;
    }
    // A dump without the plugin's own port values (written ahead of the vibe, as the app's engine
    // start does) replays generative tracks empty: density_override is 0 in C++, -1 in Kotlin.
    const bool has_defaults = std::any_of(dump.base.begin(), dump.base.end(), [](const PortWrite& w) {
        return w.symbol == "track_0_density_override";
    });
    if (!has_defaults) {
        printf("  FAIL: %s has no plugin port defaults; re-run VibePortDumpRenderHarness\n", ports.c_str());
        return false;
    }
    parse_pulsar_set(std::getenv("VIBE_SET"), dump.overrides);

    std::vector<float> trims(kTracks, 0.0f);
    if (const char* t = std::getenv("VIBE_TRIM")) {
        std::stringstream ss(t);
        std::string kv;
        while (std::getline(ss, kv, ',')) {
            const size_t c = kv.find(':');
            if (c == std::string::npos) continue;
            const int track = std::stoi(kv.substr(0, c));
            if (track >= 0 && track < kTracks) trims[track] = std::stof(kv.substr(c + 1));
        }
    }
    const float dist = env_float("VIBE_DIST", 0.30f);
    const int sr = env_int("VIBE_SR", 48000), block = env_int("VIBE_BLOCK", 256);
    const int seconds = env_int("VIBE_SECONDS", 240);
    const std::string stem = std::filesystem::path(ports).stem().string();
    const char* env_tag = std::getenv("VIBE_TAG");
    const std::string name = stem + (env_tag ? std::string("_") + env_tag : "");
    const char* env_dir = std::getenv("VIBE_MEASURE_DIR");
    const std::string dir = env_dir ? env_dir : "liborpheus_dsp/test/output/vibe_measure";
    std::filesystem::create_directories(dir);
    const std::vector<std::string> names = section_names(ports);

    printf("  %s: seed %.0f, %.1f bpm, %d s, DIST %.2f\n", ports.c_str(), dump.seed, dump.bpm, seconds, dist);
    Run run;
    std::vector<int> order;
    const bool ok = measure(dump, trims, dist, sr, block, seconds, run, order);

    printf("  %-10s %6s  %-11s %-12s %-24s %-12s |   KICK   PERC  HIHAT   BASS   KEYS    PAD   TEXT     FX  STORM STREET SPEECH (LU vs dry mix)\n",
           "section", "", "", "", "", "");
    print_window("whole", run.whole);
    for (int s : order) {
        const std::string label = s >= 0 && s < static_cast<int>(names.size()) ? names[s] : std::to_string(s);
        print_window(label.c_str(), run.sections[s]);
    }

    const std::string json = dir + "/" + name + ".json";
    FILE* f = fopen(json.c_str(), "w");
    if (!f) { printf("  FAIL: cannot write %s\n", json.c_str()); return false; }
    fprintf(f, "{\n  \"vibe\": \"%s\", \"ports\": \"%s\", \"seconds\": %d, \"dist\": %.2f, \"sample_rate\": %d,\n",
            json_escape(stem).c_str(), json_escape(ports).c_str(), seconds, dist, sr);
    fprintf(f, "  \"trims_db\": [");
    for (int t = 0; t < kTracks; t++) fprintf(f, "%.2f%s", trims[t], t + 1 < kTracks ? ", " : "");
    fprintf(f, "],\n  \"set\": \"%s\",\n  \"whole\": {\n", json_escape(std::getenv("VIBE_SET") ? std::getenv("VIBE_SET") : "").c_str());
    write_window(f, run.whole, "    ");
    fprintf(f, "\n  },\n  \"sections\": [\n");
    for (size_t i = 0; i < order.size(); i++) {
        const int s = order[i];
        const std::string label = s >= 0 && s < static_cast<int>(names.size()) ? names[s] : std::to_string(s);
        fprintf(f, "    {\"index\": %d, \"name\": \"%s\",\n", s, json_escape(label).c_str());
        write_window(f, run.sections[s], "     ");
        fprintf(f, "\n    }%s\n", i + 1 < order.size() ? "," : "");
    }
    fprintf(f, "  ]\n}\n");
    fclose(f);
    printf("  wrote %s\n", json.c_str());
    if (!run.pcm.empty()) {
        const std::string wav = dir + "/" + name + ".wav";
        write_wav(wav.c_str(), run.pcm.data(), static_cast<int>(run.pcm.size() / 2), sr);
        printf("  wrote %s\n", wav.c_str());
    }
    printf("Vibe measure: %s\n", ok ? "OK" : "FLAGGED (the render was silent)");
    return ok;
}

// ── Meter checks: the harness's numbers against known signals ──

namespace {

// Gated loudness of a stereo signal through the same K-weighting and 400 ms / 75% blocks.
double lufs_of(const std::vector<float>& l, const std::vector<float>& r, double fs) {
    KWeight kl(fs), kr(fs);
    const size_t step = static_cast<size_t>(fs / 10);
    std::vector<double> subs, blocks;
    double acc = 0.0;
    for (size_t i = 0; i < l.size(); i++) {
        const double a = kl.process(l[i]), b = kr.process(r[i]);
        acc += a * a + b * b;
        if ((i + 1) % step == 0) {
            subs.push_back(acc / step);
            acc = 0.0;
            if (subs.size() >= 4) blocks.push_back((subs[subs.size() - 1] + subs[subs.size() - 2] +
                                                    subs[subs.size() - 3] + subs[subs.size() - 4]) / 4.0);
        }
    }
    return integrated_lufs(blocks);
}

std::vector<float> sine(double hz, double dbfs, double seconds, double fs) {
    std::vector<float> s(static_cast<size_t>(seconds * fs));
    const double a = std::pow(10.0, dbfs / 20.0);
    for (size_t i = 0; i < s.size(); i++) s[i] = static_cast<float>(a * std::sin(2.0 * M_PI * hz * i / fs));
    return s;
}

// Sub and low shares of a mono sine, in percent, after a second of settling.
void band_shares(double hz, double fs, double& sub_pct, double& low_pct) {
    Bands b(fs);
    const std::vector<float> s = sine(hz, -6.0, 3.0, fs);
    double e = 0, es = 0, el = 0;
    for (size_t i = 0; i < s.size(); i++) {
        double sub, low;
        b.process(s[i], sub, low);
        if (i < fs) continue;
        e += static_cast<double>(s[i]) * s[i];
        es += sub * sub;
        el += low * low;
    }
    sub_pct = 100.0 * es / e;
    low_pct = 100.0 * el / e;
}

bool check(bool ok, const char* what, double got) {
    printf("  [%s] %s (%.2f)\n", ok ? "PASS" : "FAIL", what, got);
    return ok;
}

}  // namespace

bool run_vibe_measure_meter_tests() {
    printf("\n=== Vibe measure meter ===\n");
    const double fs = 48000.0;
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { if (ok) ++pass; else ++fail; };

    const std::vector<float> tone = sine(997.0, -20.0, 5.0, fs), silence(tone.size(), 0.0f);
    double v = lufs_of(tone, tone, fs);
    tally(check(std::fabs(v + 20.0) < 0.2, "-20 dBFS 997 Hz sine in both channels reads -20 LUFS", v));
    v = lufs_of(tone, silence, fs);
    tally(check(std::fabs(v + 23.01) < 0.2, "the same sine in one channel reads 3 dB lower", v));
    std::vector<float> gated = tone;
    gated.insert(gated.end(), silence.begin(), silence.end());
    v = lufs_of(gated, gated, fs);
    tally(check(std::fabs(v + 20.0) < 0.2, "silence after the sine is gated out", v));

    double sub, low;
    band_shares(20.0, fs, sub, low);
    tally(check(sub > 70.0, "a 20 Hz sine is mostly sub", sub));
    band_shares(70.0, fs, sub, low);
    tally(check(low > 60.0 && sub < 25.0, "a 70 Hz sine is mostly low", low));
    band_shares(1000.0, fs, sub, low);
    tally(check(sub + low < 1.0, "a 1 kHz sine has no bottom end", sub + low));

    printf("\nVibe measure meter tests: %s\n", fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(pass, fail);
}
