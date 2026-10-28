cmake_minimum_required(VERSION 3.22.1)
project(hypermagic_minecraft C CXX)

# Native glue from Pojlib (QuestCraft, LGPLv3), the same modules its Android.mk builds.
set(CMAKE_C_STANDARD 11)
include_directories(${CMAKE_CURRENT_SOURCE_DIR})
add_compile_definitions(XR_USE_PLATFORM_ANDROID XR_USE_GRAPHICS_API_OPENGL_ES)
add_compile_options(-Wno-int-conversion -Wno-incompatible-pointer-types -Wno-implicit-function-declaration -Wno-format)

# GLFW stub, EGL bridge and input queue shared by the Android side and the game's JVM
add_library(pojavexec SHARED egl_bridge.c utils.c environ/environ.c input_bridge_v3.c)
target_link_libraries(pojavexec dl log android GLESv3 EGL)

# EGL handles and the Android activity for Vivecraft's OpenXR session
add_library(vloader SHARED vloader.cpp)
target_link_libraries(vloader pojavexec log android GLESv3 EGL)

# stdout/stderr of the game into the launcher log
add_library(istdio SHARED stdio_is.c)
target_link_libraries(istdio log)

add_library(pojavexec_awt SHARED awt_bridge.c)
target_link_libraries(pojavexec_awt log)

# starts the JVM (JLI_Launch) inside this process
add_library(jrelauncher SHARED jre_launcher.c)
target_link_libraries(jrelauncher pojavexec log android dl)

# AWT without X11 (Caciocavallo); copied into the Java runtime's lib folder when it is installed
add_library(awt_xawt SHARED awt_xawt/xawt_fake.c)
