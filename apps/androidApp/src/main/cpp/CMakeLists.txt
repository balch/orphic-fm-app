cmake_minimum_required(VERSION 3.22.1)
project(orpheus_oboe)

include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

add_library(orpheus_oboe SHARED
    jni_bridge.cpp
    OboeEngine.cpp
)

target_include_directories(orpheus_oboe PRIVATE ${oboe_SOURCE_DIR}/include)
target_link_libraries(orpheus_oboe android log oboe)
target_compile_options(orpheus_oboe PRIVATE -Wall -Werror "$<$<CONFIG:RELEASE>:-O3 -ffast-math>")
target_link_options(orpheus_oboe PRIVATE "-Wl,-z,max-page-size=16384")
