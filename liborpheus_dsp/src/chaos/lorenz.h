#pragma once
#include "orpheus_unit_chaos.h"  // for ChaosVoiceState

namespace chaos {
    // Advance state by one audio sample. Returns chaos signal in roughly [-1, 1].
    // Parameters: harmonics in [0,1], timbre in [0,1], note_freq Hz, sr Hz.
    // Implementations may ignore params they don't use.
    float process_lorenz(ChaosVoiceState& s, float harmonics, float timbre,
                         float note_freq, float sr);
}
