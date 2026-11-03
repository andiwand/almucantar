import CoreLocation
import Foundation
import Almucantar

/// The last place, in UserDefaults, so the app starts at that place also offline and without a
/// location permission.
struct StoredPlace: Codable, Equatable {
    var latitudeDeg: Double
    var longitudeDeg: Double
    var name: String
    /// The IANA name of the time zone of the place.
    var timeZone: String
    /// The user dropped the place on the map. A device location does not replace it.
    var manual: Bool
    /// The accuracy of a device location in metres, or nil for a manual place.
    var accuracyM: Double?

    static let key = "place"

    static func load(from defaults: UserDefaults) -> StoredPlace? {
        guard let data = defaults.data(forKey: key), let place = try? JSONDecoder().decode(StoredPlace.self, from: data),
            place.latitudeDeg.isFinite, place.longitudeDeg.isFinite, TimeZone(identifier: place.timeZone) != nil
        else { return nil }
        return place
    }

    func save(to defaults: UserDefaults) {
        if let data = try? JSONEncoder().encode(self) { defaults.set(data, forKey: Self.key) }
    }
}

/// The place of the app: the device location (an accuracy of a kilometer is sufficient), a place
/// that the user dropped on the map (manual), or the last place. Without any of them, the place is
/// Vienna. A manual place stays until the user chooses the device location again. The times use
/// the time zone of the place.
@MainActor
final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let defaultObserver = Observer(latitudeDeg: 48.2082, longitudeDeg: 16.3738)
    static let defaultTimeZone = TimeZone(identifier: "Europe/Vienna")!

    @Published private(set) var observer = defaultObserver
    @Published private(set) var text = "Vienna · no location"
    @Published private(set) var timeZone = defaultTimeZone
    /// The user dropped the place on the map.
    @Published private(set) var manual = false
    /// The place is not the default place: it is a device location, a manual place, or the last
    /// place. The terrain and the landmark alignment need it.
    @Published private(set) var hasPlace = false
    /// The horizontal accuracy of the device location in metres, or nil for a manual place and the
    /// default place.
    @Published private(set) var accuracyM: Double?
    /// A message for the user, for example when the device location fails.
    @Published var notice: String?

    private let manager = CLLocationManager()
    private let defaults: UserDefaults
    /// The demo mode does not read or write the stored place.
    private var stores = true
    /// True after "Use my location": the next device location replaces also a manual place.
    private var wantsDevice = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        super.init()
        if ScreenshotMode.scene == nil, let place = StoredPlace.load(from: defaults) { apply(place) }
    }

    /// The demo mode of the store screenshots: Vienna, without a request for the location and
    /// without the stored place.
    func startDemo() {
        stores = false
        observer = Self.defaultObserver
        text = "Vienna"
        timeZone = ScreenshotScene.timeZone
        manual = false
        hasPlace = false
        accuracyM = 50
    }

    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        manager.requestWhenInUseAuthorization()
        manager.requestLocation()
    }

    /// Sets a place that the user chose on the map or in the search.
    func drop(_ observer: Observer, name: String, timeZone: TimeZone) {
        let place = StoredPlace(
            latitudeDeg: observer.latitudeDeg, longitudeDeg: observer.longitudeDeg, name: name,
            timeZone: timeZone.identifier, manual: true)
        wantsDevice = false
        apply(place)
        save(place)
    }

    /// Asks for the device location. The next device location replaces also a manual place; until
    /// then, the place stays.
    func useDeviceLocation() {
        notice = nil
        wantsDevice = true
        start()
    }

    private func apply(_ place: StoredPlace) {
        observer = Observer(latitudeDeg: place.latitudeDeg, longitudeDeg: place.longitudeDeg)
        text = place.name
        timeZone = TimeZone(identifier: place.timeZone) ?? .current
        manual = place.manual
        accuracyM = place.accuracyM
        hasPlace = true
    }

    private func save(_ place: StoredPlace) {
        if stores { place.save(to: defaults) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in
            // A place that the user dropped stays, until the user chooses "Use my location".
            if (self.manual && !self.wantsDevice) || !self.stores { return }
            self.wantsDevice = false
            let c = location.coordinate
            let place = StoredPlace(
                latitudeDeg: c.latitude, longitudeDeg: c.longitude,
                name: String(format: "%.3f°, %.3f° · ±%.0f m", c.latitude, c.longitude, location.horizontalAccuracy),
                // The device location is where the device is, so the zone of the device applies.
                timeZone: TimeZone.current.identifier, manual: false, accuracyM: location.horizontalAccuracy)
            self.apply(place)
            self.save(place)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            if self.wantsDevice {
                self.wantsDevice = false
                self.notice = "No location: the place stays (\(self.text))."
            } else if self.hasPlace && !self.manual {
                self.notice = "No location: the app uses the last place (\(self.text))."
            }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.requestLocation()
        }
    }
}
