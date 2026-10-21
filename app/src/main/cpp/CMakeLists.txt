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
    nnapi_probe.cpp)

target_include_directories(hyperxr PRIVATE ${LUAJIT_DIR}/include)
target_compile_options(hyperxr PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -fno-exceptions)
target_link_libraries(hyperxr luajit OpenXR::openxr_loader EGL GLESv3 android log dl m)
# LuaJIT needs its symbols exported for C modules loaded at runtime only in unsafe mode; keep them local otherwise.
target_link_options(hyperxr PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
