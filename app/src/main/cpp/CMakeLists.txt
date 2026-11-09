cmake_minimum_required(VERSION 3.22.1)
project("amar_native_engines")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Re-enabled fast-math to speed up float calculations on ARM
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math")

find_library(log-lib log)

# ═══════════════════════════════════════════════════════════════════════
# Vector Engine & Search Engine
# ═══════════════════════════════════════════════════════════════════════

add_library(amar_vector_engine SHARED vector_jni_bridge.cpp VectorEngine.cpp photo_scan.cpp photo_pixels.cpp)
target_include_directories(amar_vector_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# Optimised in debug builds too: photo search scans every stored picture per query.
target_compile_options(amar_vector_engine PRIVATE -O3 -ffast-math)
# Must give the same bits as the Kotlin it stands in for: no fast math, no fused multiply-add.
set_source_files_properties(photo_pixels.cpp PROPERTIES COMPILE_OPTIONS "-fno-fast-math;-ffp-contract=off")
target_link_libraries(amar_vector_engine ${log-lib} jnigraphics)

add_library(amar_search_engine SHARED search_jni_bridge.cpp SearchEngine.cpp)
target_include_directories(amar_search_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(amar_search_engine ${log-lib})