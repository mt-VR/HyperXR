#!/usr/bin/env bash
# Reproduces native/luajit/prebuilt/*/libluajit.a from LuaJIT sources.
# Usage: tools/build_luajit_android.sh <path-to-luajit-v2.1-checkout> [ndk-dir] [abi...]
# armeabi-v7a needs a 32-bit host compiler (gcc -m32: the gcc-multilib package).
# The commit used for the checked-in binaries is in native/luajit/LUAJIT_COMMIT.
set -euo pipefail
SRC=${1:?luajit checkout}
NDK_DIR=${2:-${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}}
BIN=$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin
OUT=$(cd "$(dirname "$0")/.." && pwd)/native/luajit
API=24
shift $(( $# < 2 ? $# : 2 ))
WANT="${*:-arm64-v8a armeabi-v7a x86_64}"
for spec in "arm64-v8a aarch64-linux-android gcc" "armeabi-v7a armv7a-linux-androideabi gcc -m32" "x86_64 x86_64-linux-android gcc"; do
  set -- $spec
  case " $WANT " in *" $1 "*) ;; *) continue ;; esac
  HOST="${*:3}"
  WORK=$(mktemp -d); cp -r "$SRC"/. "$WORK"; pushd "$WORK" >/dev/null
  CC="$BIN/$2$API-clang"
  make clean >/dev/null
  make -j"$(nproc)" HOST_CC="$HOST" CROSS="$BIN/llvm-" STATIC_CC="$CC -fPIC" DYNAMIC_CC="$CC -fPIC" \
       TARGET_LD="$CC" TARGET_AR="$BIN/llvm-ar rcus" TARGET_STRIP="$BIN/llvm-strip" \
       TARGET_SYS=Linux BUILDMODE=static
  mkdir -p "$OUT/prebuilt/$1"; cp src/libluajit.a "$OUT/prebuilt/$1/"
  popd >/dev/null; rm -rf "$WORK"
done
cp "$SRC"/src/{lua.h,lualib.h,lauxlib.h,luaconf.h,lua.hpp,luajit.h} "$OUT/include/"
(cd "$SRC" && git rev-parse HEAD) > "$OUT/LUAJIT_COMMIT"
