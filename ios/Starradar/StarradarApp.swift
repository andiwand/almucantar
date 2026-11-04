import SwiftUI

@main
struct StarradarApp: App {
    /// The star data is a few hundred kilobytes; the app reads it once. The file is a resource of
    /// the app, so an error is a defect of the build.
    private let stars: StarData = {
        guard let url = Bundle.main.url(forResource: "stars", withExtension: "json"),
            let data = try? Data(contentsOf: url), let stars = try? StarData.decode(data)
        else { fatalError("stars.json is missing or not valid") }
        return stars
    }()

    init() {
        // The user holds the phone up and watches the sky, so the screen stays on.
        UIApplication.shared.isIdleTimerDisabled = true
    }

    var body: some Scene {
        WindowGroup {
            StarScreen(stars: stars)
                .preferredColorScheme(.dark)
                .statusBarHidden()
        }
    }
}
