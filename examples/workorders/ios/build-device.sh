#!/usr/bin/env bash
# Builds the Work Orders app for iPhones and iPads: the site as a native library for devices, which
# `./bld app-library ios` builds, in the Xcode project that XcodeGen makes of ios/project.yml,
# signed with the team that it names. Ship it with the JDK libraries that `./bld app-jdk-libs
# ios` builds rather than the stand-ins, `./bld app-library ios` warns about those.
#
#   ios/build-device.sh     into ios/build/device-app/Build/Products/Release-iphoneos/WorkOrders.app
set -euo pipefail

cd "$(dirname "$0")/.."
command -v xcodegen > /dev/null || { echo "xcodegen is needed: brew install xcodegen" >&2; exit 1; }
./bld app-library ios
rm -rf ios/build/app
mkdir -p ios/build/app
cp -R build/native/ios/workorders.framework ios/build/app/
(cd ios && xcodegen generate > /dev/null)
xcodebuild build -project ios/WorkOrders.xcodeproj -scheme WorkOrders -configuration Release -destination generic/platform=iOS \
    -derivedDataPath ios/build/device-app -allowProvisioningUpdates > ios/build/device-build.log 2>&1 ||
    { echo "The app didn't build, see ios/build/device-build.log" >&2; exit 1; }
app=ios/build/device-app/Build/Products/Release-iphoneos/WorkOrders.app
./bld app-check-privacy "$app"
echo "Built $app"
