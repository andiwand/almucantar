@file:JvmName("Sunradar")

package at.stefl.sunradar.core

// The functions of the public C++ API (core/src/sunradar/*.hpp). All times are Unix time in
// seconds (UTC). An invalid argument throws IllegalArgumentException.

/** Returns the version of the core library. */
fun version(): String = SunradarNative.version()

/** Returns the position of the center of the sun at [unixUtc] for [observer]. */
fun sunPosition(
    unixUtc: Double,
    observer: Observer,
    options: SunOptions = SunOptions(),
): Horizontal = SunradarNative.sunPosition(unixUtc, observer.pack(), options.pack()).toHorizontal()

/** Finds the crossings of the threshold altitude and the culmination between the two times. */
fun sunEvents(
    observer: Observer,
    startUtc: Double,
    endUtc: Double,
    options: EventOptions = EventOptions(),
): DayEvents {
    val r = SunradarNative.sunEvents(observer.pack(), startUtc, endUtc, options.pack())
    val count = r[3].toInt()
    return DayEvents(
        kind = DayKind.entries[r[0].toInt()],
        crossings = List(count) { Crossing(r[4 + 2 * it], r[5 + 2 * it] != 0.0) },
        culminationUtc = r[1],
        culminationAltitudeDeg = r[2],
    )
}

/** Returns the path of the sun at the times `startUtc + k * stepS` up to [endUtc]. */
fun sunPath(
    observer: Observer,
    startUtc: Double,
    endUtc: Double,
    options: PathOptions = PathOptions(),
): List<PathPoint> {
    val r = SunradarNative.sunPath(observer.pack(), startUtc, endUtc, options.pack())
    return List(r.size / 3) { PathPoint(r[3 * it], r.toHorizontal(3 * it + 1)) }
}

/** Returns the magnetic declination (WMM2025), positive to the east. */
fun magneticDeclinationDeg(observer: Observer, unixUtc: Double): Double =
    SunradarNative.magneticDeclinationDeg(observer.pack(), unixUtc)

/** Returns a camera without distortion with the horizontal field of view [horizontalFovDeg]. */
fun cameraFromFov(width: Double, height: Double, horizontalFovDeg: Double): Camera =
    SunradarNative.cameraFromFov(width, height, horizontalFovDeg).toCamera()

/** Projects a direction to the screen. */
fun project(view: View, direction: Horizontal): Projection {
    val r = SunradarNative.project(view.pack(), direction.pack())
    return Projection(r[0] != 0.0, ScreenPoint(r[1], r[2]))
}

/** Returns the direction that the screen point shows. */
fun unproject(view: View, point: ScreenPoint): Horizontal =
    SunradarNative.unproject(view.pack(), point.pack()).toHorizontal()

/** Projects a line of directions, clipped to the valid area. */
fun projectLine(view: View, line: DirectionLine): Polylines =
    Polylines(SunradarNative.projectLine(view.pack(), line.packed))

/** Projects a line of directions, clipped to the valid area. */
fun projectLine(view: View, line: List<Horizontal>): List<List<ScreenPoint>> =
    projectLine(view, DirectionLine.of(line)).toList()

/** Projects a path of the sun (see [sunPath]). */
fun projectPath(view: View, path: List<PathPoint>): List<List<ScreenPoint>> =
    projectLine(view, DirectionLine.fromPath(path)).toList()

/** Projects the astronomical horizon (altitude 0) as a closed line in steps of 1 degree. */
fun horizonLine(view: View): Polylines = Polylines(SunradarNative.horizonLine(view.pack()))

/** Finds the time between the two times when the sun is nearest to [direction]. */
fun findTime(
    observer: Observer,
    direction: Horizontal,
    startUtc: Double,
    endUtc: Double,
    options: SearchOptions = SearchOptions(),
): TimeMatch =
    SunradarNative.findTime(observer.pack(), direction.pack(), startUtc, endUtc, options.pack())
        .toTimeMatch()

/** Finds the dates when the sun goes through [direction], one 24-hour window at a time. */
fun findDates(
    observer: Observer,
    direction: Horizontal,
    startUtc: Double,
    endUtc: Double,
    options: SearchOptions = SearchOptions(),
): List<TimeMatch> {
    val r =
        SunradarNative.findDates(
            observer.pack(),
            direction.pack(),
            startUtc,
            endUtc,
            options.pack(),
        )
    return List(r.size / 4) { r.toTimeMatch(4 * it) }
}

/** Increases the variance for the time [dtS], because the yaw of the frame drifts. */
fun headingPredict(
    estimate: HeadingEstimate,
    dtS: Double,
    driftDeg2PerS: Double = 1.0 / 60.0,
): HeadingEstimate =
    SunradarNative.headingPredict(estimate.pack(), dtS, driftDeg2PerS).toHeadingEstimate()

/** Combines the estimate with a measurement (a Kalman update on the circle). */
fun headingUpdate(estimate: HeadingEstimate, measurement: HeadingMeasurement): HeadingEstimate =
    SunradarNative.headingUpdate(estimate.pack(), measurement.pack()).toHeadingEstimate()

/**
 * The heading offset from a magnetometer ([fieldUt] in the device frame, in µT), with a variance
 * from the disturbance of the field. Give the filter at most about one measurement per second.
 */
fun headingFromMagnetometer(
    deviceToFrame: Quaternion,
    fieldUt: Vector3,
    observer: Observer,
    unixUtc: Double,
    options: MagnetometerOptions = MagnetometerOptions(),
): HeadingMeasurement =
    SunradarNative.headingFromMagnetometer(
            deviceToFrame.pack(),
            fieldUt.pack(),
            observer.pack(),
            unixUtc,
            options.pack(),
        )
        .toHeadingMeasurement()

/** The heading offset from a screen point that shows a known true azimuth (for example the sun). */
fun headingFromDirection(
    view: View,
    point: ScreenPoint,
    trueAzimuthDeg: Double,
    sigmaDeg: Double,
): HeadingMeasurement =
    SunradarNative.headingFromDirection(view.pack(), point.pack(), trueAzimuthDeg, sigmaDeg)
        .toHeadingMeasurement()
