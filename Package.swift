// swift-tools-version: 5.9
// The Swift package of the almucantar core. It is at the root of the repository, because SwiftPM
// needs that for a package from a Git URL. The binary target is the xcframework of the C API
// (core/apple): the local build if it exists (python3 core/apple/build_xcframework.py), else the
// file of a release, which the release workflow stamps below.

import Foundation
import PackageDescription

let localXcframework = "core/apple/build/AlmucantarC.xcframework"
let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let binary: Target =
    FileManager.default.fileExists(atPath: packageRoot.appendingPathComponent(localXcframework).path)
    ? .binaryTarget(name: "AlmucantarC", path: localXcframework)
    : .binaryTarget(
        name: "AlmucantarC",
        url: "https://github.com/andiwand/almucantar/releases/download/UNRELEASED/AlmucantarC.xcframework.zip",
        checksum: "0000000000000000000000000000000000000000000000000000000000000000"
    )

let package = Package(
    name: "Almucantar",
    // The same as os.version in core/conan/profiles/apple.jinja.
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "Almucantar", targets: ["Almucantar"])],
    targets: [
        binary,
        .target(
            name: "Almucantar",
            dependencies: ["AlmucantarC"],
            path: "core/apple/swift/Sources/Almucantar",
            linkerSettings: [.linkedLibrary("c++")]
        ),
        .testTarget(
            name: "AlmucantarTests",
            dependencies: ["Almucantar"],
            path: "core/apple/swift/Tests/AlmucantarTests"
        ),
    ]
)
