import SwiftUI

@main
struct SunradarApp: App {
    var body: some Scene {
        WindowGroup {
            SkyScreen()
                .preferredColorScheme(.dark)
                .statusBarHidden()
        }
    }
}
