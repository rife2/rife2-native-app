// swift-tools-version:5.10
/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
import PackageDescription

let package = Package(
    name: "RIFE2NativeApp",
    platforms: [.iOS(.v17), .macOS(.v14), .visionOS("26.0")],
    products: [
        .library(name: "RIFE2NativeApp", targets: ["RIFE2NativeApp"]),
    ],
    targets: [
        // the C functions of the site's native library, which the app links
        .target(name: "CRIFE2NativeApp", path: "apple/Sources/CRIFE2NativeApp"),
        .target(name: "RIFE2NativeApp", dependencies: ["CRIFE2NativeApp"], path: "apple/Sources/RIFE2NativeApp"),
        // The site's library is loaded by the tests that need one, see
        // RIFE2_NATIVE_APP_LIBRARY, so its functions are found while they run.
        .testTarget(name: "RIFE2NativeAppTests", dependencies: ["RIFE2NativeApp"], path: "apple/Tests/RIFE2NativeAppTests",
                    linkerSettings: [.unsafeFlags(["-Xlinker", "-undefined", "-Xlinker", "dynamic_lookup"])]),
    ]
)
