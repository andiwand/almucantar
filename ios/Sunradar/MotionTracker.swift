import CoreMotion
import Foundation
import Sunradar

/// The orientation from CoreMotion and the heading filter of the core (the same design as the
/// Android app: PLAN.md, section 5.4a).
///
/// The reference frame xArbitraryCorrectedZVertical has z up and an arbitrary x; the heading
/// filter estimates the offset to true north, the hard-iron bias, and a local disturbance. iOS
/// gives a calibrated magnetic field only with a reference frame that uses the magnetometer, so
/// this one uses it a little for the drift. iOS does not give its bias estimate, so the filter
/// starts with a bias of 0 and corrects the error that the OS calibration leaves. A sun tap also
/// corrects that bias, so the magnetometer does not pull the offset back.
@MainActor
final class MotionTracker: ObservableObject {
    @Published private(set) var deviceToFrame: Sunradar.Quaternion?
    @Published private(set) var estimate = HeadingEstimate()
    @Published private(set) var available = true

    var observer = Observer(latitudeDeg: 48.2082, longitudeDeg: 16.3738)

    private let manager = CMMotionManager()
    private var filter: HeadingFilter?
    private var lastTimestamp: TimeInterval?
    private var pendingDtS = 0.0
    private var lastMagnetometerUpdate: TimeInterval = 0
    /// Whether CMAttitude.quaternion rotates from the reference frame to the device (true), or
    /// the other way round. Apple does not say clearly, so the tracker finds it from gravity.
    private var conjugate: Bool?

    func start() {
        guard manager.isDeviceMotionAvailable else {
            available = false
            return
        }
        manager.deviceMotionUpdateInterval = 1.0 / 60.0
        manager.startDeviceMotionUpdates(using: .xArbitraryCorrectedZVertical, to: .main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            MainActor.assumeIsolated { self.update(motion) }
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }

    /// The demo mode of the store screenshots: a fixed orientation and a fixed heading, without
    /// sensors.
    func startDemo(deviceToFrame q: Sunradar.Quaternion, headingSigmaDeg: Double) {
        available = true
        deviceToFrame = q
        estimate = HeadingEstimate(offsetDeg: 0, varianceDeg2: headingSigmaDeg * headingSigmaDeg)
    }

    /// The error that is left in a field that the OS calibrated, in µT.
    private static let calibratedBiasSigmaUt = 5.0

    func apply(_ measurement: HeadingMeasurement) {
        guard let start = startedFilter(), let updated = try? headingFilterUpdate(predicted(start), measurement)
        else { return }
        set(updated)
    }

    private func startedFilter() -> HeadingFilter? {
        filter ?? (try? headingFilterStart(biasUt: Vector3(x: 0, y: 0, z: 0), biasSigmaUt: Self.calibratedBiasSigmaUt))
    }

    private func predicted(_ f: HeadingFilter) -> HeadingFilter {
        let dt = pendingDtS
        pendingDtS = 0
        guard dt > 0 else { return f }
        return (try? headingFilterPredict(f, dtS: dt)) ?? f
    }

    private func set(_ f: HeadingFilter) {
        filter = f
        estimate = f.estimate
    }

    private func update(_ motion: CMDeviceMotion) {
        let a = motion.attitude.quaternion
        var q = Sunradar.Quaternion(w: a.w, x: a.x, y: a.y, z: a.z)
        if conjugate == nil { conjugate = Self.needsConjugate(q, gravity: motion.gravity) }  // nil until the tilt is clear
        if conjugate == true { q = Sunradar.Quaternion(w: q.w, x: -q.x, y: -q.y, z: -q.z) }

        if let last = lastTimestamp, motion.timestamp > last { pendingDtS += motion.timestamp - last }
        lastTimestamp = motion.timestamp
        deviceToFrame = q
        // The prediction copies the covariance, so it runs at most ten times per second.
        if pendingDtS >= 0.1, let f = filter { set(predicted(f)) }

        // The disturbance model of the filter assumes about one sample per second.
        let field = motion.magneticField
        if field.accuracy != .uncalibrated, motion.timestamp - lastMagnetometerUpdate >= 1,
            let start = startedFilter()
        {
            lastMagnetometerUpdate = motion.timestamp
            let f = Vector3(x: field.field.x, y: field.field.y, z: field.field.z)
            if let updated = try? headingFilterUpdateMagnetometer(
                predicted(start), deviceToFrame: q, fieldUt: f, observer: observer,
                unixUtc: Date().timeIntervalSince1970)
            {
                set(updated)
            }
        }
    }

    /// The gravity of CoreMotion points down, in the device frame. With the correct direction of
    /// the quaternion, rotating "down" of the frame into the device frame gives that vector. The
    /// two directions give almost the same vector if the device is nearly flat, so the function
    /// decides only if they differ clearly (a tilt of more than about 30°), else it returns nil.
    static func needsConjugate(_ q: Sunradar.Quaternion, gravity: CMAcceleration) -> Bool? {
        let down = (x: 0.0, y: 0.0, z: -1.0)
        let dot = { (v: (x: Double, y: Double, z: Double)) in v.x * gravity.x + v.y * gravity.y + v.z * gravity.z }
        // q rotates from the device to the frame, so its inverse rotates "down" into the device.
        let asGiven = dot(rotate(Sunradar.Quaternion(w: q.w, x: -q.x, y: -q.y, z: -q.z), down))
        let conjugated = dot(rotate(q, down))
        return abs(asGiven - conjugated) > 0.5 ? conjugated > asGiven : nil
    }

    static func rotate(_ q: Sunradar.Quaternion, _ v: (x: Double, y: Double, z: Double)) -> (x: Double, y: Double, z: Double) {
        // v' = v + 2 u × (u × v + w v)
        let u = (x: q.x, y: q.y, z: q.z)
        let t = (
            x: u.y * v.z - u.z * v.y + q.w * v.x, y: u.z * v.x - u.x * v.z + q.w * v.y,
            z: u.x * v.y - u.y * v.x + q.w * v.z
        )
        return (
            x: v.x + 2 * (u.y * t.z - u.z * t.y), y: v.y + 2 * (u.z * t.x - u.x * t.z),
            z: v.z + 2 * (u.x * t.y - u.y * t.x)
        )
    }
}
