// swift-tools-version: 5.9
// The Swift package of the sunradar core. It is at the root of the repository, because SwiftPM
// needs that for a package from a Git URL. The binary target is the xcframework of the C API
// (core/apple): the local build if it exists (python3 core/apple/build_xcframework.py), else the
// file of a release, which the release workflow stamps below.

import Foundation
import PackageDescription

let localXcframework = "core/apple/build/SunradarC.xcframework"
let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let binary: Target =
    FileManager.default.fileExists(atPath: packageRoot.appendingPathComponent(localXcframework).path)
    ? .binaryTarget(name: "SunradarC", path: localXcframework)
    : .binaryTarget(
        name: "SunradarC",
        url: "https://github.com/andiwand/sunradar/releases/download/core-v0.2.0/SunradarC.xcframework.zip",
        checksum: "7c412788e48c431825105001c24cbd3d2cc580091e74ef860fcf904bd585dec2"
    )

let package = Package(
    name: "Sunradar",
    // The same as os.version in core/conan/profiles/apple.jinja.
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "Sunradar", targets: ["Sunradar"])],
    targets: [
        binary,
        .target(
            name: "Sunradar",
            dependencies: ["SunradarC"],
            path: "core/apple/swift/Sources/Sunradar",
            linkerSettings: [.linkedLibrary("c++")]
        ),
        .testTarget(
            name: "SunradarTests",
            dependencies: ["Sunradar"],
            path: "core/apple/swift/Tests/SunradarTests"
        ),
    ]
)
