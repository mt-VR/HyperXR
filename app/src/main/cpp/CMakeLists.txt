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
target_link_options(hyperxr_openxr PRIVATE -nostdlib++ -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(hyperxr_openxr EGL GLESv3 android log dl m)
