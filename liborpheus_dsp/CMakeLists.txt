cmake_minimum_required(VERSION 3.22)
project(orpheus_dsp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Eurorack source location ──────────────────────
# Set EURORACK_DIR to the path of the eurorack repo.
# Default: sibling directory or environment variable.
if(NOT DEFINED EURORACK_DIR)
    if(DEFINED ENV{EURORACK_DIR})
        set(EURORACK_DIR $ENV{EURORACK_DIR})
    else()
        set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../eurorack")
    endif()
endif()
message(STATUS "Eurorack source: ${EURORACK_DIR}")

# ── Compat shim include (must come BEFORE eurorack includes) ──
# This overrides stmlib.h macros for non-STM32 targets.
set(COMPAT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src")

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
    "src/orpheus_engine.cpp"
    "src/orpheus_graph.cpp"
    "src/orpheus_monitor.cpp"
    "src/orpheus_units.cpp"
    "src/orpheus_patch.cpp"
)

# ── Library target ────────────────────────────────
add_library(orpheus_dsp STATIC
    ${ORPHEUS_SRC}
    ${STMLIB_SRC}
    ${PLAITS_SRC}
    ${CLOUDS_SRC}
    ${RINGS_SRC}
    ${WARPS_SRC}
    ${MARBLES_SRC}
)

target_include_directories(orpheus_dsp PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_include_directories(orpheus_dsp PRIVATE
    "${COMPAT_DIR}"
    "${EURORACK_DIR}"
)

# Compile flags: treat warnings loosely for MI code (it targets GCC ARM)
target_compile_options(orpheus_dsp PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-unused-variable
    -Wno-missing-field-initializers
    -Wno-unused-local-typedef
    -Wno-unused-private-field
    -Wno-char-subscripts
    -Wno-tautological-compare
    -Wno-implicit-const-int-float-conversion
    "$<$<CONFIG:Release>:-O3>"
    "$<$<CONFIG:Release>:-ffast-math>"
)

# Define TEST to use non-ARM code paths in stmlib
target_compile_definitions(orpheus_dsp PRIVATE TEST)

# ── Desktop test executable (optional) ────────────
option(BUILD_TESTS "Build test executable" OFF)
if(BUILD_TESTS)
    add_executable(orpheus_dsp_test
        test/test_main.cpp
        test/test_units.cpp
        test/test_voices.cpp
        test/test_engine_render.cpp
        test/test_effects.cpp
        test/test_graph.cpp
        test/test_output_chain.cpp
        test/test_drums_graph.cpp
        test/test_snapshots.cpp
        test/test_benchmark.cpp
    )
    target_link_libraries(orpheus_dsp_test orpheus_dsp)
    target_include_directories(orpheus_dsp_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
        "${EURORACK_DIR}"
    )
    target_compile_definitions(orpheus_dsp_test PRIVATE TEST)
endif()
