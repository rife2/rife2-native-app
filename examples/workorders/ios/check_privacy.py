#!/usr/bin/env python3
"""Checks a built app's privacy manifests against the APIs its binaries use that Apple asks a
reason for, so that App Store Connect never turns a build away for one left out, and a manifest
never claims one that isn't used. ios/build.sh and ios/build-device.sh run it on every build.

    ios/check_privacy.py APP   APP: a built WorkOrders.app for iOS

The app's manifest is app/PrivacyInfo.xcprivacy. The site, compiled to native code with the
JDK's own, is a framework inside the app with a manifest of its own,
the one that `./bld app-library` writes from what the library uses, and is checked the
same way.

The same as the one of Gates of Humanity, a RIFE2 app on the App Store."""
import os
import plistlib
import subprocess
import sys

# Apple's required reason APIs: the symbols a binary imports for each, and the selectors among its
# strings, from developer.apple.com/documentation/bundleresources/privacy-manifest-files
CATEGORIES = {
    "FileTimestamp": ({"_stat", "_fstat", "_lstat", "_fstatat", "_getattrlist", "_fgetattrlist", "_getattrlistat",
                       "_getattrlistbulk", "_NSFileCreationDate", "_NSFileModificationDate",
                       "_NSURLContentModificationDateKey", "_NSURLCreationDateKey"}, set()),
    "SystemBootTime": ({"_mach_absolute_time"}, {"systemUptime"}),
    "DiskSpace": ({"_statfs", "_statvfs", "_fstatfs", "_fstatvfs", "_getattrlist", "_fgetattrlist", "_getattrlistat",
                   "_NSFileSystemFreeSize", "_NSFileSystemSize", "_NSURLVolumeAvailableCapacityKey",
                   "_NSURLVolumeAvailableCapacityForImportantUsageKey",
                   "_NSURLVolumeAvailableCapacityForOpportunisticUsageKey", "_NSURLVolumeTotalCapacityKey"}, set()),
    # SwiftUI's AppStorage keeps its values there without the binary naming the class
    "UserDefaults": ({"_OBJC_CLASS_$_NSUserDefaults", "_$s7SwiftUI10AppStorageVMn"}, {"standardUserDefaults"}),
    "ActiveKeyboards": (set(), {"activeInputModes"}),
}
PREFIX = "NSPrivacyAccessedAPICategory"


def used(symbols, strings):
    return {name for name, (wanted, selectors) in CATEGORIES.items() if symbols & wanted or strings & selectors}


def problems(uses, manifest):
    if manifest is None:
        return ["no PrivacyInfo.xcprivacy"]
    found = []
    if manifest.get("NSPrivacyTracking", True):
        found.append("NSPrivacyTracking isn't false")
    declared = {}
    for entry in manifest.get("NSPrivacyAccessedAPITypes", []):
        declared[entry.get("NSPrivacyAccessedAPIType", "").removeprefix(PREFIX)] = entry.get("NSPrivacyAccessedAPITypeReasons", [])
    for name in sorted(uses - declared.keys()):
        found.append(f"{name} is used and not declared")
    for name in sorted(declared.keys() - uses):
        found.append(f"{name} is declared and not used")
    for name in sorted(uses & declared.keys()):
        if not declared[name]:
            found.append(f"{name} is declared without a reason")
    return found


def read(bundle):
    with open(os.path.join(bundle, "Info.plist"), "rb") as f:
        binary = os.path.join(bundle, plistlib.load(f)["CFBundleExecutable"])
    symbols = set()
    strings = set()
    # a debug build of Xcode has the app's code in a library beside its executable
    for scanned in (binary, binary + ".debug.dylib"):
        if os.path.exists(scanned):
            symbols |= set(subprocess.run(["nm", "-u", scanned], capture_output=True, text=True, check=True).stdout.split())
            strings |= set(subprocess.run(["strings", scanned], capture_output=True, text=True, check=True).stdout.splitlines())
    manifest = None
    path = os.path.join(bundle, "PrivacyInfo.xcprivacy")
    if os.path.exists(path):
        with open(path, "rb") as f:
            manifest = plistlib.load(f)
    return used(symbols, strings), manifest


def check(app):
    """The categories the app, its frameworks and its extensions use and what's wrong in each,
    the problems of one inside the app named after it; one that uses none needs no manifest."""
    uses, manifest = read(app)
    found = problems(uses, manifest)
    for directory, kind in (("Frameworks", ".framework"), ("PlugIns", ".appex")):
        inside = os.path.join(app, directory)
        for name in sorted(os.listdir(inside)) if os.path.isdir(inside) else []:
            if name.endswith(kind):
                inner, kept = read(os.path.join(inside, name))
                if inner or kept is not None:
                    found += [name + ": " + p for p in problems(inner, kept)]
                uses |= inner
    return uses, found


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    uses, found = check(sys.argv[1])
    if found:
        sys.exit("the privacy manifest of " + sys.argv[1] + " is wrong: " + "; ".join(found))
    print("the privacy manifests declare what the binaries use: " + ", ".join(sorted(uses)))


if __name__ == "__main__":
    main()
