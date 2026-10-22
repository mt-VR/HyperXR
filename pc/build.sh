#!/bin/sh
# Builds the Windows side of HyperXR SteamVR streaming with MinGW-w64 (no Windows needed):
#   dist/HyperXR-PC/HyperXR PC.exe                        app (phone IP, start SteamVR, status)
#   dist/HyperXR-PC/hyperxr/bin/win64/driver_hyperxr.dll  SteamVR driver
# and zips it as dist/HyperXR-PC.zip.
set -e
cd "$(dirname "$0")"
CXX=${CXX:-x86_64-w64-mingw32-g++-posix}
FLAGS="-std=c++17 -O2 -Wall -Wno-unknown-pragmas -Wno-missing-field-initializers -static -static-libgcc -static-libstdc++ -DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN -D_WIN32_WINNT=0x0A00"
OUT=dist/HyperXR-PC
rm -rf dist && mkdir -p "$OUT/hyperxr/bin/win64" "$OUT/hyperxr/resources/settings"
$CXX $FLAGS -shared -o "$OUT/hyperxr/bin/win64/driver_hyperxr.dll" driver/driver.cpp driver/encoder.cpp \
    -Ithird_party/openvr -Wl,--kill-at \
    -ld3d11 -ldxgi -lmfplat -lmfuuid -lmf -lole32 -loleaut32 -luuid -lws2_32 -lshell32
$CXX $FLAGS -mwindows -municode -o "$OUT/HyperXR PC.exe" app/main.cpp \
    -lws2_32 -liphlpapi -lshell32 -lole32 -luuid -lcomctl32 -lgdi32 -luser32
cp driver/driver.vrdrivermanifest "$OUT/hyperxr/"
cp driver/resources/settings/default.vrsettings "$OUT/hyperxr/resources/settings/"
cp README.md "$OUT/LEIA-ME.md"
cp third_party/openvr/LICENSE "$OUT/hyperxr/OPENVR-LICENSE.txt"
(cd dist && rm -f HyperXR-PC.zip && zip -qr HyperXR-PC.zip HyperXR-PC)
echo "built dist/HyperXR-PC.zip"
