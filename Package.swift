// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "RIFE2NativeApp",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "RIFE2NativeApp", targets: ["RIFE2NativeApp"]),
    ],
    targets: [
        // the C functions of the site's native library, which the app links
        .target(name: "CRIFE2NativeApp", path: "apple/Sources/CRIFE2NativeApp"),
        .target(name: "RIFE2NativeApp", dependencies: ["CRIFE2NativeApp"], path: "apple/Sources/RIFE2NativeApp"),
    ]
)
