import SunradarC

// The functions of the public C++ API. All times are Unix time in seconds (UTC), all angles in
// degrees. An invalid argument throws SunradarError with kind invalidArgument.

/// Runs a C function and converts its status to a SunradarError.
private func check(_ status: sr_status) throws {
    switch status {
    case SR_OK: return
    case SR_INVALID_ARGUMENT: throw SunradarError(kind: .invalidArgument, message: String(cString: sr_last_error()))
    default: throw SunradarError(kind: .other, message: String(cString: sr_last_error()))
    }
}

/// Copies a C array that the C API allocated, and frees it.
private func take<C, S>(_ pointer: UnsafeMutablePointer<C>?, _ count: Int, _ convert: (C) -> S) -> [S] {
    defer { sr_free(pointer) }
    guard let pointer, count > 0 else { return [] }
    return (0..<count).map { convert(pointer[$0]) }
}

private func polylines(_ lines: inout sr_polylines) -> [[ScreenPoint]] {
    defer { sr_free_polylines(&lines) }
    var result: [[ScreenPoint]] = []
    var offset = 0
    for i in 0..<lines.line_count {
        let count = lines.counts[i]
        result.append((0..<count).map { ScreenPoint(x: lines.points[offset + $0].x, y: lines.points[offset + $0].y) })
        offset += count
    }
    return result
}

/// The version of the core library.
public func version() -> String { String(cString: sr_version()) }

/// The airless altitude of the center of the sun at the standard sunrise and sunset.
public let sunriseAltitudeDeg = sr_sunrise_altitude_deg()

public func sunPosition(_ unixUtc: Double, observer: Observer, options: SunOptions = SunOptions()) throws -> Horizontal {
    var out = sr_horizontal()
    try check(sr_sun_position(unixUtc, observer.c, options.c, &out))
    return Horizontal(out)
}

public func sunEvents(
    observer: Observer, startUtc: Double, endUtc: Double, options: EventOptions = EventOptions()
) throws -> DayEvents {
    var out = sr_day_events()
    try check(sr_sun_events(observer.c, startUtc, endUtc, options.c, &out))
    guard let kind = DayKind(rawValue: out.kind) else {
        sr_free(out.crossings)
        throw SunradarError(kind: .other, message: "unknown day kind \(out.kind)")
    }
    return DayEvents(
        kind: kind,
        crossings: take(out.crossings, out.crossing_count) { Crossing(unixUtc: $0.unix_utc, rising: $0.rising) },
        culminationUtc: out.culmination_utc, culminationAltitudeDeg: out.culmination_altitude_deg)
}

public func sunPath(
    observer: Observer, startUtc: Double, endUtc: Double, options: PathOptions = PathOptions()
) throws -> [PathPoint] {
    var points: UnsafeMutablePointer<sr_path_point>?
    var count = 0
    try check(sr_sun_path(observer.c, startUtc, endUtc, options.c, &points, &count))
    return take(points, count) { PathPoint(unixUtc: $0.unix_utc, position: Horizontal($0.position)) }
}

public func moonPosition(_ unixUtc: Double, observer: Observer, options: MoonOptions = MoonOptions()) throws -> Horizontal {
    var out = sr_horizontal()
    try check(sr_moon_position(unixUtc, observer.c, options.c, &out))
    return Horizontal(out)
}

public func moonIlluminatedFraction(_ unixUtc: Double) throws -> Double {
    var out = 0.0
    try check(sr_moon_illuminated_fraction(unixUtc, &out))
    return out
}

public func magneticDeclinationDeg(observer: Observer, unixUtc: Double) throws -> Double {
    var out = 0.0
    try check(sr_magnetic_declination_deg(observer.c, unixUtc, &out))
    return out
}

public func cameraFromFov(width: Double, height: Double, horizontalFovDeg: Double) throws -> Camera {
    var out = sr_camera()
    try check(sr_camera_from_fov(width, height, horizontalFovDeg, &out))
    return Camera(out)
}

public func project(_ view: View, _ direction: Horizontal) throws -> Projection {
    var out = sr_projection()
    try check(sr_project(view.c, direction.c, &out))
    return Projection(valid: out.valid, point: ScreenPoint(x: out.point.x, y: out.point.y))
}

public func unproject(_ view: View, _ point: ScreenPoint) throws -> Horizontal {
    var out = sr_horizontal()
    try check(sr_unproject(view.c, point.c, &out))
    return Horizontal(out)
}

public func projectLine(_ view: View, _ line: [Horizontal]) throws -> [[ScreenPoint]] {
    var out = sr_polylines()
    let c = line.map(\.c)
    try c.withUnsafeBufferPointer { try check(sr_project_line(view.c, $0.baseAddress, $0.count, &out)) }
    return polylines(&out)
}

public func projectPath(_ view: View, _ path: [PathPoint]) throws -> [[ScreenPoint]] {
    try projectLine(view, path.map(\.position))
}

public func horizonLine(_ view: View) throws -> [[ScreenPoint]] {
    var out = sr_polylines()
    try check(sr_horizon_line(view.c, &out))
    return polylines(&out)
}

public func findTime(
    observer: Observer, direction: Horizontal, startUtc: Double, endUtc: Double, options: SearchOptions = SearchOptions()
) throws -> TimeMatch {
    var out = sr_time_match()
    try check(sr_find_time(observer.c, direction.c, startUtc, endUtc, options.c, &out))
    return TimeMatch(unixUtc: out.unix_utc, separationDeg: out.separation_deg, sun: Horizontal(out.sun))
}

public func findDates(
    observer: Observer, direction: Horizontal, startUtc: Double, endUtc: Double, options: SearchOptions = SearchOptions()
) throws -> [TimeMatch] {
    var matches: UnsafeMutablePointer<sr_time_match>?
    var count = 0
    try check(sr_find_dates(observer.c, direction.c, startUtc, endUtc, options.c, &matches, &count))
    return take(matches, count) { TimeMatch(unixUtc: $0.unix_utc, separationDeg: $0.separation_deg, sun: Horizontal($0.sun)) }
}

public func findAzimuthTimes(
    observer: Observer, azimuthDeg: Double, startUtc: Double, endUtc: Double, options: SearchOptions = SearchOptions()
) throws -> [AzimuthCrossing] {
    var crossings: UnsafeMutablePointer<sr_azimuth_crossing>?
    var count = 0
    try check(sr_find_azimuth_times(observer.c, azimuthDeg, startUtc, endUtc, options.c, &crossings, &count))
    return take(crossings, count) { AzimuthCrossing(unixUtc: $0.unix_utc, sun: Horizontal($0.sun)) }
}

/// The variance of a heading that is not known at all, in square degrees.
public let unknownHeadingVarianceDeg2 = sr_unknown_heading_variance_deg2()

/// The default growth of the variance in headingPredict(), in square degrees per second.
public let defaultHeadingDriftDeg2PerS = sr_default_heading_drift_deg2_per_s()

public func headingPredict(
    _ estimate: HeadingEstimate, dtS: Double, driftDeg2PerS: Double = defaultHeadingDriftDeg2PerS
) throws -> HeadingEstimate {
    var out = sr_heading_estimate()
    try check(sr_heading_predict(estimate.c, dtS, driftDeg2PerS, &out))
    return HeadingEstimate(out)
}

public func headingUpdate(_ estimate: HeadingEstimate, _ measurement: HeadingMeasurement) throws -> HeadingEstimate {
    var out = sr_heading_estimate()
    try check(sr_heading_update(estimate.c, measurement.c, &out))
    return HeadingEstimate(out)
}

public func headingFromMagnetometer(
    deviceToFrame: Quaternion, fieldUt: Vector3, observer: Observer, unixUtc: Double,
    options: MagnetometerOptions = MagnetometerOptions()
) throws -> HeadingMeasurement {
    var out = sr_heading_measurement()
    try check(
        sr_heading_from_magnetometer(
            deviceToFrame.c, sr_vector3(x: fieldUt.x, y: fieldUt.y, z: fieldUt.z), observer.c, unixUtc,
            sr_magnetometer_options(sensor_sigma_deg: options.sensorSigmaDeg), &out))
    return HeadingMeasurement(out)
}

public func headingFromDirection(_ view: View, point: ScreenPoint, trueAzimuthDeg: Double, sigmaDeg: Double) throws
    -> HeadingMeasurement
{
    var out = sr_heading_measurement()
    try check(sr_heading_from_direction(view.c, point.c, trueAzimuthDeg, sigmaDeg, &out))
    return HeadingMeasurement(out)
}
