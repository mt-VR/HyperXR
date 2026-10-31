#!/usr/bin/env bash
# Builds the Godot XR Tools demo for phones running the HyperMagic OpenXR runtime, from source:
#   ports/xrtools/build.sh [work-dir]   ->  ports/xrtools/dist/XRToolsDemo-<version>-arm64.apk
#
# Godot XR Tools (MIT, Bastiaan Olij and contributors) is Godot's VR toolkit; its repository is
# also a playable demo: a hub of small worlds to walk, climb, pick things up, shoot and fly in.
# This is that demo at the commit below, exported for Android with the Khronos OpenXR loader (which
# finds the HyperMagic runtime through the OpenXR broker) instead of a headset vendor's. Every
# change is made below, in the open; see README.md.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${1:-$(mktemp -d)}
UPSTREAM=https://github.com/GodotVR/godot-xr-tools
COMMIT=6c6189bdfcdf855052ef1aeb9f3e0ec4c7a7eb60   # master, 2026-10-10
GODOT=4.4.1-stable
VERSION=4.4.0
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
mkdir -p "$WORK" "$HERE/dist"

# the demo, at the pinned commit
if [ ! -d "$WORK/game/.git" ]; then
  git init -q "$WORK/game"
  git -C "$WORK/game" fetch -q --depth 1 "$UPSTREAM" "$COMMIT"
  git -C "$WORK/game" checkout -q FETCH_HEAD
fi

# HyperMagic's changes: the export plugin with the Khronos loader, an Android preset that uses it
# (no vendor plugin), HyperMagic's package name; the Godot 3 GDNative singleton is dropped
cd "$WORK/game"
git checkout -q -- project.godot export_presets.cfg
rm -rf addons/hypermagic_export && cp -r "$ROOT/ports/common/hypermagic_export" addons/
python3 - <<'EOF'
import re
p = open('project.godot').read()
p = p.replace('enabled=PackedStringArray("res://addons/godot-xr-tools/plugin.cfg")',
              'enabled=PackedStringArray("res://addons/godot-xr-tools/plugin.cfg", "res://addons/hypermagic_export/plugin.cfg")')
p = re.sub(r'\[gdnative\]\s*\n\s*singletons=\[[^\]]*\]\s*\n', '', p)
open('project.godot', 'w').write(p)
e = open('export_presets.cfg').read()
start = e.index('name="Android Khronos"')
m = re.compile(r'^\[preset\.\d+\]$', re.M).search(e, start)
end = m.start() if m else len(e)
s = e[start:end]
s = s.replace('name="Android Khronos"', 'name="Android (HyperMagic)"')
s = re.sub(r'export_path="[^"]*"', 'export_path="build/XRToolsDemo.apk"', s)
s = s.replace('package/unique_name="org.godotengine.godotxrtoolsdemo"', 'package/unique_name="com.hypermagic.xrtoolsdemo"')
s = s.replace('xr_features/enable_khronos_plugin=true', 'xr_features/enable_khronos_plugin=false')
s = re.sub(r'version/name="[^"]*"', 'version/name="4.4.0"', s)
open('export_presets.cfg', 'w').write(e[:start] + s + e[end:])
EOF
grep -q 'com.hypermagic.xrtoolsdemo' export_presets.cfg || { echo "preset edit failed"; exit 1; }

# Godot (editor) and only its Android export templates, read out of the template archive with
# HTTP range requests
G="$WORK/godot/Godot_v${GODOT}_linux.x86_64"
if [ ! -x "$G" ]; then
  mkdir -p "$WORK/godot"
  curl -sSL -o "$WORK/godot/godot.zip" "https://github.com/godotengine/godot/releases/download/$GODOT/Godot_v${GODOT}_linux.x86_64.zip"
  unzip -q -o "$WORK/godot/godot.zip" -d "$WORK/godot"
  rm -f "$WORK/godot/godot.zip"
fi
T="$HOME/.local/share/godot/export_templates/${GODOT/-/.}"
if [ ! -f "$T/android_source.zip" ]; then
  mkdir -p "$T"
  python3 "$ROOT/ports/common/remote_zip.py" "https://github.com/godotengine/godot/releases/download/$GODOT/Godot_v${GODOT}_export_templates.tpz" "$T" \
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

mkdir -p "$HOME/.config/godot"
cat > "$HOME/.config/godot/editor_settings-4.4.tres" <<EOF
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

"$G" --headless --path . --import > "$WORK/import.log" 2>&1 || true
if [ ! -f android/build/build.gradle ]; then
  rm -rf android/build && mkdir -p android/build
  unzip -q "$T/android_source.zip" -d android/build
  cat "$T/version.txt" > android/.build_version
  touch android/build/.gdignore
fi
for f in android/build/build.gradle android/build/settings.gradle; do
  grep -q googleapis "$f" 2>/dev/null || sed -i 's#^\(\s*\)mavenCentral()#\1maven { url "https://maven-central.storage-download.googleapis.com/maven2/" }\n\1mavenCentral()#' "$f"
done
mkdir -p build
"$G" --headless --path . --export-release "Android (HyperMagic)" build/XRToolsDemo.apk > "$WORK/export.log" 2>&1 || true
if [ ! -f build/XRToolsDemo.apk ]; then tail -40 "$WORK/export.log"; echo "export failed"; exit 1; fi
cp build/XRToolsDemo.apk "$HERE/dist/XRToolsDemo-$VERSION-arm64.apk"
echo "built $HERE/dist/XRToolsDemo-$VERSION-arm64.apk"
