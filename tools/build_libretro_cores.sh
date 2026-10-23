#!/usr/bin/env bash
# Builds the embedded emulator cores (libretro) from source into app/src/main/jniLibs.
# Usage: tools/build_libretro_cores.sh [work-dir] [ndk-dir]
# Sources and the exact commits used for the checked-in binaries are in native/libretro/CORES.
# The cores keep their own licences (GPL-2.0 / MPL-2.0); see native/libretro/README.md.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$(mktemp -d)}
NDK_DIR=${2:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-/opt/android-sdk}/ndk/27.2.12479018}}
SDK_DIR=$(cd "$NDK_DIR/../.." && pwd)
CMAKE_BIN=$(ls -d "$SDK_DIR"/cmake/*/bin | tail -1)
OUT=$ROOT/app/src/main/jniLibs
ABIS="arm64-v8a x86_64"
# 16 KB pages (Android 15+ devices), as for the app's own libraries
PAGE=-Wl,-z,max-page-size=16384

checkout() {  # name url commit
  if [ ! -d "$WORK/$1" ]; then git clone -q "$2" "$WORK/$1"; fi
  git -C "$WORK/$1" fetch -q --depth 1 origin "$3" 2>/dev/null || true
  git -C "$WORK/$1" checkout -q "$3"
}

while read -r name url commit; do
  [ -z "$name" ] || [ "${name#\#}" != "$name" ] && continue
  checkout "$name" "$url" "$commit"
done < "$ROOT/native/libretro/CORES"

for abi in $ABIS; do mkdir -p "$OUT/$abi"; done

# QuickNES (NES), Gambatte (Game Boy / Color) and DOSBox Pure (PC): their own Android.mk
for spec in "quicknes QuickNES_Core/jni" "gambatte gambatte-libretro/libgambatte/libretro/jni" "dosbox_pure dosbox-pure/jni"; do
  set -- $spec
  (cd "$WORK/$2" && "$NDK_DIR/ndk-build" -j"$(nproc)" APP_ABI="$ABIS" APP_PLATFORM=android-26 APP_LDFLAGS="$PAGE" >/dev/null)
  for abi in $ABIS; do cp "$WORK/$2/../libs/$abi/libretro.so" "$OUT/$abi/libcore_$1.so"; done
done

# mGBA (Game Boy Advance): upstream CMake with only the libretro target
for abi in $ABIS; do
  B=$WORK/build-mgba-$abi
  "$CMAKE_BIN/cmake" -S "$WORK/mgba" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_SHARED_LINKER_FLAGS="$PAGE" \
    -DBUILD_LIBRETRO=ON -DBUILD_SDL=OFF -DBUILD_QT=OFF -DBUILD_SHARED=OFF -DBUILD_STATIC=OFF -DSKIP_LIBRARY=ON \
    -DUSE_DEBUGGERS=OFF -DUSE_GDB_STUB=OFF -DUSE_FFMPEG=OFF -DUSE_ZLIB=OFF -DUSE_MINIZIP=OFF -DUSE_PNG=OFF \
    -DUSE_LIBZIP=OFF -DUSE_SQLITE3=OFF -DUSE_ELF=OFF -DUSE_LUA=OFF -DUSE_JSON_C=OFF -DUSE_EPOXY=OFF \
    -DUSE_DISCORD_RPC=OFF -DENABLE_SCRIPTING=OFF -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF >/dev/null
  "$CMAKE_BIN/ninja" -C "$B" mgba_libretro >/dev/null
  "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$OUT/$abi/libcore_mgba.so" "$B/mgba_libretro.so"
done
echo "cores in $OUT"
