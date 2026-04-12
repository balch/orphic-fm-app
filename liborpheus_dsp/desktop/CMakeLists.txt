cmake_minimum_required(VERSION 3.22)
project(orpheus_desktop CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -- miniaudio (fetched at configure time) ----------------------------------
include(FetchContent)
FetchContent_Declare(miniaudio
    GIT_REPOSITORY https://github.com/mackron/miniaudio.git
    GIT_TAG        0.11.25
)
FetchContent_MakeAvailable(miniaudio)

# -- JNI headers -----------------------------------------------------------
find_package(JNI REQUIRED)

# -- Eurorack source location ----------------------------------------------
if(NOT DEFINED EURORACK_DIR)
    if(DEFINED ENV{EURORACK_DIR})
        set(EURORACK_DIR $ENV{EURORACK_DIR})
    else()
        set(EURORACK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../eurorack")
    endif()
endif()
message(STATUS "Eurorack source: ${EURORACK_DIR}")

# -- Build liborpheus_dsp as a static library ------------------------------
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/.."
    "${CMAKE_CURRENT_BINARY_DIR}/liborpheus_dsp"
)

# -- Shared library: liborpheus_desktop.dylib ------------------------------
add_library(orpheus_desktop SHARED
    DesktopEngine.cpp
    jni_bridge_desktop.cpp
    miniaudio_impl.cpp
)

# macOS Now Playing integration (MPNowPlayingInfoCenter / MPRemoteCommandCenter)
if(APPLE)
    target_sources(orpheus_desktop PRIVATE macos_now_playing.mm)
    set_source_files_properties(macos_now_playing.mm PROPERTIES
        COMPILE_FLAGS "-x objective-c++"
    )
endif()

target_include_directories(orpheus_desktop PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${CMAKE_CURRENT_SOURCE_DIR}/../include"
    "${miniaudio_SOURCE_DIR}"
)

target_link_libraries(orpheus_desktop PRIVATE orpheus_dsp)

# Platform audio frameworks for miniaudio
if(APPLE)
    target_link_libraries(orpheus_desktop PRIVATE
        "-framework CoreAudio"
        "-framework AudioToolbox"
        "-framework CoreFoundation"
        "-framework Foundation"
        "-framework MediaPlayer"
    )
elseif(UNIX)
    target_link_libraries(orpheus_desktop PRIVATE pthread m dl)
endif()

# -- macOS RPATH -----------------------------------------------------------
if(APPLE)
    set_target_properties(orpheus_desktop PROPERTIES
        INSTALL_RPATH "@loader_path"
        BUILD_WITH_INSTALL_RPATH TRUE
    )
endif()
