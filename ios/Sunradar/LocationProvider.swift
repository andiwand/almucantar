import CoreLocation
import Foundation
import Sunradar

/// The place of the user: the location (reduced accuracy is sufficient), else Vienna.
@MainActor
final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var observer = Observer(latitudeDeg: 48.2082, longitudeDeg: 16.3738)
    @Published private(set) var text = "Vienna · no location"

    private let manager = CLLocationManager()

    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        manager.requestWhenInUseAuthorization()
        manager.requestLocation()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in
            self.observer = Observer(
                latitudeDeg: location.coordinate.latitude, longitudeDeg: location.coordinate.longitude)
            self.text = String(
                format: "%.3f°, %.3f° · ±%.0f m", location.coordinate.latitude, location.coordinate.longitude,
                location.horizontalAccuracy)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.requestLocation()
        }
    }
}
