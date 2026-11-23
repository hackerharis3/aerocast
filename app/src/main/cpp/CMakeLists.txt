cmake_minimum_required(VERSION 3.22.1)
project("airmusic_audio_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    airmusic_audio_engine
    SHARED
    audio_engine_jni.cpp
)

# Android 15 (API 35) 16 KB ELF page size compatibility + Full RELRO + NX stack + Fast Math DSP
target_compile_options(
    airmusic_audio_engine
    PRIVATE
    "-O3"
    "-ffast-math"
    "-Wall"
    "-Wextra"
)

target_link_options(
    airmusic_audio_engine
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,relro"
    "-Wl,-z,now"
    "-Wl,-z,noexecstack"
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(
    airmusic_audio_engine
    ${log-lib}
    ${dl-lib}
)
