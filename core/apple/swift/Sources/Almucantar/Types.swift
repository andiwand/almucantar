import AlmucantarC

// The value types of the public C++ API (core/src/almucantar). The names are the C++ names in
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

/// The noise model of a HeadingFilter.
public struct HeadingFilterOptions: Equatable, Sendable {
    /// The growth of the variance of the offset, because the yaw of the frame drifts.
    public var driftDeg2PerS: Double
    /// The growth of the variance of each bias component (1 µT² in 10 minutes).
    public var biasDriftUt2PerS: Double
    /// The size of a local disturbance of the field, per component.
    public var disturbanceSigmaUt: Double
    /// The correlation time of a local disturbance.
    public var disturbanceTimeS: Double
    /// The noise of one magnetometer sample, per component.
    public var noiseSigmaUt: Double
    public init(
        driftDeg2PerS: Double = HeadingFilterOptions.c.drift_deg2_per_s,
        biasDriftUt2PerS: Double = HeadingFilterOptions.c.bias_drift_ut2_per_s,
        disturbanceSigmaUt: Double = HeadingFilterOptions.c.disturbance_sigma_ut,
        disturbanceTimeS: Double = HeadingFilterOptions.c.disturbance_time_s,
        noiseSigmaUt: Double = HeadingFilterOptions.c.noise_sigma_ut
    ) {
        self.driftDeg2PerS = driftDeg2PerS
        self.biasDriftUt2PerS = biasDriftUt2PerS
        self.disturbanceSigmaUt = disturbanceSigmaUt
        self.disturbanceTimeS = disturbanceTimeS
        self.noiseSigmaUt = noiseSigmaUt
    }
    @usableFromInline static let c = sr_default_heading_filter_options()
    var c: sr_heading_filter_options {
        sr_heading_filter_options(
            drift_deg2_per_s: driftDeg2PerS, bias_drift_ut2_per_s: biasDriftUt2PerS,
            disturbance_sigma_ut: disturbanceSigmaUt, disturbance_time_s: disturbanceTimeS,
            noise_sigma_ut: noiseSigmaUt)
    }
}

/// The number of values in the state of a HeadingFilter.
public let headingFilterSize = Int(SR_HEADING_FILTER_SIZE)

/// The heading filter with the magnetometer: the offset of the frame to true north, the hard-iron
/// bias in the device frame, and a local disturbance in East-North-Up, with their covariance (row
/// by row, in that order). Make it with headingFilterStart().
public struct HeadingFilter: Equatable, Sendable {
    public var offsetDeg: Double
    public var biasUt: Vector3
    public var disturbanceUt: Vector3
    public var covariance: [Double]

    /// The variance of the offset.
    public var varianceDeg2: Double { covariance[0] }
    /// The offset and its variance.
    public var estimate: HeadingEstimate { HeadingEstimate(offsetDeg: offsetDeg, varianceDeg2: varianceDeg2) }

    init(_ c: sr_heading_filter) {
        var copy = c
        offsetDeg = c.offset_deg
        biasUt = Vector3(x: c.bias_ut.x, y: c.bias_ut.y, z: c.bias_ut.z)
        disturbanceUt = Vector3(x: c.disturbance_ut.x, y: c.disturbance_ut.y, z: c.disturbance_ut.z)
        covariance = withUnsafeBytes(of: &copy.covariance) { Array($0.bindMemory(to: Double.self)) }
    }

    var c: sr_heading_filter {
        var out = sr_heading_filter()
        out.offset_deg = offsetDeg
        out.bias_ut = sr_vector3(x: biasUt.x, y: biasUt.y, z: biasUt.z)
        out.disturbance_ut = sr_vector3(x: disturbanceUt.x, y: disturbanceUt.y, z: disturbanceUt.z)
        withUnsafeMutableBytes(of: &out.covariance) { raw in
            let values = raw.bindMemory(to: Double.self)
            for i in 0..<min(values.count, covariance.count) { values[i] = covariance[i] }
        }
        return out
    }
}

/// The heights of an ElevationGrid, row by row from the north, in meters. NaN is a sample without
/// data. A tile has more than a million values, so this class keeps the buffer of the C API and
/// does not copy it. The buffer does not change.
public final class ElevationHeights: RandomAccessCollection, @unchecked Sendable {
    let pointer: UnsafeMutablePointer<Float>?
    public let count: Int
    private let ownedByC: Bool

    /// Takes a buffer that the C API allocated. The class frees it with sr_free().
    init(takingCBuffer pointer: UnsafeMutablePointer<Float>?, count: Int) {
        self.pointer = pointer
        self.count = pointer == nil ? 0 : count
        ownedByC = true
    }

    /// Copies the values.
    public init(_ values: [Float]) {
        let buffer = UnsafeMutablePointer<Float>.allocate(capacity: Swift.max(values.count, 1))
        values.withUnsafeBufferPointer { source in
            if let base = source.baseAddress { buffer.initialize(from: base, count: values.count) }
        }
        pointer = buffer
        count = values.count
        ownedByC = false
    }

    deinit {
        if ownedByC { sr_free(pointer) } else { pointer?.deallocate() }
    }

    public var startIndex: Int { 0 }
    public var endIndex: Int { count }
    public subscript(index: Int) -> Float {
        precondition(index >= 0 && index < count, "index out of range")
        return pointer![index]
    }
}

/// A digital elevation model as a plain grid of heights. The sample in row r and column c is at
/// the latitude northLatitudeDeg - r * latitudeStepDeg and the longitude
/// westLongitudeDeg + c * longitudeStepDeg.
public struct ElevationGrid: Sendable {
    public var northLatitudeDeg: Double
    public var westLongitudeDeg: Double
    public var latitudeStepDeg: Double
    public var longitudeStepDeg: Double
    public var rows: Int
    public var columns: Int
    /// rows * columns heights. horizonProfile() checks the count.
    public var heightsM: ElevationHeights

    public init(
        northLatitudeDeg: Double, westLongitudeDeg: Double, latitudeStepDeg: Double, longitudeStepDeg: Double,
        rows: Int, columns: Int, heightsM: ElevationHeights
    ) {
        self.northLatitudeDeg = northLatitudeDeg
        self.westLongitudeDeg = westLongitudeDeg
        self.latitudeStepDeg = latitudeStepDeg
        self.longitudeStepDeg = longitudeStepDeg
        self.rows = rows
        self.columns = columns
        self.heightsM = heightsM
    }

    public init(
        northLatitudeDeg: Double, westLongitudeDeg: Double, latitudeStepDeg: Double, longitudeStepDeg: Double,
        rows: Int, columns: Int, heightsM: [Float]
    ) {
        self.init(
            northLatitudeDeg: northLatitudeDeg, westLongitudeDeg: westLongitudeDeg, latitudeStepDeg: latitudeStepDeg,
            longitudeStepDeg: longitudeStepDeg, rows: rows, columns: columns, heightsM: ElevationHeights(heightsM))
    }
}

/// A tile of 1° by 1° of a global DEM, named by its south-west corner. Copernicus names the tile
/// with latitudeDeg 47 and longitudeDeg 11 N47_00_E011_00.
public struct ElevationTile: Equatable, Hashable, Sendable {
    public var latitudeDeg: Int
    public var longitudeDeg: Int
    /// The distance from the observer to the nearest point of the tile, 0 for the tile of the observer.
    public var distanceM: Double
    public init(latitudeDeg: Int, longitudeDeg: Int, distanceM: Double = 0) {
        self.latitudeDeg = latitudeDeg
        self.longitudeDeg = longitudeDeg
        self.distanceM = distanceM
    }
}

public struct HorizonOptions: Sendable {
    /// The distance between two azimuths of the profile. 360 must be a multiple of it.
    public var azimuthStepDeg: Double
    /// The terrain farther away is not used, at most 500 km.
    public var maxDistanceM: Double
    /// The height of the eye above the ground.
    public var eyeHeightM: Double
    /// If true, the elevation of the ground at the observer comes from the tiles.
    public var useGridElevation: Bool
    /// Used only if useGridElevation is false.
    public var observerElevationM: Double
    /// The coefficient of the terrestrial refraction; 0 gives no refraction.
    public var refractionCoefficient: Double
    /// The terrain nearer than this is not used: 0 for a bare-earth model, about 2 km for a surface
    /// model with buildings (Copernicus), so a city does not give a false wall.
    public var minDistanceM: Double
    public init(
        azimuthStepDeg: Double = HorizonOptions.c.azimuth_step_deg, maxDistanceM: Double = HorizonOptions.c.max_distance_m,
        eyeHeightM: Double = HorizonOptions.c.eye_height_m, useGridElevation: Bool = HorizonOptions.c.use_grid_elevation,
        observerElevationM: Double = HorizonOptions.c.observer_elevation_m,
        refractionCoefficient: Double = HorizonOptions.c.refraction_coefficient,
        minDistanceM: Double = HorizonOptions.c.min_distance_m
    ) {
        self.azimuthStepDeg = azimuthStepDeg
        self.maxDistanceM = maxDistanceM
        self.eyeHeightM = eyeHeightM
        self.useGridElevation = useGridElevation
        self.observerElevationM = observerElevationM
        self.refractionCoefficient = refractionCoefficient
        self.minDistanceM = minDistanceM
    }
    @usableFromInline static let c = sr_default_horizon_options()
}

/// The altitude of the terrain horizon for each azimuth: the entry i is for the azimuth
/// i * azimuthStepDeg. NaN is a direction without data.
public struct HorizonProfile: Sendable {
    public var azimuthStepDeg: Double
    public var altitudeDeg: [Double]
    public var distanceM: [Double]
    /// The elevation of the ground at the observer.
    public var observerElevationM: Double
    public init(azimuthStepDeg: Double, altitudeDeg: [Double], distanceM: [Double], observerElevationM: Double = 0) {
        self.azimuthStepDeg = azimuthStepDeg
        self.altitudeDeg = altitudeDeg
        self.distanceM = distanceM
        self.observerElevationM = observerElevationM
    }
}

/// A body that can rise and set behind the terrain.
public enum Body: Int32, Sendable {
    case sun = 0
    case moon = 1
}

public struct TerrainEventOptions: Sendable {
    /// The sun or the moon.
    public var body: Body
    /// Used only for the sun.
    public var model: SolarModel
    public var refraction: Bool
    public var atmosphere: Atmosphere
    /// The body is visible if its center plus this angle is above the terrain (the semi-diameter by default).
    public var limbDeg: Double
    public var searchStepS: Double
    public var toleranceS: Double
    public init(
        body: Body = Body(rawValue: TerrainEventOptions.c.body)!,
        model: SolarModel = SolarModel(rawValue: TerrainEventOptions.c.model)!,
        refraction: Bool = TerrainEventOptions.c.refraction,
        atmosphere: Atmosphere = Atmosphere(TerrainEventOptions.c.atmosphere),
        limbDeg: Double = TerrainEventOptions.c.limb_deg, searchStepS: Double = TerrainEventOptions.c.search_step_s,
        toleranceS: Double = TerrainEventOptions.c.tolerance_s
    ) {
        self.body = body
        self.model = model
        self.refraction = refraction
        self.atmosphere = atmosphere
        self.limbDeg = limbDeg
        self.searchStepS = searchStepS
        self.toleranceS = toleranceS
    }
    @usableFromInline static let c = sr_default_terrain_event_options()
}

/// The times when the body appears (rising) or goes away behind the terrain.
public struct TerrainEvents: Sendable {
    /// normal: the body crosses the terrain at least once. polarDay: the body stays above the terrain.
    /// polarNight: the body stays behind the terrain or below the horizon.
    public var kind: DayKind
    public var crossings: [Crossing]
}

/// The planets of planetPosition() and planetMagnitude(), in the order of the C++ enum.
public enum Planet: Int32, CaseIterable, Sendable {
    case mercury = 0
    case venus = 1
    case mars = 2
    case jupiter = 3
    case saturn = 4
    case uranus = 5
    case neptune = 6
}

public struct SkyOptions: Sendable {
    /// Add the refraction, to get the position that the observer sees.
    public var refraction: Bool
    /// Used only if refraction is true.
    public var atmosphere: Atmosphere
    public init(refraction: Bool = SkyOptions.c.refraction, atmosphere: Atmosphere = Atmosphere(SkyOptions.c.atmosphere)) {
        self.refraction = refraction
        self.atmosphere = atmosphere
    }
    @usableFromInline static let c = sr_default_sky_options()
}

/// The galactic longitude and latitude of the center of the Milky Way (Sagittarius A*).
public let galacticCenterLongitudeDeg = sr_galactic_center_longitude_deg()
public let galacticCenterLatitudeDeg = sr_galactic_center_latitude_deg()

/// A catalogue of stars as columns of the same length: J2000 positions at the epoch 2000.0, and the
/// proper motions in arcseconds per year (pmRaArcsecPerYear is d(ra)/dt * cos(dec)). Empty proper
/// motion columns mean 0 for all stars.
public struct StarCatalog: Sendable {
    public var raDeg: [Double]
    public var decDeg: [Double]
    public var pmRaArcsecPerYear: [Double]
    public var pmDecArcsecPerYear: [Double]
    public var count: Int { raDeg.count }
    public init(raDeg: [Double], decDeg: [Double], pmRaArcsecPerYear: [Double] = [], pmDecArcsecPerYear: [Double] = []) {
        self.raDeg = raDeg
        self.decDeg = decDeg
        self.pmRaArcsecPerYear = pmRaArcsecPerYear
        self.pmDecArcsecPerYear = pmDecArcsecPerYear
    }
}

/// An error of the core: an invalid argument, or another error. The message names the argument.
public struct AlmucantarError: Error, CustomStringConvertible, Sendable {
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

extension SkyOptions {
    var c: sr_sky_options { sr_sky_options(refraction: refraction, atmosphere: atmosphere.c) }
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

extension HorizonOptions {
    var c: sr_horizon_options {
        sr_horizon_options(
            azimuth_step_deg: azimuthStepDeg, max_distance_m: maxDistanceM, eye_height_m: eyeHeightM,
            use_grid_elevation: useGridElevation, observer_elevation_m: observerElevationM,
            refraction_coefficient: refractionCoefficient, min_distance_m: minDistanceM)
    }
}

extension TerrainEventOptions {
    var c: sr_terrain_event_options {
        sr_terrain_event_options(
            model: model.rawValue, refraction: refraction, atmosphere: atmosphere.c, limb_deg: limbDeg,
            search_step_s: searchStepS, tolerance_s: toleranceS, body: body.rawValue)
    }
}
