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
set(LIBORPHEUS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../liborpheus_dsp")
if(NOT DEFINED EURORACK_DIR)
    set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../../../../eurorack"
        CACHE PATH "Path to eurorack source repo")
endif()
add_subdirectory(
    "${LIBORPHEUS_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/liborpheus_dsp"
)

# ── Android bridge ────────────────────────────────
# Reuse the JNI bridge sources from Orpheus's androidApp
set(ORPHEUS_ANDROID_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../orpheus/androidApp/src/main/cpp")
add_library(orpheus_oboe SHARED
    ${ORPHEUS_ANDROID_CPP}/jni_bridge.cpp
    ${ORPHEUS_ANDROID_CPP}/OboeEngine.cpp
)

target_include_directories(orpheus_oboe PRIVATE ${oboe_SOURCE_DIR}/include)
target_link_libraries(orpheus_oboe android log oboe orpheus_dsp)
target_compile_options(orpheus_oboe PRIVATE -Wall -Werror "$<$<CONFIG:RELEASE>:-O3 -ffast-math>")
target_link_options(orpheus_oboe PRIVATE "-Wl,-z,max-page-size=16384")
