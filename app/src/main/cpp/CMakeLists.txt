cmake_minimum_required(VERSION 3.22.1)

project(ab_native LANGUAGES CXX)

set(MNN_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(LLM_SUPPORT_HTTP_RESOURCE OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_VULKAN ON CACHE BOOL "" FORCE)
set(MNN_ARM82 ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT ON CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)

set(MNN_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/MNN")

add_subdirectory(
    ${MNN_SOURCE_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}/mnn
)

add_library(
    ab_native
    SHARED
    native_bridge.cpp
)

target_compile_features(
    ab_native
    PRIVATE
    cxx_std_17
)

target_include_directories(
    ab_native
    PRIVATE
    ${MNN_SOURCE_DIR}/include
    ${MNN_SOURCE_DIR}/transformers/llm/engine/include
)

target_link_libraries(
    ab_native
    PRIVATE
    MNN
    llm
    android
    log
)
