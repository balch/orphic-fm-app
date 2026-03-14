// Single compilation unit for miniaudio implementation.
// Keep this in its own .cpp to isolate the large header from other TUs.
#define MA_IMPLEMENTATION
#define MA_NO_ENCODING   // We don't need file encoding
#define MA_NO_DECODING   // We don't need file decoding
#define MA_NO_GENERATION // We don't need waveform generation
#include "miniaudio.h"