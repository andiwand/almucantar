import SwiftUI

@main
struct SonnseiteApp: App {
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
