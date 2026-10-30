cmake_minimum_required(VERSION 3.22.1)
project(hyperxr_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# LuaJIT 2.1 static libraries built by tools/build_luajit_android.sh
set(LUAJIT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/luajit)
add_library(luajit STATIC IMPORTED)
set_target_properties(luajit PROPERTIES IMPORTED_LOCATION ${LUAJIT_DIR}/prebuilt/${ANDROID_ABI}/libluajit.a)

# Khronos OpenXR loader (prefab from org.khronos.openxr:openxr_loader_for_android)
find_package(OpenXR REQUIRED CONFIG)

add_library(hyperxr SHARED
    lua_bridge.cpp
    openxr_bridge.cpp
    runtime_selftest.cpp
    nnapi_probe.cpp)

target_include_directories(hyperxr PRIVATE ${LUAJIT_DIR}/include)
target_include_directories(hyperxr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/openxr-runtime/include)
target_compile_options(hyperxr PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -fno-exceptions)
target_link_libraries(hyperxr luajit OpenXR::openxr_loader EGL GLESv3 android log dl m)
# LuaJIT needs its symbols exported for C modules loaded at runtime only in unsafe mode; keep them local otherwise.
target_link_options(hyperxr PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)

# HyperXR OpenXR runtime (loaded by the Khronos loader into OpenXR apps' processes). Built without
# libc++ so it never depends on a libc++_shared.so the host app may not ship.
set(HX_RUNTIME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/openxr-runtime)
add_library(hyperxr_openxr SHARED
    ${HX_RUNTIME_DIR}/src/runtime.cpp
    ${HX_RUNTIME_DIR}/src/platform_android.cpp)
target_include_directories(hyperxr_openxr PRIVATE ${HX_RUNTIME_DIR}/include)
target_compile_options(hyperxr_openxr PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers
    -fno-exceptions -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(hyperxr_openxr PRIVATE -nostdlib++ -Wl,--no-undefined -Wl,-z,nodelete -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(hyperxr_openxr EGL GLESv3 android log dl m)

# Camera frame conversion for hand tracking (hot path: optimised even in debug builds).
# libyuv (BSD-3-Clause) built from source by tools/build_libyuv.sh: NEON YUV conversion and scaling.
set(LIBYUV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/libyuv)
add_library(libyuv STATIC IMPORTED)
set_target_properties(libyuv PROPERTIES IMPORTED_LOCATION ${LIBYUV_DIR}/${ANDROID_ABI}/libyuv.a)
add_library(hyperxr_image SHARED yuv_convert.cpp)
target_include_directories(hyperxr_image PRIVATE ${LIBYUV_DIR}/include)
target_compile_options(hyperxr_image PRIVATE -O3 -Wall -Wextra -fno-exceptions -fno-rtti)
target_link_options(hyperxr_image PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(hyperxr_image libyuv jnigraphics)

# libretro frontend for the embedded emulator cores (jniLibs/libcore_*.so, loaded with dlopen).
add_library(hyperxr_retro SHARED retro_frontend.cpp)
target_include_directories(hyperxr_retro PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/libretro/include)
target_compile_options(hyperxr_retro PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -fno-exceptions -fno-rtti)
target_link_options(hyperxr_retro PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(hyperxr_retro android log dl)

# Spatial audio: Resonance Audio (Apache-2.0) built from source by tools/build_resonance_audio.sh,
# mixed and played through AAudio.
set(RESONANCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/resonance-audio)
add_library(resonance_audio STATIC IMPORTED)
set_target_properties(resonance_audio PROPERTIES IMPORTED_LOCATION ${RESONANCE_DIR}/${ANDROID_ABI}/libResonanceAudioStatic.a)
add_library(hyperxr_audio SHARED spatial_audio.cpp)
target_include_directories(hyperxr_audio PRIVATE ${RESONANCE_DIR}/include)
target_compile_definitions(hyperxr_audio PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_compile_options(hyperxr_audio PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)
target_link_options(hyperxr_audio PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(hyperxr_audio resonance_audio aaudio log m)
