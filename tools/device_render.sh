#!/usr/bin/env bash
# Renders documents on a connected device or emulator (DeviceRenderTool) and
# pulls the pages back: tools/device_render.sh <out dir> <document>...
# Uses the device adb picks (set ANDROID_SERIAL to choose one).
set -euo pipefail
cd "$(dirname "$0")/.."
out=$1; shift
pkg=io.github.baeseungwon1010.officecanvas.test
dir=/sdcard/Android/data/$pkg/files
run() {
    adb shell am instrument -w -e class io.github.baeseungwon1010.officecanvas.DeviceRenderTool \
        "$pkg/androidx.test.runner.AndroidJUnitRunner" | tail -3
}
./gradlew -q :officecanvas:installDebugAndroidTest
# The app has to create its own files directory: one the shell made first isn't
# readable to it. The first run (with nothing to render) does that.
adb shell rm -rf "$dir/in" "$dir/out"
run >/dev/null
adb shell mkdir -p "$dir/in"
for f in "$@"; do adb push -q "$f" "$dir/in/" >/dev/null; done
run
rm -rf "$out"; mkdir -p "$out"
adb pull -q "$dir/out/." "$out" >/dev/null
cat "$out/TIMES"
