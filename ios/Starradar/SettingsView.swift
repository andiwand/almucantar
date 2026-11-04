import SwiftUI

/// The keys of the settings of starradar in UserDefaults. The terrain uses the shared key.
enum StarSettings {
    /// The lines and the names of the constellations. On by default.
    static let lines = "constellationLines"
    /// The red light, so the eyes stay adapted to the dark. Off by default.
    static let red = "redLight"
}

/// The settings and the credits of the data.
struct SettingsView: SwiftUI.View {
    let starAttribution: String
    @AppStorage(StarSettings.lines) private var lines = true
    @AppStorage(Settings.terrainHorizon) private var terrainHorizon = true
    @AppStorage(StarSettings.red) private var red = false
    @Environment(\.dismiss) private var dismiss

    var body: some SwiftUI.View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Constellation lines", isOn: $lines)
                    Toggle("Terrain horizon", isOn: $terrainHorizon)
                    Toggle("Red light", isOn: $red)
                } footer: {
                    Text(
                        "The terrain horizon hides the stars behind the mountains. The app downloads the elevation data "
                            + "around your place and keeps it for offline use. The red light keeps your eyes adapted to the dark.")
                }
                Section("Credits") {
                    Text(starAttribution).font(.footnote)
                    Text("Terrain: " + DemCache.defaultSource.attribution).font(.footnote)
                    Text("Map: Natural Earth (public domain), naturalearthdata.com").font(.footnote)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
