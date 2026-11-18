#!/usr/bin/env bash
# Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
# Licensed under the Apache License, Version 2.0 (the "License")

# Shows a site's native library for Android in an app made of the classes of rife2-native-app, on
# the emulator or the device that adb sees, and goes through it: each step either waits until the
# screen shows a text, or taps what shows a text.
#
#   checks/android/check.sh path/to/libsite.so [see:text|tap:text]...
#
# Needs the Android SDK of ANDROID_HOME, or of Android Studio on a Mac, with build-tools 35 and
# platform 35, and a JDK.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 path/to/libsite.so [see:text|tap:text]..." >&2
    exit 2
fi
library=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
shift
name=$(basename "$library" .so)
name=${name#lib}
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
tools="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"
adb="$sdk/platform-tools/adb"
out="$root/build/android-check"
rm -rf "$out"
mkdir -p "$out/classes" "$out/dex" "$out/apk/lib/arm64-v8a"

sed "s/LIBRARY/$name/" "$here/AndroidManifest.xml" > "$out/AndroidManifest.xml"
"$tools/aapt2" link -o "$out/app.apk" --manifest "$out/AndroidManifest.xml" -I "$platform"
javac --release 17 -nowarn -cp "$platform" -d "$out/classes" "$root"/android/src/rife/nativeapp/android/*.java "$here"/src/rife/nativeapp/check/*.java
classes=()
while IFS= read -r class; do
    classes+=("$class")
done < <(find "$out/classes" -name "*.class")
"$tools/d8" --lib "$platform" --min-api 29 --output "$out/dex" "${classes[@]}"
cp "$library" "$out/apk/lib/arm64-v8a/"
(cd "$out/dex" && zip -q ../app.apk classes.dex)
(cd "$out/apk" && zip -q -r ../app.apk lib)
"$tools/zipalign" -f -p 4 "$out/app.apk" "$out/aligned.apk"
# a key of this check only
keytool -genkeypair -keystore "$out/check.jks" -storepass check1 -keypass check1 -alias check -keyalg RSA -keysize 2048 -validity 1 \
    -dname "CN=rife2-native-app check" > /dev/null 2>&1
"$tools/apksigner" sign --ks "$out/check.jks" --ks-pass pass:check1 --out "$out/check.apk" "$out/aligned.apk"

"$adb" uninstall rife.nativeapp.check > /dev/null 2>&1 || true
"$adb" install "$out/check.apk" > /dev/null
trap '"$adb" uninstall rife.nativeapp.check > /dev/null 2>&1 || true' EXIT
# a notice can cover the top of the screen right after an emulator started
"$adb" shell cmd statusbar collapse > /dev/null 2>&1 || true
"$adb" shell am start -W -n rife.nativeapp.check/.MainActivity > /dev/null

# The center of what shows a text on the screen, or nothing: the smallest that does, since the
# web view and the elements around a text say it too.
shown() {
    "$adb" shell rm -f /sdcard/check.xml > /dev/null 2>&1 || true
    "$adb" shell uiautomator dump /sdcard/check.xml > /dev/null 2>&1 || return 0
    "$adb" exec-out cat /sdcard/check.xml 2> /dev/null | python3 -I -c '
import re, sys, xml.etree.ElementTree as tree
wanted = sys.argv[1].lower()
try:
    nodes = list(tree.fromstring(sys.stdin.read()).iter("node"))
except tree.ParseError:
    sys.exit(0)
found = []
for node in nodes:
    said = (node.get("text", "") + " " + node.get("content-desc", "")).lower()
    left, top, right, bottom = map(int, re.findall(r"-?\d+", node.get("bounds", "[0,0][0,0]")))
    if wanted in said and right > left and bottom > top:
        found.append(((right - left) * (bottom - top), (left + right) // 2, (top + bottom) // 2))
if found:
    _, x, y = min(found)
    print(x, y)
' "$1" || true
}

for step in "$@"; do
    what=${step%%:*}
    text=${step#*:}
    if [ "$what" != see ] && [ "$what" != tap ] || [ "$text" = "$step" ]; then
        echo "A step is see:text or tap:text, not $step" >&2
        exit 2
    fi
    at=""
    for _ in $(seq 1 30); do
        at=$(shown "$text")
        [ -n "$at" ] && break
        sleep 1
    done
    if [ -z "$at" ]; then
        "$adb" exec-out screencap -p > "$out/failed.png" || true
        echo "The app doesn't show \"$text\", see $out/failed.png" >&2
        exit 1
    fi
    if [ "$what" = tap ]; then
        "$adb" shell input tap $at
    fi
    echo "$step"
done
echo "The app went through ${#} steps with the library $name"
