#!/usr/bin/env bash
# Builds the Work Orders app for the simulator: the technician's site as a native library, which
# `./bld app-library ios-sim` builds, inside a SwiftUI app whose web view asks the library for
# every page instead of a server. Needs GraalVM, Xcode and XcodeGen, see the README.
#
#   ios/build.sh [simulator name]     builds, installs and launches, on the newest iPhone by default
#
# OFFICE names an office to sync with instead of the demo office inside the app, for instance
# OFFICE=http://localhost:8080 ios/build.sh while ./bld run serves the office on this Mac.
set -euo pipefail

cd "$(dirname "$0")/.."
udid=$(./bld app-simulator ios-sim ${1:+"$1"} | tail -1)
device=${1:-the newest iPhone}
./bld app-library ios-sim

command -v xcodegen > /dev/null || { echo "xcodegen is needed: brew install xcodegen" >&2; exit 1; }
rm -rf ios/build/app
mkdir -p ios/build/app
cp -R build/native/ios-sim/workorders.framework ios/build/app/
(cd ios && xcodegen generate > /dev/null)
xcodebuild build -project ios/WorkOrders.xcodeproj -scheme WorkOrders -configuration Debug -destination "id=$udid" \
    -derivedDataPath ios/build/sim-app > ios/build/sim-build.log 2>&1 ||
    { echo "The app didn't build, see ios/build/sim-build.log" >&2; exit 1; }
app=ios/build/sim-app/Build/Products/Debug-iphonesimulator/WorkOrders.app
./bld app-check-privacy "$app"

xcrun simctl bootstatus "$udid" -b
xcrun simctl install "$udid" "$app"
# a launch argument of the form -key value is a setting of the app, which the site reads
xcrun simctl launch --terminate-running-process "$udid" com.uwyn.rife2.workorders ${OFFICE:+-workorders.office "$OFFICE"}
echo "Launched on $device, shut it down with: xcrun simctl shutdown $udid"
