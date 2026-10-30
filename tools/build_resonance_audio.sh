#!/usr/bin/env bash
# Builds Resonance Audio (Google, Apache-2.0) from source for HyperMagic's spatial audio:
#   tools/build_resonance_audio.sh  ->  native/resonance-audio/<abi>/libResonanceAudioStatic.a + include/
# Pinned commits; Eigen (MPL-2.0 parts only, EIGEN_MPL2_ONLY) and PFFFT (BSD-like) as Resonance
# Audio's own build expects them. The SADIE HRTF set (Apache-2.0) is compiled into the library.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$(mktemp -d)}
OUT="$ROOT/native/resonance-audio"
RA_COMMIT=4556a46afd4ffae092aa281bfd072eb0279d3a29     # resonance-audio master, 2022-09-08
EIGEN_COMMIT=3147391d946bb4b6c68edd901f2add6ac1f31f8c  # Eigen 3.4.0
PFFFT_COMMIT=67a4a6a113179ab4ac398298f46fce8dd60498dc  # pffft master
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}

fetch() { # dir url commit
  if [ ! -d "$1/.git" ]; then
    git init -q "$1"; git -C "$1" fetch -q --depth 1 "$2" "$3"; git -C "$1" checkout -q FETCH_HEAD
  fi
}
fetch "$WORK/ra" https://github.com/resonance-audio/resonance-audio "$RA_COMMIT"
fetch "$WORK/ra/third_party/eigen" https://gitlab.com/libeigen/eigen.git "$EIGEN_COMMIT"
fetch "$WORK/ra/third_party/pffft" https://bitbucket.org/jpommier/pffft.git "$PFFFT_COMMIT"

for ABI in arm64-v8a armeabi-v7a; do
  B="$WORK/build-$ABI"
  cmake -S "$WORK/ra" -B "$B" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_RESONANCE_AUDIO_API=ON \
    -DCMAKE_CXX_FLAGS="-O3 -fPIC -Wno-everything" -DCMAKE_C_FLAGS="-O3 -fPIC" > "$WORK/cmake-$ABI.log"
  cmake --build "$B" --target ResonanceAudioStatic -j"$(nproc)" > "$WORK/build-$ABI.log"
  mkdir -p "$OUT/$ABI"
  cp "$(find "$B" -name libResonanceAudioStatic.a | head -1)" "$OUT/$ABI/"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-debug "$OUT/$ABI/libResonanceAudioStatic.a"
done
mkdir -p "$OUT/include/api"
cp "$WORK/ra/resonance_audio/api/resonance_audio_api.h" "$OUT/include/api/"
cp "$WORK/ra/LICENSE" "$OUT/LICENSE-ResonanceAudio.txt"
cp "$WORK/ra/third_party/SADIE_hrtf_database/LICENSE" "$OUT/LICENSE-SADIE.txt"
sed -n "1,60p" "$WORK/ra/third_party/pffft/pffft.c" | sed -n "/Copyright/,/SUCH DAMAGE/p" > "$OUT/LICENSE-PFFFT.txt"
cat > "$OUT/SOURCES" <<SRC
resonance-audio https://github.com/resonance-audio/resonance-audio $RA_COMMIT (Apache-2.0)
eigen https://gitlab.com/libeigen/eigen $EIGEN_COMMIT (MPL-2.0, EIGEN_MPL2_ONLY)
pffft https://bitbucket.org/jpommier/pffft $PFFFT_COMMIT (FFTPACK licence, BSD-like)
SADIE HRTF database (Apache-2.0), compiled in
Built by tools/build_resonance_audio.sh with NDK $(basename "$NDK"), android-26, c++_static
SRC
echo "built $OUT"
