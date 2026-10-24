cmake_minimum_required(VERSION 3.22)
project(orpheus_dsp_wasm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Eurorack source location ──────────────────────
set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../eurorack"
    CACHE PATH "Path to Mutable Instruments eurorack repository")
message(STATUS "Eurorack source: ${EURORACK_DIR}")

# ── DSP library ───────────────────────────────────
# Reuse the root static library so this build can never drift from its source list.
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../.."
    "${CMAKE_CURRENT_BINARY_DIR}/liborpheus_dsp"
)

# ── WASM executable target ────────────────────────
# Named apart from the library target; OUTPUT_NAME keeps the orpheus_dsp.js/.wasm artifacts.
add_executable(orpheus_dsp_wasm
    "${CMAKE_CURRENT_SOURCE_DIR}/wasm_exports.cpp"
)
target_link_libraries(orpheus_dsp_wasm PRIVATE orpheus_dsp)
target_include_directories(orpheus_dsp_wasm PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../src"
    "${EURORACK_DIR}"
)
target_compile_options(orpheus_dsp_wasm PRIVATE -O3 -ffast-math)
# stmlib takes its non-ARM code paths under TEST, as in the library.
target_compile_definitions(orpheus_dsp_wasm PRIVATE TEST)

# ── Emscripten link flags ────────────────────────
# Build as a JS module loadable in a Web Worker
target_link_options(orpheus_dsp_wasm PRIVATE
    # Module output format
    -sMODULARIZE=1
    -sEXPORT_NAME=OrpheusDSP
    -sENVIRONMENT=worker

    # Memory
    -sALLOW_MEMORY_GROWTH=1
    -sINITIAL_MEMORY=33554432  # 32 MB

    # No main() entry point
    --no-entry

    # Runtime methods accessible from JS
    -sEXPORTED_RUNTIME_METHODS=ccall,cwrap,HEAPF32,HEAPU8,wasmMemory
    -sEXPORTED_FUNCTIONS=_malloc,_free

    # Optimisation
    -O3
    -flto
)

# Output files: orpheus_dsp.js + orpheus_dsp.wasm
set_target_properties(orpheus_dsp_wasm PROPERTIES
    SUFFIX ".js"
    OUTPUT_NAME "orpheus_dsp"
)
