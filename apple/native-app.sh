# Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
# Licensed under the Apache License, Version 2.0 (the "License")

# Sourced by a script that puts an app together with swiftc instead of Xcode: builds the Swift
# package of this repository, which runs the site inside the app and shows its pages, and sets
# what swiftc takes to use it.
#
#   . path/to/rife2-native-app/apple/native-app.sh
#   native_app <triple> <SDK, or nothing for this Mac> <where it's built>     sets native_app
#   swiftc ... "${native_app[@]}" ...
rife2_native_app=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

native_app() {
    local triple=$1 sdk=$2 built=$3
    mkdir -p "$built"
    built=$(cd "$built" && pwd)
    if [ -n "$sdk" ]; then
        (cd "$rife2_native_app" && swift build -c release --product RIFE2NativeApp --scratch-path "$built" --triple "$triple" --sdk "$(xcrun --sdk "$sdk" --show-sdk-path)" > "$built.log" 2>&1) ||
            { cat "$built.log" >&2; return 1; }
    else
        (cd "$rife2_native_app" && swift build -c release --product RIFE2NativeApp --scratch-path "$built" > "$built.log" 2>&1) || { cat "$built.log" >&2; return 1; }
    fi
    # its Swift, and the C functions of the site's library that it calls
    native_app=(-I "$built/release" -L "$built/release" -lRIFE2NativeApp -I "$rife2_native_app/apple/Sources/CRIFE2NativeApp/include")
}
