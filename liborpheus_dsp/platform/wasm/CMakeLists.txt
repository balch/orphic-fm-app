cmake_minimum_required(VERSION 3.22)
project(orpheus_dsp_wasm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Eurorack source location ──────────────────────
set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../eurorack"
    CACHE PATH "Path to Mutable Instruments eurorack repository")
message(STATUS "Eurorack source: ${EURORACK_DIR}")

# ── Source directories ────────────────────────────
set(LIB_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../src")
set(LIB_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../../include")

# ── Plaits engine sources ─────────────────────────
file(GLOB PLAITS_ENGINE_SRC "${EURORACK_DIR}/plaits/dsp/engine/*.cc")
file(GLOB PLAITS_ENGINE2_SRC "${EURORACK_DIR}/plaits/dsp/engine2/*.cc")
file(GLOB PLAITS_SPEECH_SRC "${EURORACK_DIR}/plaits/dsp/speech/*.cc")
file(GLOB PLAITS_PM_SRC "${EURORACK_DIR}/plaits/dsp/physical_modelling/*.cc")
file(GLOB PLAITS_FM_SRC "${EURORACK_DIR}/plaits/dsp/fm/*.cc")
set(PLAITS_SRC
    ${PLAITS_ENGINE_SRC}
    ${PLAITS_ENGINE2_SRC}
    ${PLAITS_SPEECH_SRC}
    ${PLAITS_PM_SRC}
    ${PLAITS_FM_SRC}
    "${EURORACK_DIR}/plaits/dsp/voice.cc"
    "${EURORACK_DIR}/plaits/dsp/chords/chord_bank.cc"
    "${EURORACK_DIR}/plaits/resources.cc"
)

# ── stmlib sources ────────────────────────────────
set(STMLIB_SRC
    "${EURORACK_DIR}/stmlib/dsp/atan.cc"
    "${EURORACK_DIR}/stmlib/dsp/units.cc"
    "${EURORACK_DIR}/stmlib/utils/random.cc"
)

# ── Clouds sources ────────────────────────────────
set(CLOUDS_SRC
    "${EURORACK_DIR}/clouds/dsp/granular_processor.cc"
    "${EURORACK_DIR}/clouds/dsp/mu_law.cc"
    "${EURORACK_DIR}/clouds/dsp/correlator.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/frame_transformation.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/phase_vocoder.cc"
    "${EURORACK_DIR}/clouds/dsp/pvoc/stft.cc"
    "${EURORACK_DIR}/clouds/resources.cc"
)

# ── Rings sources ─────────────────────────────────
set(RINGS_SRC
    "${EURORACK_DIR}/rings/dsp/fm_voice.cc"
    "${EURORACK_DIR}/rings/dsp/part.cc"
    "${EURORACK_DIR}/rings/dsp/string_synth_part.cc"
    "${EURORACK_DIR}/rings/dsp/string.cc"
    "${EURORACK_DIR}/rings/dsp/resonator.cc"
    "${EURORACK_DIR}/rings/resources.cc"
)

# ── Warps sources ─────────────────────────────────
set(WARPS_SRC
    "${EURORACK_DIR}/warps/dsp/modulator.cc"
    "${EURORACK_DIR}/warps/dsp/oscillator.cc"
    "${EURORACK_DIR}/warps/dsp/vocoder.cc"
    "${EURORACK_DIR}/warps/dsp/filter_bank.cc"
    "${EURORACK_DIR}/warps/resources.cc"
)

# ── Marbles sources ───────────────────────────────
file(GLOB MARBLES_RANDOM_SRC "${EURORACK_DIR}/marbles/random/*.cc")
set(MARBLES_SRC
    ${MARBLES_RANDOM_SRC}
    "${EURORACK_DIR}/marbles/ramp/ramp_extractor.cc"
    "${EURORACK_DIR}/marbles/resources.cc"
)

# ── Orpheus engine sources ────────────────────────
set(ORPHEUS_SRC
    "${LIB_SRC}/orpheus_engine.cpp"
    "${LIB_SRC}/orpheus_graph.cpp"
    "${LIB_SRC}/orpheus_monitor.cpp"
    "${LIB_SRC}/orpheus_units.cpp"
    "${LIB_SRC}/orpheus_patch.cpp"
)

# ── WASM export wrappers ─────────────────────────
set(WASM_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/wasm_exports.cpp"
)

# ── WASM executable target ────────────────────────
add_executable(orpheus_dsp
    ${WASM_SRC}
    ${ORPHEUS_SRC}
    ${STMLIB_SRC}
    ${PLAITS_SRC}
    ${CLOUDS_SRC}
    ${RINGS_SRC}
    ${WARPS_SRC}
    ${MARBLES_SRC}
)

target_include_directories(orpheus_dsp PRIVATE
    "${LIB_INCLUDE}"
    "${LIB_SRC}"
    "${EURORACK_DIR}"
)

# ── Compile flags ─────────────────────────────────
target_compile_options(orpheus_dsp PRIVATE
    -O3
    -ffast-math
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-unused-variable
    -Wno-missing-field-initializers
    -Wno-unused-local-typedef
    -Wno-unused-private-field
    -Wno-char-subscripts
    -Wno-tautological-compare
    -Wno-implicit-const-int-float-conversion
)

# Define TEST to use non-ARM code paths in stmlib
target_compile_definitions(orpheus_dsp PRIVATE TEST)

# ── Emscripten link flags ────────────────────────
# Build as a JS module loadable in a Web Worker
target_link_options(orpheus_dsp PRIVATE
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
set_target_properties(orpheus_dsp PROPERTIES
    SUFFIX ".js"
    OUTPUT_NAME "orpheus_dsp"
)
