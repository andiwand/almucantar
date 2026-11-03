import MapKit
import Almucantar
import SwiftUI

/// A map to pick a landmark: tap a place that you can see from where you are. `onDone` gets the
/// place, or nil if the user cancels.
struct MapPicker: SwiftUI.View {
    let observer: Observer
    let onDone: (Observer?) -> Void

    @State private var position: MapCameraPosition
    @State private var landmark: CLLocationCoordinate2D?

    init(observer: Observer, onDone: @escaping (Observer?) -> Void) {
        self.observer = observer
        self.onDone = onDone
        let here = CLLocationCoordinate2D(latitude: observer.latitudeDeg, longitude: observer.longitudeDeg)
        _position = State(initialValue: .region(MKCoordinateRegion(center: here, latitudinalMeters: 3000, longitudinalMeters: 3000)))
    }

    var body: some SwiftUI.View {
        let here = CLLocationCoordinate2D(latitude: observer.latitudeDeg, longitude: observer.longitudeDeg)
        let picked = landmark.map { Observer(latitudeDeg: $0.latitude, longitudeDeg: $0.longitude) }
        VStack(spacing: 0) {
            MapReader { proxy in
                Map(position: $position) {
                    Annotation("You", coordinate: here) {
                        Circle().fill(.blue).stroke(.white, lineWidth: 2).frame(width: 14, height: 14)
                    }
                    if let landmark {
                        Marker("Landmark", coordinate: landmark).tint(.red)
                        MapPolyline(coordinates: [here, landmark]).stroke(.red, lineWidth: 2)
                    }
                }
                .onTapGesture { point in
                    if let coordinate = proxy.convert(point, from: .local) { landmark = coordinate }
                }
            }
            VStack(spacing: 8) {
                if let picked {
                    let distance = Geo.distanceM(from: observer, to: picked)
                    Text(
                        String(
                            format: "%.0f m away, bearing %.0f°", distance,
                            Geo.bearingDeg(from: observer, to: picked)))
                    if distance < 50 { Text("Too near: choose a landmark that is farther away.").foregroundColor(.orange) }
                } else {
                    Text("Tap a landmark that you can see from where you are, for example a tower.")
                }
                HStack {
                    Button("Cancel") { onDone(nil) }.buttonStyle(.bordered)
                    Button("Aim at it") { onDone(picked) }
                        .buttonStyle(.borderedProminent)
                        .disabled(picked.map { Geo.distanceM(from: observer, to: $0) < 50 } ?? true)
                }
            }
            .multilineTextAlignment(.center)
            .padding()
        }
    }
}
