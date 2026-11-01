import SunradarC

// The value types of the public C++ API (core/src/sunradar). The names are the C++ names in
// camel case. Each default comes from the C API (sr_default_*), so it cannot drift from C++.

/// A place on the Earth, in geodetic coordinates (WGS 84).
public struct Observer: Equatable, Sendable {
    public var latitudeDeg: Double
    public var longitudeDeg: Double
    public init(latitudeDeg: Double, longitudeDeg: Double) {
        self.latitudeDeg = latitudeDeg
        self.longitudeDeg = longitudeDeg
    }
}

/// A direction: azimuth from true north, clockwise; altitude above the horizon.
public struct Horizontal: Equatable, Sendable {
    public var azimuthDeg: Double
    public var altitudeDeg: Double
    public init(azimuthDeg: Double, altitudeDeg: Double) {
        self.azimuthDeg = azimuthDeg
        self.altitudeDeg = altitudeDeg
    }
}

public struct Atmosphere: Equatable, Sendable {
    public var pressureHpa: Double
    public var temperatureC: Double
    public init(pressureHpa: Double = Atmosphere.c.pressure_hpa, temperatureC: Double = Atmosphere.c.temperature_c) {
        self.pressureHpa = pressureHpa
        self.temperatureC = temperatureC
    }
    @usableFromInline static let c = sr_default_sun_options().atmosphere
}

/// A unit quaternion; for the view, the device frame to East-North-Up.
public struct Quaternion: Equatable, Sendable {
    public var w, x, y, z: Double
    public init(w: Double = Quaternion.c.w, x: Double = Quaternion.c.x, y: Double = Quaternion.c.y, z: Double = Quaternion.c.z) {
        self.w = w
        self.x = x
        self.y = y
        self.z = z
    }
    @usableFromInline static let c = sr_default_view().device_to_enu
}

public struct Vector3: Equatable, Sendable {
    public var x, y, z: Double
    public init(x: Double, y: Double, z: Double) {
        self.x = x
        self.y = y
        self.z = z
    }
}

/// A point on the screen in points or pixels; y goes down.
public struct ScreenPoint: Equatable, Sendable {
    public var x, y: Double
    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

public enum SolarModel: Int32, Sendable {
    case noaa = 0
    case astronomyEngine = 1
}

public enum DayKind: Int32, Sendable {
    case normal = 0
    case polarDay = 1
    case polarNight = 2
}

/// The rotation of the screen content: the meaning of Android Display.getRotation(). rotation90
/// is a device turned 90° counterclockwise (UIDeviceOrientation.landscapeLeft on iOS).
public enum ScreenRotation: Int32, Sendable {
    case rotation0 = 0
    case rotation90 = 1
    case rotation180 = 2
    case rotation270 = 3
}

public struct SunOptions: Sendable {
    public var model: SolarModel
    public var refraction: Bool
    public var atmosphere: Atmosphere
    public init(
        model: SolarModel = SolarModel(rawValue: SunOptions.c.model)!, refraction: Bool = SunOptions.c.refraction,
        atmosphere: Atmosphere = Atmosphere(SunOptions.c.atmosphere)
    ) {
        self.model = model
        self.refraction = refraction
        self.atmosphere = atmosphere
    }
    @usableFromInline static let c = sr_default_sun_options()
}

public struct EventOptions: Sendable {
    public var model: SolarModel
    public var altitudeDeg: Double
    public var searchStepS: Double
    public var toleranceS: Double
    public init(
        model: SolarModel = SolarModel(rawValue: EventOptions.c.model)!, altitudeDeg: Double = EventOptions.c.altitude_deg,
        searchStepS: Double = EventOptions.c.search_step_s, toleranceS: Double = EventOptions.c.tolerance_s
    ) {
        self.model = model
        self.altitudeDeg = altitudeDeg
        self.searchStepS = searchStepS
        self.toleranceS = toleranceS
    }
    @usableFromInline static let c = sr_default_event_options()
}

public struct PathOptions: Sendable {
    public var model: SolarModel
    public var stepS: Double
    public var refraction: Bool
    public var atmosphere: Atmosphere
    public init(
        model: SolarModel = SolarModel(rawValue: PathOptions.c.model)!, stepS: Double = PathOptions.c.step_s, refraction: Bool = PathOptions.c.refraction,
        atmosphere: Atmosphere = Atmosphere(PathOptions.c.atmosphere)
    ) {
        self.model = model
        self.stepS = stepS
        self.refraction = refraction
        self.atmosphere = atmosphere
    }
    @usableFromInline static let c = sr_default_path_options()
}

public struct SearchOptions: Sendable {
    public var model: SolarModel
    public var stepS: Double
    public var toleranceS: Double
    public var refraction: Bool
    public var atmosphere: Atmosphere
    public var maxSeparationDeg: Double
    public init(
        model: SolarModel = SolarModel(rawValue: SearchOptions.c.model)!, stepS: Double = SearchOptions.c.step_s,
        toleranceS: Double = SearchOptions.c.tolerance_s, refraction: Bool = SearchOptions.c.refraction,
        atmosphere: Atmosphere = Atmosphere(SearchOptions.c.atmosphere),
        maxSeparationDeg: Double = SearchOptions.c.max_separation_deg
    ) {
        self.model = model
        self.stepS = stepS
        self.toleranceS = toleranceS
        self.refraction = refraction
        self.atmosphere = atmosphere
        self.maxSeparationDeg = maxSeparationDeg
    }
    @usableFromInline static let c = sr_default_search_options()
}

public struct MoonOptions: Sendable {
    public var refraction: Bool
    public var atmosphere: Atmosphere
    public init(refraction: Bool = MoonOptions.c.refraction, atmosphere: Atmosphere = Atmosphere(MoonOptions.c.atmosphere)) {
        self.refraction = refraction
        self.atmosphere = atmosphere
    }
    @usableFromInline static let c = sr_default_moon_options()
}

public struct MagnetometerOptions: Sendable {
    public var sensorSigmaDeg: Double
    public init(sensorSigmaDeg: Double = MagnetometerOptions.c.sensor_sigma_deg) {
        self.sensorSigmaDeg = sensorSigmaDeg
    }
    @usableFromInline static let c = sr_default_magnetometer_options()
}

/// A pinhole camera with Brown–Conrady distortion (OpenCV convention), in screen pixels.
public struct Camera: Equatable, Sendable {
    public var width, height, fx, fy, cx, cy, k1, k2, k3, p1, p2: Double
    public init(
        width: Double = Camera.c.width, height: Double = Camera.c.height, fx: Double = Camera.c.fx,
        fy: Double = Camera.c.fy, cx: Double = Camera.c.cx, cy: Double = Camera.c.cy, k1: Double = Camera.c.k1,
        k2: Double = Camera.c.k2, k3: Double = Camera.c.k3, p1: Double = Camera.c.p1, p2: Double = Camera.c.p2
    ) {
        self.width = width
        self.height = height
        self.fx = fx
        self.fy = fy
        self.cx = cx
        self.cy = cy
        self.k1 = k1
        self.k2 = k2
        self.k3 = k3
        self.p1 = p1
        self.p2 = p2
    }
    @usableFromInline static let c = sr_default_camera()
}

public struct View: Sendable {
    public var deviceToEnu: Quaternion
    public var screenRotation: ScreenRotation
    public var headingOffsetDeg: Double
    public var camera: Camera
    public init(
        deviceToEnu: Quaternion = Quaternion(), screenRotation: ScreenRotation = ScreenRotation(rawValue: View.c.screen_rotation)!,
        headingOffsetDeg: Double = View.c.heading_offset_deg, camera: Camera = Camera()
    ) {
        self.deviceToEnu = deviceToEnu
        self.screenRotation = screenRotation
        self.headingOffsetDeg = headingOffsetDeg
        self.camera = camera
    }
    @usableFromInline static let c = sr_default_view()
}

public struct Projection: Sendable {
    public var valid: Bool
    public var point: ScreenPoint
}

public struct Crossing: Sendable {
    public var unixUtc: Double
    public var rising: Bool
}

public struct DayEvents: Sendable {
    public var kind: DayKind
    public var crossings: [Crossing]
    public var culminationUtc: Double
    public var culminationAltitudeDeg: Double
}

public struct PathPoint: Sendable {
    public var unixUtc: Double
    public var position: Horizontal
}

public struct TimeMatch: Sendable {
    public var unixUtc: Double
    public var separationDeg: Double
    public var sun: Horizontal
}

public struct AzimuthCrossing: Sendable {
    public var unixUtc: Double
    public var sun: Horizontal
}

public struct HeadingEstimate: Equatable, Sendable {
    public var offsetDeg: Double
    public var varianceDeg2: Double
    public init(
        offsetDeg: Double = HeadingEstimate.c.offset_deg, varianceDeg2: Double = HeadingEstimate.c.variance_deg2
    ) {
        self.offsetDeg = offsetDeg
        self.varianceDeg2 = varianceDeg2
    }
    @usableFromInline static let c = sr_default_heading_estimate()
    var c: sr_heading_estimate { sr_heading_estimate(offset_deg: offsetDeg, variance_deg2: varianceDeg2) }
    init(_ c: sr_heading_estimate) { self.init(offsetDeg: c.offset_deg, varianceDeg2: c.variance_deg2) }
}

public struct HeadingMeasurement: Equatable, Sendable {
    public var offsetDeg: Double
    public var varianceDeg2: Double
    public init(offsetDeg: Double, varianceDeg2: Double) {
        self.offsetDeg = offsetDeg
        self.varianceDeg2 = varianceDeg2
    }
}

/// An error of the core: an invalid argument, or another error. The message names the argument.
public struct SunradarError: Error, CustomStringConvertible, Sendable {
    public enum Kind: Sendable { case invalidArgument, other }
    public let kind: Kind
    public let message: String
    public var description: String { message }
}

// MARK: - Conversion to and from the C API

extension Atmosphere {
    @usableFromInline init(_ c: sr_atmosphere) { self.init(pressureHpa: c.pressure_hpa, temperatureC: c.temperature_c) }
    var c: sr_atmosphere { sr_atmosphere(pressure_hpa: pressureHpa, temperature_c: temperatureC) }
}

extension Observer {
    var c: sr_observer { sr_observer(latitude_deg: latitudeDeg, longitude_deg: longitudeDeg) }
}

extension Horizontal {
    init(_ c: sr_horizontal) { self.init(azimuthDeg: c.azimuth_deg, altitudeDeg: c.altitude_deg) }
    var c: sr_horizontal { sr_horizontal(azimuth_deg: azimuthDeg, altitude_deg: altitudeDeg) }
}

extension Quaternion {
    var c: sr_quaternion { sr_quaternion(w: w, x: x, y: y, z: z) }
}

extension ScreenPoint {
    var c: sr_screen_point { sr_screen_point(x: x, y: y) }
}

extension Camera {
    init(_ c: sr_camera) {
        self.init(
            width: c.width, height: c.height, fx: c.fx, fy: c.fy, cx: c.cx, cy: c.cy, k1: c.k1, k2: c.k2, k3: c.k3,
            p1: c.p1, p2: c.p2)
    }
    var c: sr_camera {
        sr_camera(width: width, height: height, fx: fx, fy: fy, cx: cx, cy: cy, k1: k1, k2: k2, k3: k3, p1: p1, p2: p2)
    }
}

extension View {
    var c: sr_view {
        sr_view(
            device_to_enu: deviceToEnu.c, screen_rotation: screenRotation.rawValue,
            heading_offset_deg: headingOffsetDeg, camera: camera.c)
    }
}

extension SunOptions {
    var c: sr_sun_options { sr_sun_options(model: model.rawValue, refraction: refraction, atmosphere: atmosphere.c) }
}

extension EventOptions {
    var c: sr_event_options {
        sr_event_options(
            model: model.rawValue, altitude_deg: altitudeDeg, search_step_s: searchStepS, tolerance_s: toleranceS)
    }
}

extension PathOptions {
    var c: sr_path_options {
        sr_path_options(model: model.rawValue, step_s: stepS, refraction: refraction, atmosphere: atmosphere.c)
    }
}

extension MoonOptions {
    var c: sr_moon_options { sr_moon_options(refraction: refraction, atmosphere: atmosphere.c) }
}

extension HeadingMeasurement {
    var c: sr_heading_measurement { sr_heading_measurement(offset_deg: offsetDeg, variance_deg2: varianceDeg2) }
    init(_ c: sr_heading_measurement) { self.init(offsetDeg: c.offset_deg, varianceDeg2: c.variance_deg2) }
}

extension SearchOptions {
    var c: sr_search_options {
        sr_search_options(
            model: model.rawValue, step_s: stepS, tolerance_s: toleranceS, refraction: refraction,
            atmosphere: atmosphere.c, max_separation_deg: maxSeparationDeg)
    }
}
