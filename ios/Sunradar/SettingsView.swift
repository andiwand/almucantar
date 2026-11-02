import SwiftUI

/// The keys of the settings in UserDefaults.
enum Settings {
    /// The terrain horizon is on. It is on by default; when it is off, the app downloads nothing.
    static let terrainHorizon = "terrainHorizon"
}

/// The settings and the credits.
struct SettingsView: SwiftUI.View {
    @AppStorage(Settings.terrainHorizon) private var terrainHorizon = true
    @Environment(\.dismiss) private var dismiss

    /// The attribution that the license of the Copernicus DEM requires.
    static let copernicusAttribution =
        "Terrain: Copernicus DEM GLO-90, © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, "
        + "provided under COPERNICUS by the European Union and ESA; all rights reserved."

    var body: some SwiftUI.View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Terrain horizon", isOn: $terrainHorizon)
                } footer: {
                    Text(
                        "Shows the mountains around you and the visible sunrise and sunset. The app downloads the "
                            + "elevation data around your place (a few MB per square degree) and keeps it for offline use.")
                }
                Section("Credits") {
                    Text(Self.copernicusAttribution).font(.footnote)
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
