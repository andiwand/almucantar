import Foundation

/// One screen of the store screenshots: the time, the view, and the state of the panels.
/// See screenshots/README.md for the table that the Android app uses too.
struct ScreenshotScene {
    /// The local time in Europe/Vienna.
    let year, month, day, hour, minute: Int
    /// The view: the back camera to the azimuth, pitched up.
    let azimuthDeg, pitchDeg: Double
    /// The uncertainty of the heading.
    let headingSigmaDeg: Double
    /// The time panel is open.
    let showTime: Bool

    static let timeZone = TimeZone(identifier: "Europe/Vienna")!

    /// The instant of the scene.
    var date: Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = Self.timeZone
        return calendar.date(
            from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    static let scenes: [String: ScreenshotScene] = [
        "01-path": ScreenshotScene(
            year: 2026, month: 8, day: 20, hour: 19, minute: 0, azimuthDeg: 255, pitchDeg: 20,
            headingSigmaDeg: 0.5, showTime: false),
        "02-date": ScreenshotScene(
            year: 2026, month: 12, day: 21, hour: 12, minute: 0, azimuthDeg: 180, pitchDeg: 30,
            headingSigmaDeg: 0.5, showTime: true),
        // The UI test opens the align menu, because a SwiftUI menu does not open from code.
        "03-align": ScreenshotScene(
            year: 2026, month: 5, day: 12, hour: 10, minute: 0, azimuthDeg: 125, pitchDeg: 35,
            headingSigmaDeg: 12, showTime: false),
        // The moon is 73 % lit at 207.5° and 37.8° high, 20° above the path of the sun.
        "04-moon": ScreenshotScene(
            year: 2026, month: 11, day: 19, hour: 21, minute: 0, azimuthDeg: 195, pitchDeg: 25,
            headingSigmaDeg: 0.5, showTime: false),
    ]
}

/// The demo mode for the store screenshots. It exists only in debug builds: the UI test starts
/// the app with the launch argument `-SunradarScreenshot <screen>`, and `UserDefaults` reads it.
/// A release build always gives nil, so the app works as usual.
enum ScreenshotMode {
    /// The accessibility identifier that the UI test waits for.
    static let readyIdentifier = "sunradar-screenshot-ready"

    static let scene: ScreenshotScene? = {
        #if DEBUG
            guard let name = UserDefaults.standard.string(forKey: "SunradarScreenshot") else { return nil }
            return ScreenshotScene.scenes[name]
        #else
            return nil
        #endif
    }()

    /// Sets the time zone of the app to the one of the scenes, before a view reads the calendar.
    static func prepare() {
        if scene != nil { NSTimeZone.default = ScreenshotScene.timeZone }
    }
}
