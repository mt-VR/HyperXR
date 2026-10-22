cmake_minimum_required(VERSION 3.22)
project(compatibility_layer_gearvr LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PROJECT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(COMPATIBILITY_LAYER_INCLUDE ${PROJECT_ROOT}/compatibility-layer-runtime/monado/src/external/compatibility_layer_includes)
# 32-bit games (armeabi-v7a) get their own adapter and the official Khronos 32-bit loader.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
	set(ABI_SUFFIX 32)
else()
	set(ABI_SUFFIX "")
endif()
set(COMPATIBILITY_LAYER_LOADER ${PROJECT_ROOT}/compatibility-layer-runtime/loaders/libcompatibility_layer_loader${ABI_SUFFIX}.so)
set(WARNINGS -Wall -Wextra -Werror -Wno-missing-field-initializers)

# Compatibility-Layer backend of the Gear VR adapter, linked into libvrapi.so.
add_library(compatibility_layer_xr_backend STATIC src/xr_backend.cpp)
target_include_directories(compatibility_layer_xr_backend PUBLIC src ${COMPATIBILITY_LAYER_INCLUDE})
target_compile_options(compatibility_layer_xr_backend PRIVATE ${WARNINGS})

add_library(compatibility_layer_loader SHARED IMPORTED)
set_target_properties(compatibility_layer_loader PROPERTIES IMPORTED_LOCATION ${COMPATIBILITY_LAYER_LOADER})

target_link_libraries(compatibility_layer_xr_backend PUBLIC compatibility_layer_loader EGL GLESv3 log android)

# VrApi headers from the Oculus Mobile SDK (local copy, not in git).
set(VRAPI_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/VrApi CACHE PATH "Oculus Mobile SDK VrApi folder")
if(NOT EXISTS ${VRAPI_ROOT}/Include/VrApi.h)
	message(FATAL_ERROR "VrApi headers not found in ${VRAPI_ROOT}; pass -DVRAPI_ROOT=<ovr_sdk_mobile>/VrApi")
endif()

# Drop-in replacement for the game's libvrapi.so.
add_library(vrapi SHARED
	src/vrapi_system.cpp
	src/vrapi_frame.cpp
	src/vrapi_swapchain.cpp
	src/vrapi_input.cpp)
target_include_directories(vrapi PRIVATE ${VRAPI_ROOT}/Include)
target_compile_options(vrapi PRIVATE ${WARNINGS} -fvisibility=hidden -fvisibility-inlines-hidden)
# Games load the library by file name and need nothing from the C++ runtime.
target_link_options(vrapi PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL -static-libstdc++)
target_link_libraries(vrapi PRIVATE compatibility_layer_xr_backend)

# The Compatibility-Layer app bundles the adapter as an asset and puts it into Gear VR games it installs.
# Both ABIs land in gearvr-shim/build/assets whatever build folder is used: libvrapi.so, libvrapi32.so.
set(ASSET_DIR ${CMAKE_CURRENT_SOURCE_DIR}/build/assets)
add_custom_command(TARGET vrapi POST_BUILD
	COMMAND ${CMAKE_COMMAND} -E make_directory ${ASSET_DIR}
	COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:vrapi> ${ASSET_DIR}/libvrapi${ABI_SUFFIX}.so)

# The same VrApi as the system driver of unpatched games (package com.oculus.systemdriver, built by
# vrapi-driver/build_driver_apk.py): the game's own libvrapi.so loader asks it for every function.
add_library(compatibility_layer_vrapi SHARED
	src/vrapi_system.cpp
	src/vrapi_frame.cpp
	src/vrapi_swapchain.cpp
	src/vrapi_input.cpp
	src/vrapi_driver.cpp)
target_include_directories(compatibility_layer_vrapi PRIVATE ${VRAPI_ROOT}/Include)
target_compile_options(compatibility_layer_vrapi PRIVATE ${WARNINGS} -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(compatibility_layer_vrapi PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL -static-libstdc++ -Wl,-Bsymbolic)
target_link_libraries(compatibility_layer_vrapi PRIVATE compatibility_layer_xr_backend)
add_custom_command(TARGET compatibility_layer_vrapi POST_BUILD
	COMMAND ${CMAKE_COMMAND} -E make_directory ${ASSET_DIR}
	COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:compatibility_layer_vrapi> ${ASSET_DIR}/libcompatibility_layer_vrapi${ABI_SUFFIX}.so)
