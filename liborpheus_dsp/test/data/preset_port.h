// Shared struct for preset port data — included by all preset_*.h files
#pragma once

struct PresetPort {
    const char* uri;
    const char* symbol;
    float value;
    bool is_int;
};
