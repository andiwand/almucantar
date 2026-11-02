import SwiftUI

@main
struct SunradarApp: App {
    init() {
        ScreenshotMode.prepare()
    }

    var body: some Scene {
        WindowGroup {
            SkyScreen()
                .preferredColorScheme(.dark)
                .statusBarHidden()
        }
    }
}
