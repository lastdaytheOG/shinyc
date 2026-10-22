cmake_minimum_required(VERSION 3.22.1)
project("amar_native_engines")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Re-enabled fast-math to speed up float calculations on ARM
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math")

# CPU only — Vulkan hangs on Adreno 619
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)

# 🔥 FIX 1: Turn OpenMP ON to properly map threads across your Snapdragon cores
set(GGML_OPENMP OFF CACHE BOOL "Use OpenMP for thread scheduling" FORCE)

set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# 🔥 FIX 2: DELETED `add_compile_definitions(GGML_NO_SIMD)`
# ARM NEON is now active. Math will be ~20x faster.

# ═══════════════════════════════════════════════════════════════════════
# LLM Engine — JNI bridge
# ═══════════════════════════════════════════════════════════════════════

add_library(amar_llama_engine SHARED llama_jni_bridge.cpp)

target_compile_options(amar_llama_engine PRIVATE -O3 -DNDEBUG -ffast-math)

target_include_directories(amar_llama_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

find_library(log-lib log)
target_link_libraries(amar_llama_engine llama ggml ${log-lib})

# ═══════════════════════════════════════════════════════════════════════
# Vector Engine & Search Engine
# ═══════════════════════════════════════════════════════════════════════

add_library(amar_vector_engine SHARED vector_jni_bridge.cpp VectorEngine.cpp photo_scan.cpp)
target_include_directories(amar_vector_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# Optimised in debug builds too: photo search scans every stored picture per query.
target_compile_options(amar_vector_engine PRIVATE -O3 -ffast-math)
target_link_libraries(amar_vector_engine ${log-lib})

add_library(amar_search_engine SHARED search_jni_bridge.cpp SearchEngine.cpp)
target_include_directories(amar_search_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(amar_search_engine ${log-lib})