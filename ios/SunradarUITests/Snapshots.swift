import Foundation
import XCTest

/// The handoff with fastlane snapshot, in place of its SnapshotHelper.swift: the test reads the
/// language and the locale that fastlane leaves, and writes each screenshot where fastlane collects
/// it. See https://docs.fastlane.tools/actions/snapshot/.
enum Snapshots {
    /// The directory that fastlane writes to and reads from. It exists only on a simulator.
    private static var handOff: URL? {
        guard let host = ProcessInfo.processInfo.environment["SIMULATOR_HOST_HOME"] else { return nil }
        return URL(fileURLWithPath: host).appendingPathComponent("Library/Caches/tools.fastlane")
    }

    private static var pictures: URL? {
        handOff?.appendingPathComponent("screenshots", isDirectory: true)
    }

    /// Gives the language and the locale of fastlane to the app.
    @MainActor
    static func prepare(_ app: XCUIApplication) {
        guard let handOff else {
            XCTFail("No SIMULATOR_HOST_HOME: fastlane takes the screenshots on a simulator.")
            return
        }
        let language = read(handOff.appendingPathComponent("language.txt"))
        if let language { app.launchArguments += ["-AppleLanguages", "(\(language))"] }
        // Without a locale file, the language is the locale for the dates and the numbers.
        let region = read(handOff.appendingPathComponent("locale.txt")) ?? language
        if let region { app.launchArguments += ["-AppleLocale", "\"\(region)\""] }
    }

    /// Writes `<device>-<screen>.png`, the name that scripts/screenshots/frame.py reads.
    @MainActor
    static func take(_ name: String) {
        guard let pictures, let simulator = ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] else {
            XCTFail("No place to write \(name): fastlane did not prepare this run.")
            return
        }
        // Some parts of SwiftUI move into place after the ready signal, for example a menu.
        Thread.sleep(forTimeInterval: 0.5)
        let file = pictures.appendingPathComponent("\(model(of: simulator))-\(name).png")
        do {
            try FileManager.default.createDirectory(at: pictures, withIntermediateDirectories: true)
            try XCUIScreen.main.screenshot().pngRepresentation.write(to: file, options: .atomic)
        } catch {
            XCTFail("Cannot write \(file.lastPathComponent): \(error.localizedDescription)")
        }
    }

    /// The name of the model. xcodebuild calls a device "Clone 2 of iPhone 16 Pro Max" when it
    /// runs several at the same time.
    private static func model(of simulator: String) -> String {
        guard simulator.hasPrefix("Clone "), let of = simulator.range(of: " of ") else { return simulator }
        return String(simulator[of.upperBound...])
    }

    /// The first line of a file of fastlane, or nil if the file is empty or missing.
    private static func read(_ file: URL) -> String? {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return nil }
        let line = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return line.isEmpty ? nil : line
    }
}
