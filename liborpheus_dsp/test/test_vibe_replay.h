// Shared by vibe_record and vibe_measure: reads the port writes VibePortDumpRenderHarness dumped
// from Kotlin and replays them into an engine.
#pragma once

#include "orpheus_engine.h"
#include <cstdlib>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace vibe_replay {

constexpr const char* kPulsarUri = "org.balch.orpheus.plugins.pulsar";

struct PortWrite { std::string uri, symbol; float value; };

struct PortDump {
    std::vector<PortWrite> base;
    std::map<int, std::vector<PortWrite>> sections;
    float seed = 0.0f;
    float bpm = 120.0f;
    std::vector<PortWrite> overrides;   // applied after the load and after every section flip
};

inline bool parse_dump(const std::string& path, PortDump& d) {
    std::ifstream in(path);
    if (!in) return false;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty()) continue;
        std::vector<std::string> f;
        std::stringstream ss(line);
        std::string cell;
        while (std::getline(ss, cell, '\t')) f.push_back(cell);
        if (f[0] == "#section" && f.size() == 5) {
            d.sections[std::stoi(f[1])].push_back({f[2], f[3], std::stof(f[4])});
        } else if (f.size() == 3) {
            PortWrite w{f[0], f[1], std::stof(f[2])};
            if (w.uri == kPulsarUri && w.symbol == "seed") d.seed = w.value;
            if (w.uri == kPulsarUri && w.symbol == "bpm") d.bpm = w.value;
            d.base.push_back(w);
        }
    }
    return true;
}

inline void apply(OrpheusEngine* e, const std::vector<PortWrite>& ws) {
    for (const PortWrite& w : ws) orpheus_engine_set_port(e, w.uri.c_str(), w.symbol.c_str(), w.value);
}

// "symbol=value,..." on the Pulsar plugin.
inline void parse_pulsar_set(const char* text, std::vector<PortWrite>& out) {
    if (!text) return;
    std::stringstream os(text);
    std::string kv;
    while (std::getline(os, kv, ',')) {
        const size_t eq = kv.find('=');
        if (eq == std::string::npos) continue;
        out.push_back({kPulsarUri, kv.substr(0, eq), std::stof(kv.substr(eq + 1))});
    }
}

inline int env_int(const char* name, int fallback) {
    const char* v = std::getenv(name);
    return v ? std::atoi(v) : fallback;
}

inline float env_float(const char* name, float fallback) {
    const char* v = std::getenv(name);
    return v ? static_cast<float>(std::atof(v)) : fallback;
}

}  // namespace vibe_replay
