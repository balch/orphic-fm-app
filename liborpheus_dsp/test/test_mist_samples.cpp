// Mist audition clips: one fixed phrase rendered through the kernel, one setting changed
// per clip, so the sounds can be learned by ear. Manual suite, writes WAV files.
#include "test_harness.h"
#include "stmlib/utils/gate_flags.h"
#include "stmlib/utils/random.h"
#include <cctype>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include "../src/pulsar_mist.h"

namespace {

constexpr int kSr = 48000;
constexpr int kBlock = 512;
constexpr int kSixteenth = 7200;   // 100 bpm
constexpr int kTailSamples = 3 * kSr;

struct Note { int midi, start, length; };   // start and length in sixteenths
constexpr Note kPhrase[] = {{60, 0, 3}, {64, 4, 3}, {67, 8, 3}, {72, 12, 3}, {60, 16, 12}};

const uint8_t kMajorScale[7] = {0, 2, 4, 5, 7, 9, 11};

struct Clip {
    std::string group, slug, title;
    mist::Params p;
};

mist::Params base_patch() {
    mist::Params p{};
    p.note = 60.0f;
    p.harmonics = 0.30f;
    p.timbre = 0.50f;
    p.morph = 0.45f;
    p.mode = mist::kModeBurst;
    p.scatter_mode = mist::kScatterChord;
    p.voicing = mist::kVoicingEven;
    p.scatter = 0.0f;
    p.rate = 0.70f;
    p.random_timing = false;
    p.shape = 0.50f;
    p.blend = 1.0f;
    return p;
}

const char* mode_name(int m) {
    return m == mist::kModeBurst ? "BURST" : (m == mist::kModeSingle ? "SINGLE" : "CONTINUOUS");
}
const char* scatter_name(int s) {
    return s == mist::kScatterChord ? "CHORD" : (s == mist::kScatterScale ? "SCALE" : "RAW");
}
const char* voicing_name(int v) {
    return v == mist::kVoicingEven ? "EVEN" : (v == mist::kVoicingCentered ? "CENTERED" : "RISING");
}

std::string fmt(const char* f, ...) __attribute__((format(printf, 1, 2)));
std::string fmt(const char* f, ...) {
    char buf[160];
    va_list ap;
    va_start(ap, f);
    vsnprintf(buf, sizeof buf, f, ap);
    va_end(ap);
    return buf;
}

// Value as hundredths for file names: 0.33 -> "033".
std::string hundredths(float v) { return fmt("%03d", static_cast<int>(std::lround(v * 100.0f))); }

std::string settings_of(const mist::Params& p) {
    return fmt("note=phrase harmonics=%.2f timbre=%.2f morph=%.2f mode=%s scatter_mode=%s "
               "voicing=%s scatter=%.2f rate=%.2f random_timing=%s shape=%.2f blend=%.2f",
               p.harmonics, p.timbre, p.morph, mode_name(p.mode), scatter_name(p.scatter_mode),
               voicing_name(p.voicing), p.scatter, p.rate, p.random_timing ? "true" : "false",
               p.shape, p.blend);
}

std::vector<Clip> build_clips() {
    std::vector<Clip> clips;
    auto add = [&](const char* group, std::string slug, std::string title, const mist::Params& p) {
        clips.push_back({group, std::move(slug), std::move(title), p});
    };

    for (int b = 0; b < 12; b++) {
        mist::Params p = base_patch();
        p.harmonics = (static_cast<float>(b) + 0.5f) / 12.0f;
        add("bank", fmt("bank_%d", b + 1), fmt("bank %d of 12", b + 1), p);
    }
    for (float v : {0.0f, 0.25f, 0.5f, 0.75f, 1.0f}) {
        mist::Params p = base_patch();
        p.timbre = v;
        add("timbre", "position_" + hundredths(v), fmt("position in bank %.2f", v), p);
    }
    for (float v : {0.0f, 0.25f, 0.5f, 0.75f, 1.0f}) {
        mist::Params p = base_patch();
        p.morph = v;
        add("length", "grain_" + hundredths(v), fmt("grain length %.2f", v), p);
    }
    for (float v : {0.0f, 0.33f, 0.66f, 1.0f}) {
        mist::Params p = base_patch();
        p.shape = v;
        add("shape", "shape_" + hundredths(v), fmt("grain shape %.2f", v), p);
    }
    for (float v : {0.0f, 0.33f, 0.66f, 1.0f}) {
        mist::Params p = base_patch();
        p.blend = v;
        add("blend", "blend_" + hundredths(v), fmt("blend %.2f, 0 is oscillator, 1 is grains", v), p);
    }
    for (float r : {0.2f, 0.5f, 0.8f, 1.0f}) {
        for (bool rnd : {false, true}) {
            mist::Params p = base_patch();
            p.rate = r;
            p.random_timing = rnd;
            add("burst", "rate_" + hundredths(r) + (rnd ? "_random" : "_even"),
                fmt("rate %.2f, %s timing", r, rnd ? "random" : "even"), p);
        }
    }
    for (float m : {0.2f, 0.5f, 0.9f}) {
        mist::Params p = base_patch();
        p.mode = mist::kModeSingle;
        p.morph = m;
        add("single", "grain_" + hundredths(m), fmt("single grain per note, length %.2f", m), p);
    }
    for (float r : {0.3f, 0.6f, 1.0f}) {
        for (bool rnd : {false, true}) {
            mist::Params p = base_patch();
            p.mode = mist::kModeContinuous;
            p.rate = r;
            p.random_timing = rnd;
            add("continuous", "rate_" + hundredths(r) + (rnd ? "_random" : "_even"),
                fmt("clocked, rate %.2f, %s timing", r, rnd ? "random" : "even"), p);
        }
    }
    for (int sm : {mist::kScatterChord, mist::kScatterScale, mist::kScatterRaw}) {
        for (float s : {0.25f, 0.5f, 1.0f}) {
            mist::Params p = base_patch();
            p.scatter_mode = sm;
            p.scatter = s;
            std::string name = scatter_name(sm);
            for (char& c : name) c = static_cast<char>(std::tolower(c));
            add("scatter", name + "_" + hundredths(s), fmt("%s scatter %.2f", name.c_str(), s), p);
        }
    }
    for (int v : {mist::kVoicingEven, mist::kVoicingCentered, mist::kVoicingRising}) {
        for (int sm : {mist::kScatterChord, mist::kScatterScale}) {
            for (float s : {0.5f, 1.0f}) {
                mist::Params p = base_patch();
                p.voicing = v;
                p.scatter_mode = sm;
                p.scatter = s;
                std::string vn = voicing_name(v), sn = scatter_name(sm);
                for (char& c : vn) c = static_cast<char>(std::tolower(c));
                for (char& c : sn) c = static_cast<char>(std::tolower(c));
                add("voicing", vn + "_" + sn + "_" + hundredths(s),
                    fmt("%s scatter %.2f, %s", sn.c_str(), s, vn.c_str()), p);
            }
        }
    }

    // Six contrasting patches that show the range.
    {
        mist::Params p = base_patch();   // short even stutter
        p.harmonics = 0.30f; p.morph = 0.0f; p.rate = 1.0f; p.shape = 0.3f;
        add("combo", "stutter", "short even stutter", p);
    }
    {
        mist::Params p = base_patch();   // long random wash with wide chord scatter
        p.harmonics = 0.55f; p.morph = 0.9f; p.rate = 0.8f; p.random_timing = true;
        p.scatter_mode = mist::kScatterChord; p.scatter = 0.9f; p.voicing = mist::kVoicingCentered;
        add("combo", "wash", "long random wash, wide chord scatter", p);
    }
    {
        mist::Params p = base_patch();   // clocked sparkle
        p.harmonics = 0.75f; p.timbre = 0.7f; p.morph = 0.05f; p.mode = mist::kModeContinuous;
        p.rate = 0.9f; p.random_timing = true; p.scatter_mode = mist::kScatterChord;
        p.scatter = 0.7f; p.voicing = mist::kVoicingRising; p.shape = 0.4f;
        add("combo", "sparkle", "clocked sparkle, short rising grains", p);
    }
    {
        mist::Params p = base_patch();   // slow swell
        p.harmonics = 0.15f; p.timbre = 0.3f; p.morph = 1.0f; p.rate = 0.3f; p.shape = 1.0f;
        add("combo", "swell", "slow swell, long grains", p);
    }
    {
        mist::Params p = base_patch();   // oscillator audible under the grains
        p.harmonics = 0.45f; p.morph = 0.5f; p.rate = 0.6f; p.blend = 0.5f;
        add("combo", "half_blend", "half blend, oscillator under the grains", p);
    }
    {
        mist::Params p = base_patch();   // raw detuned smear
        p.harmonics = 0.85f; p.timbre = 0.6f; p.morph = 0.85f; p.rate = 0.7f; p.random_timing = true;
        p.scatter_mode = mist::kScatterRaw; p.scatter = 0.6f;
        add("combo", "raw_smear", "raw detuned smear, long grains", p);
    }
    return clips;
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

// Renders the phrase and returns interleaved stereo.
std::vector<float> render_clip(const Clip& clip) {
    stmlib::Random::Seed(0x1234);
    auto st = std::make_unique<PulsarMistState>();

    mist::PitchContext pc;
    pc.scale_mask = mist::pitch_class_mask(0, kMajorScale, 7);
    pc.chord_mask = mist::chord_mask(0, 0, kMajorScale, 7);
    pc.note_lo = 36;
    pc.note_hi = 96;

    const Note& last = kPhrase[sizeof(kPhrase) / sizeof(kPhrase[0]) - 1];
    const int total = (last.start + last.length) * kSixteenth + kTailSamples;
    const int blocks = (total + kBlock - 1) / kBlock;

    std::vector<float> out;
    out.reserve(static_cast<size_t>(blocks) * kBlock * 2);

    const float rise = 1.0f - std::exp(-1.0f / (0.005f * kSr));
    const float fall = 1.0f - std::exp(-1.0f / (0.080f * kSr));
    float env = 0.0f;
    bool gate = false;
    int cur = -1;   // phrase index of the note being held
    mist::Params p = clip.p;
    float osc[kBlock], mid[kBlock], side[kBlock];

    for (int b = 0; b < blocks; b++) {
        const int b0 = b * kBlock;
        // A note that ended in an earlier block drops here, one block late at worst.
        if (gate && (kPhrase[cur].start + kPhrase[cur].length) * kSixteenth <= b0) gate = false;

        mist::Gate g;
        g.gate_pre = gate;
        g.gate_post = gate;
        for (int n = 0; n < static_cast<int>(sizeof(kPhrase) / sizeof(kPhrase[0])); n++) {
            const int s = kPhrase[n].start * kSixteenth;
            if (s >= b0 && s < b0 + kBlock) {
                g.trig_off = s - b0;
                g.gate_post = true;
                g.note_on = true;
                cur = n;
                p.note = static_cast<float>(kPhrase[n].midi);
            }
        }
        gate = g.gate_post;

        // A 16th is longer than a block, so a block holds at most one tick.
        int ticks[2];
        int tick_count = 0;
        for (int i = 0; i < kBlock; i++)
            if ((b0 + i) % kSixteenth == 0 && tick_count < 2) ticks[tick_count++] = i;
        g.ticks = ticks;
        g.tick_count = tick_count;

        mist::process_mist_block(*st, p, g, pc, static_cast<float>(kSr), osc, mid, side, kBlock);

        for (int i = 0; i < kBlock; i++) {
            const bool high = (i < g.trig_off) ? g.gate_pre : g.gate_post;
            env += ((high ? 1.0f : 0.0f) - env) * (high ? rise : fall);
            const float o = osc[i] * env;
            out.push_back(o + mid[i] + side[i] * mist::kWidth);
            out.push_back(o + mid[i] - side[i] * mist::kWidth);
        }
    }
    return out;
}

// Manual only: the suite writes files, so a no-argument run skips it unless the
// output directory is named explicitly.
bool should_render() {
    return g_filtered_run || std::getenv("MIST_SAMPLES_DIR") != nullptr;
}

}  // namespace

bool run_mist_samples() {
    printf("\n=== Mist audition clips ===\n");
    if (!should_render()) {
        printf("  skipped: name the suite on the command line or set MIST_SAMPLES_DIR\n");
        return true;
    }

    const char* env_dir = std::getenv("MIST_SAMPLES_DIR");
    const std::string dir = env_dir ? env_dir : "liborpheus_dsp/test/output/mist";
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    if (ec) {
        fprintf(stderr, "Cannot create %s: %s\n", dir.c_str(), ec.message().c_str());
        return false;
    }

    const std::vector<Clip> clips = build_clips();
    FILE* manifest = fopen((dir + "/manifest.tsv").c_str(), "w");
    if (!manifest) {
        fprintf(stderr, "Cannot open manifest in %s\n", dir.c_str());
        return false;
    }
    fprintf(manifest, "file\tgroup\ttitle\tsettings\tpeak\tseconds\n");

    bool ok = true;
    int written = 0;
    std::string last_group;
    int index = 0;
    for (const Clip& clip : clips) {
        if (clip.group != last_group) { last_group = clip.group; index = 0; }
        index++;
        const std::string file = fmt("%s_%02d_%s.wav", clip.group.c_str(), index, clip.slug.c_str());

        const std::vector<float> pcm = render_clip(clip);
        float peak = 0.0f;
        for (float x : pcm) peak = std::max(peak, std::fabs(x));
        const bool finite = all_finite(pcm);
        const int frames = static_cast<int>(pcm.size() / 2);

        write_wav((dir + "/" + file).c_str(), pcm.data(), frames, kSr);
        written++;
        fprintf(manifest, "%s\t%s\t%s\t%s\t%.4f\t%.2f\n", file.c_str(), clip.group.c_str(),
                clip.title.c_str(), settings_of(clip.p).c_str(), peak,
                static_cast<double>(frames) / kSr);
        printf("  %s peak %.4f\n", file.c_str(), peak);

        if (!finite) { printf("  FLAGGED %s: non-finite sample\n", file.c_str()); ok = false; }
        else if (peak < 1e-4f) { printf("  FLAGGED %s: silent\n", file.c_str()); ok = false; }
        else if (peak > 1.0f) { printf("  FLAGGED %s: peak above 1.0\n", file.c_str()); ok = false; }
    }
    fclose(manifest);

    printf("Wrote %d clips and manifest.tsv to %s\n", written, dir.c_str());
    printf("Mist audition clips: %s\n", ok ? "OK" : "FLAGGED");
    return ok;
}
