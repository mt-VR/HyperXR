// Host shim of <android/log.h> so lua_bridge.cpp can be compiled and tested on a desktop JVM.
#pragma once
#include <cstdarg>
#include <cstdio>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[%d %s] ", prio, tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr);
    va_end(ap); return 0;
}
