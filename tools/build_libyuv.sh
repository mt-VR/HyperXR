#!/usr/bin/env bash
# Builds libyuv (Google/Chromium, BSD-3-Clause) from source for the camera path of hand tracking:
#   tools/build_libyuv.sh  ->  native/libyuv/<abi>/libyuv.a + include/
# NEON code paths on both ABIs (and SVE on arm64; the SME kernels do not build with the NDK
# compiler and are left out); no JPEG support (not needed).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$(mktemp -d)}
OUT="$ROOT/native/libyuv"
COMMIT=0b8dd2318002db2cc089930d6e2a3ad131eabdb8
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}
if [ ! -d "$WORK/libyuv/.git" ]; then
  git init -q "$WORK/libyuv"; git -C "$WORK/libyuv" fetch -q --depth 1 https://chromium.googlesource.com/libyuv/libyuv "$COMMIT"
  git -C "$WORK/libyuv" checkout -q FETCH_HEAD
fi
for ABI in arm64-v8a armeabi-v7a; do
  B="$WORK/build-$ABI"
  cmake -S "$WORK/libyuv" -B "$B" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-26 -DANDROID_ARM_NEON=ON -DCAN_COMPILE_SME=OFF \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-O3 -fPIC" -DCMAKE_CXX_FLAGS="-O3 -fPIC" > "$WORK/cmake-$ABI.log"
  cmake --build "$B" --target yuv -j"$(nproc)" > "$WORK/build-$ABI.log"
  mkdir -p "$OUT/$ABI"
  cp "$(find "$B" -name 'libyuv.a' | head -1)" "$OUT/$ABI/libyuv.a"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-debug "$OUT/$ABI/libyuv.a"
done
rm -rf "$OUT/include"; cp -r "$WORK/libyuv/include" "$OUT/include"
cp "$WORK/libyuv/LICENSE" "$OUT/LICENSE"
echo "libyuv https://chromium.googlesource.com/libyuv/libyuv $COMMIT (BSD-3-Clause), built by tools/build_libyuv.sh, NDK $(basename "$NDK")" > "$OUT/SOURCES"
echo "built $OUT"
