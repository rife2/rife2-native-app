#!/usr/bin/env bash
# Goes through a day of work in the app that ios/build.sh installed, with a UI test that taps,
# types and signs as a technician does, starting from a fresh install. SHOTS names a directory
# for the screenshots that the readme shows.
#
#   ios/check-app.sh [simulator name]     on the newest iPhone by default
set -euo pipefail

cd "$(dirname "$0")/.."
udid=$(./bld app-simulator ios-sim ${1:+"$1"} | tail -1)
device=${1:-the newest iPhone}
command -v xcodegen > /dev/null || { echo "xcodegen is needed: brew install xcodegen" >&2; exit 1; }
xcrun simctl bootstatus "$udid" -b > /dev/null
# a fresh install has an empty database, so the day starts with nothing synced
xcrun simctl uninstall "$udid" com.uwyn.rife2.workorders
xcrun simctl install "$udid" ios/build/sim-app/Build/Products/Debug-iphonesimulator/WorkOrders.app
# a photo of the size that a phone's camera takes, for the test of the photos
photo=ios/build/ui/photo.jpg
mkdir -p ios/build/ui
if [ ! -f "$photo" ]; then
    sips -Z 4032 -s format jpeg -s formatOptions 85 "$(ls /System/Library/Desktop\ Pictures/*.heic | head -1)" --out "$photo" > /dev/null
fi
xcrun simctl addmedia "$udid" "$photo"

built=ios/build/ui
mkdir -p "$built"
cp ios/ui/Workday.swift ios/ui/project.yml "$built/"
(cd "$built" && xcodegen generate > /dev/null)
xcodebuild build-for-testing -project "$built/WorkOrdersUI.xcodeproj" -scheme WorkOrdersUI -destination "id=$udid" -derivedDataPath "$built/derived" > "$built/build.log" 2>&1 ||
    { echo "The UI test didn't build, see $built/build.log" >&2; exit 1; }
if [ -n "${SHOTS:-}" ]; then
    mkdir -p "$SHOTS"
    export TEST_RUNNER_SHOTS="$(cd "$SHOTS" && pwd)"
fi
rm -rf "$built/result.xcresult"
xcodebuild test-without-building -project "$built/WorkOrdersUI.xcodeproj" -scheme WorkOrdersUI -destination "id=$udid" -resultBundlePath "$built/result.xcresult" \
    -derivedDataPath "$built/derived" > "$built/test.log" 2>&1 &
running=$!
# xcodebuild sometimes stays for minutes after its tests ended, so what it wrote is what's waited for
for _ in $(seq 1 600); do
    kill -0 "$running" 2> /dev/null || break
    if grep -aEq "^Test Suite 'All tests' (passed|failed)" "$built/test.log"; then
        sleep 2
        kill "$running" 2> /dev/null || true
        break
    fi
    sleep 1
done
kill "$running" 2> /dev/null || true
wait "$running" 2> /dev/null || true
if grep -aq "^Test Suite 'All tests' passed" "$built/test.log"; then
    echo "The day of work and the photo passed in the app"
else
    grep -aE "error:|failed" "$built/test.log" | head -20 >&2 || true
    echo "The UI tests failed in the app, see $built/test.log" >&2
    exit 1
fi
