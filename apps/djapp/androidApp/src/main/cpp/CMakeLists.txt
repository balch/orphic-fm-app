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
# This app ships commercially; MI Grids is GPL-3.0 and must not be linked.
set(ORPHEUS_WITH_GRIDS OFF CACHE BOOL "" FORCE)

add_subdirectory(
    "${LIBORPHEUS_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/liborpheus_dsp"
)

# Placed AFTER add_subdirectory so it reads the value liborpheus_dsp actually saw -- a stale
# CMakeCache would otherwise override the set() above without a word.
if(ORPHEUS_WITH_GRIDS)
    message(FATAL_ERROR
        "GPL-3.0 Grids code would be linked into a commercial app. A stale CMakeCache is the "
        "usual cause -- delete the build directory and reconfigure.")
endif()


# ── Android bridge ────────────────────────────────
# Reuse the JNI bridge sources from Orpheus's androidApp
set(ORPHEUS_ANDROID_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../orpheus/androidApp/src/main/cpp")
add_library(orpheus_oboe SHARED
    ${ORPHEUS_ANDROID_CPP}/jni_bridge.cpp
    ${ORPHEUS_ANDROID_CPP}/OboeEngine.cpp
)

target_include_directories(orpheus_oboe PRIVATE ${oboe_SOURCE_DIR}/include)
target_link_libraries(orpheus_oboe android log oboe orpheus_dsp)
# RelWithDebInfo too — AGP uses it for release natives, so a Release-only gate never
# fired. See the same note in liborpheus_dsp/CMakeLists.txt.
target_compile_options(orpheus_oboe PRIVATE -Wall -Werror
    "$<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>>:-O3>"
    "$<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>>:-ffast-math>")
target_link_options(orpheus_oboe PRIVATE "-Wl,-z,max-page-size=16384")
