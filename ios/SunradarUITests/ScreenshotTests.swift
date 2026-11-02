import XCTest

/// Takes the store screenshots. Each screen is a new launch of the app in its demo mode
/// (`-SunradarScreenshot <screen>`, see Sunradar/ScreenshotMode.swift and screenshots/README.md).
final class ScreenshotTests: XCTestCase {
    private static let screens = ["01-path", "02-date", "03-align", "04-moon"]

    override func setUpWithError() throws {
        continueAfterFailure = false
        // The default limit of 10 minutes is too short for four launches on a slow runner.
        executionTimeAllowance = 1800
    }

    @MainActor
    func testTakesTheStoreScreenshots() throws {
        // The scenes are for a device in portrait, also on an iPad that starts in landscape.
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        Snapshots.prepare(app)
        // The scenes are in the time zone of Vienna, also on a runner in UTC.
        app.launchEnvironment["TZ"] = "Europe/Vienna"
        let arguments = app.launchArguments
        for screen in Self.screens {
            app.launchArguments = arguments + ["-SunradarScreenshot", screen]
            app.launch()
            XCTAssertTrue(
                app.descendants(matching: .any)["sunradar-screenshot-ready"].waitForExistence(timeout: 120),
                "\(screen) did not get ready")
            if screen == "03-align" {
                // A SwiftUI menu does not open from code, so the test opens it.
                app.buttons["Align"].tap()
                // The menu items are buttons on the iPhone and menu items on the iPad. The label can
                // also contain the subtitle of the item.
                let item = app.descendants(matching: .any)
                    .matching(NSPredicate(format: "label BEGINSWITH %@", "With the sun")).firstMatch
                XCTAssertTrue(item.waitForExistence(timeout: 10), "The align menu did not open")
            }
            Snapshots.take(screen)
            app.terminate()
        }
    }
}
