cmake_minimum_required(VERSION 3.22.1)
project(orpheus_oboe)

# ── Oboe ──────────────────────────────────────────
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

# ── liborpheus_dsp ────────────────────────────────
# EURORACK_DIR must be passed from Gradle or defaults to a peer of the project root.
set(LIBORPHEUS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../liborpheus_dsp")
if(NOT DEFINED EURORACK_DIR)
    set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../../../eurorack"
        CACHE PATH "Path to eurorack source repo")
endif()
add_subdirectory(
    "${LIBORPHEUS_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/liborpheus_dsp"
)

# ── Android bridge ────────────────────────────────
add_library(orpheus_oboe SHARED
    jni_bridge.cpp
    OboeEngine.cpp
)

target_include_directories(orpheus_oboe PRIVATE ${oboe_SOURCE_DIR}/include)
target_link_libraries(orpheus_oboe android log oboe orpheus_dsp)
target_compile_options(orpheus_oboe PRIVATE -Wall -Werror "$<$<CONFIG:RELEASE>:-O3 -ffast-math>")
target_link_options(orpheus_oboe PRIVATE "-Wl,-z,max-page-size=16384")
