cmake_minimum_required(VERSION 3.22)
project(orpheus_dsp_ios CXX OBJCXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── iOS deployment target ────────────────────────
set(CMAKE_OSX_DEPLOYMENT_TARGET "15.0")

# ── Eurorack source location ────────────────────
if(NOT DEFINED EURORACK_DIR)
    if(DEFINED ENV{EURORACK_DIR})
        set(EURORACK_DIR $ENV{EURORACK_DIR})
    else()
        message(FATAL_ERROR "EURORACK_DIR must be set to the path of the eurorack repository")
    endif()
endif()
message(STATUS "Eurorack source: ${EURORACK_DIR}")

# ── Build liborpheus_dsp as a static library ─────
# Includes the root CMakeLists.txt which defines the orpheus_dsp STATIC target
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../.."
    "${CMAKE_CURRENT_BINARY_DIR}/orpheus_dsp"
)

# ── iOS-only audio host (ObjC++) ─────────────────
# Added here, not in the root CMakeLists, so desktop/Android/WASM builds
# never see the .mm file.
set(ORPHEUS_IOS_AUDIO_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/../../src/ios/orpheus_ios_audio.mm")
target_sources(orpheus_dsp PRIVATE "${ORPHEUS_IOS_AUDIO_SRC}")
set_source_files_properties("${ORPHEUS_IOS_AUDIO_SRC}"
    PROPERTIES COMPILE_FLAGS "-fobjc-arc")
