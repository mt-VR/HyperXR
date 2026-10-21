#!/usr/bin/env bash
# Builds lua_bridge.cpp for the host so app unit tests can exercise the real JNI bridge.
# Usage: tools/build_host_bridge.sh <luajit-source-dir> <out-dir>
set -euo pipefail
LJ=${1:?LuaJIT checkout (built with make)}; OUT=${2:?output dir}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JH=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
mkdir -p "$OUT"
g++ -std=c++17 -O1 -fPIC -shared -o "$OUT/libhyperxr.so" \
  -I"$ROOT/app/src/test/hostjni" -I"$ROOT/native/luajit/include" -I"$JH/include" -I"$JH/include/linux" \
  "$ROOT/app/src/main/cpp/lua_bridge.cpp" "$LJ/src/libluajit.a" -ldl -lm
echo "$OUT/libhyperxr.so"
