#!/usr/bin/env bash
# Builds Open Saber for phones running the HyperMagic OpenXR runtime, from source:
#   ports/opensaber/build.sh [work-dir]   ->  ports/opensaber/dist/OpenSaber-<version>-arm64.apk
#
# Open Saber (MIT) is leandrodreamer's Godot 4.3 / OpenXR port of NeoSpark314's Beep Saber. This
# is the game itself, not a remake: its code at the commit below, exported for Android with the
# Khronos OpenXR loader (which finds the HyperMagic runtime through the OpenXR broker) instead of
# Meta's (which runs only on a Quest). hypermagic.patch holds every change; see README.md.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${1:-$(mktemp -d)}
UPSTREAM=https://github.com/leandrodreamer/BeepSaber
COMMIT=64200ad3572b634ae57159e234e87b5e3e5d96eb   # branch godot-4-port, 2025-03-05
GODOT=4.3-stable
VERSION=0.5.0
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
mkdir -p "$WORK" "$HERE/dist"

# the game, at the pinned commit, with HyperMagic's changes
if [ ! -d "$WORK/game/.git" ]; then
  git init -q "$WORK/game"
  git -C "$WORK/game" fetch -q --depth 1 "$UPSTREAM" "$COMMIT"
  git -C "$WORK/game" checkout -q FETCH_HEAD
  git -C "$WORK/game" apply "$HERE/hypermagic.patch"
fi
# the demo song (TheFatRat) is not ours to redistribute: songs come from BeatSaver in the game
rm -rf "$WORK/game/game/data/maps/Songs/TheFatRat_Timelapse"

# Godot 4.3 (editor) and only its Android export templates, read out of the 1 GB template
# archive with HTTP range requests
G="$WORK/godot/Godot_v${GODOT}_linux.x86_64"
if [ ! -x "$G" ]; then
  mkdir -p "$WORK/godot"
  curl -sSL -o "$WORK/godot/godot.zip" "https://github.com/godotengine/godot/releases/download/$GODOT/Godot_v${GODOT}_linux.x86_64.zip"
  unzip -q -o "$WORK/godot/godot.zip" -d "$WORK/godot"
fi
T="$HOME/.local/share/godot/export_templates/${GODOT/-/.}"
if [ ! -f "$T/android_source.zip" ]; then
  mkdir -p "$T"
  python3 "$HERE/remote_zip.py" "https://github.com/godotengine/godot/releases/download/$GODOT/Godot_v${GODOT}_export_templates.tpz" "$T" \
    android_source.zip android_debug.apk android_release.apk version.txt
fi

# Godot's gradle build wants JDK 17
J=${JAVA17_HOME:-}
if [ -z "$J" ]; then
  J=$(ls -d "$WORK"/jdk17/jdk-17* 2>/dev/null | head -1 || true)
  if [ -z "$J" ]; then
    mkdir -p "$WORK/jdk17"
    curl -sSL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" | tar xz -C "$WORK/jdk17"
    J=$(ls -d "$WORK"/jdk17/jdk-17* | head -1)
  fi
fi

# where the SDK and JDK are, and the debug key (the release is signed with HyperMagic's key)
mkdir -p "$HOME/.config/godot"
cat > "$HOME/.config/godot/editor_settings-4.3.tres" <<EOF
[gd_resource type="EditorSettings" format=3]

[resource]
export/android/android_sdk_path = "$SDK"
export/android/java_sdk_path = "$J"
export/android/debug_keystore = "$ROOT/keystore/hyperxr-debug.jks"
export/android/debug_keystore_user = "androiddebugkey"
export/android/debug_keystore_pass = "android"
EOF
export JAVA_HOME="$J"
export GODOT_ANDROID_KEYSTORE_RELEASE_PATH="$ROOT/keystore/hyperxr-debug.jks"
export GODOT_ANDROID_KEYSTORE_RELEASE_USER=androiddebugkey
export GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD=android

cd "$WORK/game"
"$G" --headless --path . --import > "$WORK/import.log" 2>&1 || true
# the Android build template, installed as the editor does it (unpacked into android/build)
if [ ! -f android/build/build.gradle ]; then
  rm -rf android/build && mkdir -p android/build
  unzip -q "$T/android_source.zip" -d android/build
  cat "$T/version.txt" > android/.build_version
  touch android/build/.gdignore
fi
# Maven Central rate-limits busy machines: its Google mirror (same files) is asked first
for f in android/build/build.gradle android/build/settings.gradle; do
  grep -q googleapis "$f" 2>/dev/null || sed -i 's#^\(\s*\)mavenCentral()#\1maven { url "https://maven-central.storage-download.googleapis.com/maven2/" }\n\1mavenCentral()#' "$f"
done
mkdir -p build
"$G" --headless --path . --export-release "Android (HyperMagic)" build/OpenSaber.apk > "$WORK/export.log" 2>&1 || true
if [ ! -f build/OpenSaber.apk ]; then tail -40 "$WORK/export.log"; echo "export failed"; exit 1; fi
cp build/OpenSaber.apk "$HERE/dist/OpenSaber-$VERSION-arm64.apk"
echo "built $HERE/dist/OpenSaber-$VERSION-arm64.apk"
