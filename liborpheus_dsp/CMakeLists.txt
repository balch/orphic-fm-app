cmake_minimum_required(VERSION 3.22)
project(orpheus_dsp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Default build type: Release ─────────────────────────────────────
# Single-config generators (Make, Ninja) use CMAKE_BUILD_TYPE.
# Multi-config generators (Xcode, MSVC) use CMAKE_CONFIGURATION_TYPES
# at build time, so this guard leaves them alone.
# Respects any explicit -DCMAKE_BUILD_TYPE=... on the command line.
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING
        "Build type (Release, Debug, RelWithDebInfo, MinSizeRel)" FORCE)
    message(STATUS "orpheus_dsp: defaulting CMAKE_BUILD_TYPE to Release")
endif()

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

# ── Frames sources (PolyLFO) ─────────────────────
# keyframer.cc is excluded — it depends on STM32 flash storage.
# We provide a stub for ConvertToDacCode (used by poly_lfo but not needed).
set(FRAMES_SRC
    "${EURORACK_DIR}/frames/poly_lfo.cc"
    "${EURORACK_DIR}/frames/resources.cc"
    "src/orpheus_frames_stub.cpp"
)

# ── Streams sources (Lorenz attractor) ────────────
set(STREAMS_SRC
    "${EURORACK_DIR}/streams/lorenz_generator.cc"
    "${EURORACK_DIR}/streams/resources.cc"
)

# ── Tides2 sources (PolySlopeGenerator + RampExtractor) ──
set(TIDES2_SRC
    "${EURORACK_DIR}/tides2/poly_slope_generator.cc"
    "${EURORACK_DIR}/tides2/resources.cc"
    "${EURORACK_DIR}/tides2/ramp/ramp_extractor.cc"
)

# ── Braids sources (MacroOscillator) ──────────────
# Excludes ui.cc, bootloader/, and settings.cc — those depend on STM32
# storage/ui hardware. We use the MacroOscillator API directly with
# explicit set_shape/set_pitch/set_parameters; no flash-stored settings.
# settings.h is still included via macro_oscillator.h for the shape enum.
set(BRAIDS_SRC
    "${EURORACK_DIR}/braids/macro_oscillator.cc"
    "${EURORACK_DIR}/braids/analog_oscillator.cc"
    "${EURORACK_DIR}/braids/digital_oscillator.cc"
    "${EURORACK_DIR}/braids/quantizer.cc"
    "${EURORACK_DIR}/braids/resources.cc"
)

# ── Orpheus engine sources ────────────────────────
set(ORPHEUS_SRC
    "src/orpheus_engine.cpp"
    "src/orpheus_engine_routing.cpp"
    "src/orpheus_engine_voice.cpp"
    "src/orpheus_graph.cpp"
    "src/orpheus_monitor.cpp"
    "src/orpheus_unit_basic.cpp"
    "src/orpheus_unit_bender.cpp"
    "src/orpheus_unit_clouds.cpp"
    "src/orpheus_unit_delay.cpp"
    "src/orpheus_unit_grids.cpp"
    "src/orpheus_unit_lfo.cpp"
    "src/orpheus_unit_looper.cpp"
    "src/orpheus_unit_marbles.cpp"
    "src/orpheus_unit_plaits.cpp"
    "src/orpheus_unit_reverb.cpp"
    "src/orpheus_unit_rings.cpp"
    "src/orpheus_unit_warps.cpp"
    "src/orpheus_unit_lorenz.cpp"
    "src/orpheus_unit_poly_lfo.cpp"
    "src/orpheus_unit_bass.cpp"
    "src/orpheus_unit_overdrive.cpp"
    "src/orpheus_unit_compressor.cpp"
    "src/orpheus_unit_horn.cpp"
    "src/orpheus_turntable.cpp"
    "src/orpheus_unit_tides.cpp"
    "src/orpheus_unit_braids.cpp"
    "src/orpheus_unit_pulsar.cpp"
    "src/orpheus_unit_pulsar_delay.cpp"
    "src/orpheus_unit_pulsar_reverb.cpp"
    "src/orpheus_patch.cpp"
    "src/orpheus_resonator.cpp"
    "src/orpheus_spectrum.cc"
    "src/orpheus_unit_chaos.cpp"
    "src/orpheus_master_fader.cpp"
    "src/orpheus_master_tape_stop.cpp"
    "src/orpheus_master_scratch.cpp"
    "src/orpheus_master_filter.cpp"
    "src/orpheus_master_leslie.cpp"
    "src/chaos/lorenz.cpp"
    "src/chaos/rossler.cpp"
    "src/chaos/duffing.cpp"
    "src/chaos/henon.cpp"
    "src/chaos/chua.cpp"
)

# ── Library target ────────────────────────────────
add_library(orpheus_dsp STATIC
    ${ORPHEUS_SRC}
    ${STMLIB_SRC}
    ${PLAITS_SRC}
    ${CLOUDS_SRC}
    ${WARPS_SRC}
    ${MARBLES_SRC}
    ${STREAMS_SRC}
    ${FRAMES_SRC}
    ${TIDES2_SRC}
    ${BRAIDS_SRC}
)

target_include_directories(orpheus_dsp PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_include_directories(orpheus_dsp PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
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
# When BUILD_TESTS=ON, expose test-only hooks (debug peek atomics, etc.) from
# the library itself. Production app builds leave this off so the audio path
# pays no cost for test-only state.
if(BUILD_TESTS)
    target_compile_definitions(orpheus_dsp PRIVATE ORPHEUS_TESTING)
endif()
if(BUILD_TESTS)
    add_executable(orpheus_dsp_test
        test/test_main.cpp
        test/test_units.cpp
        test/test_voices.cpp
        test/test_lpg.cpp
        test/test_engine_render.cpp
        test/test_effects.cpp
        test/test_graph.cpp
        test/test_output_chain.cpp
        test/test_drums_graph.cpp
        test/test_lfo.cpp
        test/test_spectrum.cpp
        test/test_control_routing.cpp
        test/test_snapshots.cpp
        test/test_headroom.cpp
        test/test_warps.cpp
        test/test_bridge_audit.cpp
        test/test_presets.cpp
        test/test_preset_voices.cpp
        test/test_viz.cpp
        test/test_chain_compare.cpp
        test/test_fm_compare.cpp
        test/test_benchmark.cpp
        test/test_bass_voice.cpp
        test/test_horn.cpp
        test/test_turntable.cpp
        test/test_tides.cpp
        test/test_pulsar.cpp
        test/test_pulsar_bus.cpp
        test/test_djapp_graph.cpp
        test/test_bar_strategy.cpp
        test/test_pulsar_routing.cpp
        test/test_pulsar_chords.cpp
        test/test_pulsar_comping.cpp
        test/test_pulsar_signal_quality.cpp
        test/test_pulsar_tension.cpp
        test/test_pulsar_solos.cpp
        test/test_pulsar_sections.cpp
        test/test_pulsar_lick_marshalling.cpp
        test/test_pulsar_outro_request.cpp
        test/test_oboe_buffer.cpp
        test/test_pulsar_texture.cpp
        test/test_pulsar_complexity_cap.cpp
        test/test_pulsar_pattern_gen.cpp
        test/test_pulsar_band_solo.cpp
        test/test_pulsar_lick_techniques.cpp
        test/test_pulsar_glide.cpp
        test/test_pulsar_pinning.cpp
        test/test_markov_solo.cpp
        test/test_pulsar_analysis.cpp
        test/test_braids.cpp
        test/test_chaos.cpp
        test/chaos_tests/test_lorenz.cpp
        test/chaos_tests/test_rossler.cpp
        test/chaos_tests/test_duffing.cpp
        test/chaos_tests/test_henon.cpp
        test/chaos_tests/test_chua.cpp
        test/test_pulsar_chaos.cpp
        test/test_master_fader.cpp
        test/test_master_tape_stop.cpp
        test/test_master_scratch.cpp
        test/test_master_leslie.cpp
        test/test_master_fader_pulsar_routing.cpp
        test/test_master_tape_stop_pulsar_routing.cpp
    )
    target_link_libraries(orpheus_dsp_test orpheus_dsp)
    target_include_directories(orpheus_dsp_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
        "${CMAKE_CURRENT_SOURCE_DIR}/test"
        "${EURORACK_DIR}"
    )
    target_compile_definitions(orpheus_dsp_test PRIVATE
        TEST
        ORPHEUS_TESTING
        TEST_DATA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/test/data"
    )

    # Re-export the production graph from Kotlin before running tests.
    # This ensures C++ tests always use the current DefaultWiringGraph.kt.
    find_program(GRADLEW "${CMAKE_CURRENT_SOURCE_DIR}/../gradlew")
    if(GRADLEW)
        add_custom_target(export_graph
            COMMAND ${GRADLEW} -p "${CMAKE_CURRENT_SOURCE_DIR}/.."
                :core:dsp-engine:jvmTest --tests "*ExportOdwgTest*" -q
            COMMENT "Exporting production graph (default_graph.odwg)"
        )
        # add_dependencies(orpheus_dsp_test export_graph)  # Requires Android SDK; graph file in test/data/ suffices
    endif()

    # Standalone FM compare runner (avoids crash in run_warps_tests)
    add_executable(orpheus_fm_test
        test/test_fm_main.cpp
        test/test_fm_compare.cpp
    )
    target_link_libraries(orpheus_fm_test orpheus_dsp)
    target_include_directories(orpheus_fm_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
        "${EURORACK_DIR}"
    )
    target_compile_definitions(orpheus_fm_test PRIVATE
        TEST
        TEST_DATA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/test/data"
    )
endif()
