#pragma once

// DX, DX2, DX3 (engine indices 2..4): harmonics picks a patch, so it must stay pinned.
inline bool is_dx_engine_index(int engine_index) {
    return engine_index >= 2 && engine_index <= 4;
}
