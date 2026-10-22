#!/bin/sh
# Builds the runtime for the host (headless platform layer) and runs its tests.
# Usage: native/openxr-runtime/tests/run_host_tests.sh [build-dir]
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$HERE/build-host}
mkdir -p "$OUT"
CXX=${CXX:-g++}
FLAGS="-std=c++17 -O1 -g -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -fno-exceptions -fno-rtti -fsanitize=address,undefined -I$HERE/include"
$CXX $FLAGS -c "$HERE/src/runtime.cpp" -o "$OUT/runtime.o"
$CXX $FLAGS -c "$HERE/src/platform_host.cpp" -o "$OUT/platform_host.o"
$CXX $FLAGS "$HERE/tests/runtime_test.cpp" "$OUT/runtime.o" "$OUT/platform_host.o" -lpthread -o "$OUT/runtime_test"
"$OUT/runtime_test"
