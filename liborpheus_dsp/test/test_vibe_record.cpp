// Vibe recorder, second half: replays the port writes VibePortDumpRenderHarness dumped from
// Kotlin into the DJ graph and writes the mix plus each track soloed as WAVs, with a
// per-block CSV (section, pitch, harmonics and morph handed to the engine, volume, level) for each solo.
// Manual suite: it writes files, so it runs only when named or VIBE_PORTS is set.
//
//   VIBE_PORTS=features/pulsar/build/vibe-recordings/orphic_lore.ports \
//   VIBE_TRACKS=mix,5 VIBE_SECONDS=60 orpheus_dsp_test vibe_record
//
// VIBE_SR / VIBE_BLOCK change the render rate and callback size (default 48000 / 256).
// VIBE_OUTRO_AT=<seconds> arms the outro at that time.
//
// VIBE_SET=track_5_grain_fx=0,track_5_vibrato_depth=0 overrides Pulsar ports after the load
// and after every section flip, so a stage can be switched off without re-dumping; VIBE_TAG
// suffixes the file names so an A/B keeps both takes.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "test_vibe_replay.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "stmlib/utils/random.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr const char* kDefaultPorts = "features/pulsar/build/vibe-recordings/orphic_lore.ports";
using namespace vibe_replay;

// VIBE_SR and VIBE_BLOCK mimic another output stream, e.g. 44100 / 882 for Bluetooth on Android.
const int kSr = env_int("VIBE_SR", 48000);
const int kBlock = env_int("VIBE_BLOCK", 256);

// solo < 0 records the mix. A solo mutes the other tracks with the TRACKS mixer's mute,
// which leaves the arrangement and the band rolling exactly as in the mix.
bool record(const PortDump& d, int solo, int seconds, const std::string& wav, const std::string& csv) {
    OrpheusEngine* e = orpheus_engine_create(static_cast<float>(kSr));
    if (!load_dj_graph(e)) { orpheus_engine_destroy(e); return false; }
    stmlib::Random::Seed(static_cast<uint32_t>(d.seed));
    e->clock_bpm.store(d.bpm, std::memory_order_relaxed);
    apply(e, d.base);
    apply(e, d.overrides);
    orpheus_engine_set_port(e, kPulsarUri, "playing", 1.0f);
    auto mute = [&] {
        for (int t = 0; t < kNumPulsarTracks; t++)
            e->pulsar_track_mute[t].store(solo >= 0 && t != solo ? 1 : 0, std::memory_order_relaxed);
    };
    mute();

    FILE* log = nullptr;
    if (solo >= 0) {
        log = fopen(csv.c_str(), "w");
        if (log) fprintf(log, "seconds,section,bar,note,harmonics,morph,volume,rms\n");
    }

    const int blocks = seconds * kSr / kBlock;
    // VIBE_OUTRO_AT=<seconds> arms the outro then, as the song-length timer does in the app.
    const char* env_outro = std::getenv("VIBE_OUTRO_AT");
    const int outro_block = env_outro ? static_cast<int>(std::atof(env_outro) * kSr / kBlock) : -1;
    std::vector<float> pcm;
    pcm.reserve(static_cast<size_t>(blocks) * kBlock * 2);
    std::vector<float> buf(static_cast<size_t>(kBlock) * 2);
    int section = -1;
    float peak = 0.0f;
    for (int b = 0; b < blocks; b++) {
        if (b == outro_block) orpheus_engine_set_port(e, kPulsarUri, "arrangement_outro_request", 1.0f);
        orpheus_engine_process(e, buf.data(), kBlock);
        pcm.insert(pcm.end(), buf.begin(), buf.end());
        double sum = 0.0;
        for (int i = 0; i < kBlock * 2; i++) {
            sum += static_cast<double>(buf[i]) * buf[i];
            peak = std::max(peak, std::fabs(buf[i]));
        }
        const PulsarState* ps = e->pulsar_state;
        const int now = ps ? ps->section_state.current_section : -1;
        if (now != section) {
            // Kotlin's per-section re-push, applied on the flip instead of after its 5 Hz poll.
            section = now;
            if (solo < 0) printf("    section %d at %.3f s\n", section, static_cast<double>(b) * kBlock / kSr);
            auto it = d.sections.find(section);
            if (it != d.sections.end()) apply(e, it->second);
            apply(e, d.overrides);
            mute();
        }
        if (log) {
            fprintf(log, "%.4f,%d,%d,%.3f,%.3f,%.3f,%.3f,%.5f\n",
                    static_cast<double>(b) * kBlock / kSr, section, ps ? ps->loop_count : 0,
                    e->pulsar_track_note_debug[solo].load(std::memory_order_relaxed),
                    e->pulsar_track_mod_harmonics_debug[solo].load(std::memory_order_relaxed),
                    e->pulsar_track_mod_morph_debug[solo].load(std::memory_order_relaxed),
                    e->pulsar_track_volume_debug[solo].load(std::memory_order_relaxed),
                    std::sqrt(sum / (kBlock * 2)));
        }
    }
    if (log) fclose(log);
    orpheus_engine_destroy(e);
    write_wav(wav.c_str(), pcm.data(), static_cast<int>(pcm.size() / 2), kSr);
    printf("  %s peak %.4f\n", wav.c_str(), peak);
    return peak > 1e-4f;
}

}  // namespace

bool run_vibe_record() {
    printf("\n=== Vibe recorder ===\n");
    const char* env_ports = std::getenv("VIBE_PORTS");
    if (!g_filtered_run && !env_ports) {
        printf("  skipped: name the suite on the command line or set VIBE_PORTS\n");
        return true;
    }
    const std::string ports = env_ports ? env_ports : kDefaultPorts;
    PortDump dump;
    if (!parse_dump(ports, dump)) {
        printf("  FAIL: cannot read %s (run VibePortDumpRenderHarness first)\n", ports.c_str());
        return false;
    }

    const std::string stem = std::filesystem::path(ports).stem().string();
    const char* env_dir = std::getenv("VIBE_RECORD_DIR");
    const std::string dir = env_dir ? env_dir : "liborpheus_dsp/test/output/vibe_record/" + stem;
    std::filesystem::create_directories(dir);
    const int seconds = env_int("VIBE_SECONDS", 60);
    parse_pulsar_set(std::getenv("VIBE_SET"), dump.overrides);
    const char* env_tag = std::getenv("VIBE_TAG");
    const std::string tag = env_tag ? std::string("_") + env_tag : "";

    // VIBE_TRACKS: comma list of "mix" and track numbers; default the mix and all eight.
    std::vector<int> targets;
    const char* env_tracks = std::getenv("VIBE_TRACKS");
    std::stringstream ss(env_tracks ? env_tracks : "mix,0,1,2,3,4,5,6,7");
    std::string tok;
    while (std::getline(ss, tok, ',')) targets.push_back(tok == "mix" ? -1 : std::stoi(tok));

    printf("  %s: %zu writes, %zu sections, seed %.0f, %.1f bpm, %d s\n", ports.c_str(),
           dump.base.size(), dump.sections.size(), dump.seed, dump.bpm, seconds);
    bool ok = true;
    for (int t : targets) {
        const std::string name = (t < 0 ? "mix" : "track" + std::to_string(t)) + tag;
        ok &= record(dump, t, seconds, dir + "/" + name + ".wav", dir + "/" + name + ".csv");
    }
    printf("Vibe recorder: %s\n", ok ? "OK" : "FLAGGED (a recording was silent)");
    return ok;
}
