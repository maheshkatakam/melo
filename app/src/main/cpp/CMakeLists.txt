cmake_minimum_required(VERSION 3.22.1)
project(melo_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# whisper.cpp (offline speech recognition + translation to English), linked statically
# into a single JNI library. Built for ARMv8.2 (fp16 + dot product), which every
# mainstream 64-bit phone from roughly 2018 onwards supports. The app checks the CPU
# before loading this library, so older phones simply hide the AI lyrics feature.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
)
FetchContent_GetProperties(whisper)
if (NOT whisper_POPULATED)
    FetchContent_Populate(whisper)
    add_subdirectory(${whisper_SOURCE_DIR} ${whisper_BINARY_DIR} EXCLUDE_FROM_ALL)
endif ()

add_library(melo_whisper SHARED melo_whisper.cpp)
target_compile_options(melo_whisper PRIVATE -O3 -march=armv8.2-a+fp16+dotprod)
target_link_libraries(melo_whisper PRIVATE whisper android log)
