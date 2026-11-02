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
    driftDeg2PerS: Double = DEFAULT_HEADING_DRIFT_DEG2_PER_S,
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

/** Finds the times between the two times when the sun has the azimuth [azimuthDeg]. */
fun findAzimuthTimes(
    observer: Observer,
    azimuthDeg: Double,
    startUtc: Double,
    endUtc: Double,
    options: SearchOptions = SearchOptions(),
): List<AzimuthCrossing> {
    val r =
        SunradarNative.findAzimuthTimes(
            observer.pack(),
            azimuthDeg,
            startUtc,
            endUtc,
            options.pack(),
        )
    return List(r.size / 3) { AzimuthCrossing(r[3 * it], r.toHorizontal(3 * it + 1)) }
}

/** Returns the apparent topocentric position of the center of the moon. */
fun moonPosition(
    unixUtc: Double,
    observer: Observer,
    options: MoonOptions = MoonOptions(),
): Horizontal = SunradarNative.moonPosition(unixUtc, observer.pack(), options.pack()).toHorizontal()

/** Returns the illuminated fraction of the disc of the moon, 0 (new) to 1 (full). */
fun moonIlluminatedFraction(unixUtc: Double): Double =
    SunradarNative.moonIlluminatedFraction(unixUtc)

/**
 * A heading filter with an unknown offset. [biasUt] is the first guess of the hard-iron bias (the
 * bias of the OS, or 0 for a calibrated field), and [biasSigmaUt] is its error.
 */
fun headingFilterStart(
    biasUt: Vector3,
    biasSigmaUt: Double,
    options: HeadingFilterOptions = HeadingFilterOptions(),
): HeadingFilter =
    SunradarNative.headingFilterStart(biasUt.pack(), biasSigmaUt, options.pack()).toHeadingFilter()

/** Moves the filter forward by [dtS]: the variances grow, and the disturbance decays. */
fun headingFilterPredict(
    filter: HeadingFilter,
    dtS: Double,
    options: HeadingFilterOptions = HeadingFilterOptions(),
): HeadingFilter =
    SunradarNative.headingFilterPredict(filter.pack(), dtS, options.pack()).toHeadingFilter()

/** Combines the filter with a measurement of the offset, for example a sun tap. */
fun headingFilterUpdate(filter: HeadingFilter, measurement: HeadingMeasurement): HeadingFilter =
    SunradarNative.headingFilterUpdate(filter.pack(), measurement.pack()).toHeadingFilter()

/**
 * Combines the filter with one magnetometer sample ([fieldUt] in the device frame, in µT, with or
 * without the hard-iron bias). Give the filter about one sample per second.
 */
fun headingFilterUpdateMagnetometer(
    filter: HeadingFilter,
    deviceToFrame: Quaternion,
    fieldUt: Vector3,
    observer: Observer,
    unixUtc: Double,
    options: HeadingFilterOptions = HeadingFilterOptions(),
): HeadingFilter =
    SunradarNative.headingFilterUpdateMagnetometer(
            filter.pack(),
            deviceToFrame.pack(),
            fieldUt.pack(),
            observer.pack(),
            unixUtc,
            options.pack(),
        )
        .toHeadingFilter()

/**
 * Reads one image of a GeoTIFF file with heights in latitude and longitude, for example a tile of
 * Copernicus GLO-90. [level] 0 is the full image, and 1, 2, ... are the overviews (each has half
 * the size of the one before). Throws IllegalArgumentException if the file is not valid or not
 * supported.
 */
fun readElevationGeotiff(data: ByteArray, level: Int = 0): ElevationGrid {
    val header = DoubleArray(6)
    val heights = SunradarNative.readElevationGeotiff(data, level, header)
    return ElevationGrid(
        header[0],
        header[1],
        header[2],
        header[3],
        header[4].toInt(),
        header[5].toInt(),
        heights,
    )
}

/**
 * Returns the number of bytes from the start of a GeoTIFF file that [readElevationGeotiff] needs
 * for [level], from the first part of the file in [header] (for an HTTP Range request). For a
 * Copernicus GLO-90 tile, 16 KB of header are enough, and level 2 needs about 300 KB. Returns 0 if
 * [header] is too short to tell. Throws IllegalArgumentException if the file is not valid.
 */
fun elevationGeotiffPrefixSize(header: ByteArray, level: Int): Long =
    SunradarNative.elevationGeotiffPrefixSize(header, level)

/**
 * Returns the tiles of 1° by 1° that a horizon profile with [maxDistanceM] needs: all tiles that
 * have a point nearer than [maxDistanceM] to the observer, the nearest first.
 */
fun elevationTiles(observer: Observer, maxDistanceM: Double): List<ElevationTile> {
    val r = SunradarNative.elevationTiles(observer.pack(), maxDistanceM)
    return List(r.size / 3) {
        ElevationTile(r[3 * it].toInt(), r[3 * it + 1].toInt(), r[3 * it + 2])
    }
}

/**
 * Calculates the terrain horizon of [observer] from the grids in [tiles]. A point outside all tiles
 * has no data (for example the sea). Throws IllegalArgumentException if an argument is not valid,
 * or if [HorizonOptions.useGridElevation] is true and the tiles have no data at the observer.
 */
fun horizonProfile(
    observer: Observer,
    tiles: List<ElevationGrid>,
    options: HorizonOptions = HorizonOptions(),
): HorizonProfile {
    val headers = DoubleArray(6 * tiles.size)
    tiles.forEachIndexed { i, tile -> tile.packHeader().copyInto(headers, 6 * i) }
    return SunradarNative.horizonProfile(
            observer.pack(),
            headers,
            Array(tiles.size) { tiles[it].heightsM },
            options.pack(),
        )
        .toHorizonProfile()
}

/**
 * Returns the altitude of the terrain horizon at [azimuthDeg], with a linear interpolation between
 * the azimuths of [profile]. Returns NaN if a neighbor has no data.
 */
fun terrainAltitudeDeg(profile: HorizonProfile, azimuthDeg: Double): Double =
    SunradarNative.terrainAltitudeDeg(profile.packed, azimuthDeg)

/**
 * Finds the times between the two times when the body of [options] (the sun or the moon) crosses
 * the terrain horizon [profile]: the visible rise and set, and the times when a peak hides the
 * body. An azimuth without data uses the altitude 0.
 */
fun terrainEvents(
    observer: Observer,
    profile: HorizonProfile,
    startUtc: Double,
    endUtc: Double,
    options: TerrainEventOptions = TerrainEventOptions(),
): TerrainEvents {
    val r =
        SunradarNative.terrainEvents(
            observer.pack(),
            profile.packed,
            startUtc,
            endUtc,
            options.pack(),
        )
    val count = r[1].toInt()
    return TerrainEvents(
        kind = DayKind.entries[r[0].toInt()],
        crossings = List(count) { Crossing(r[2 + 2 * it], r[3 + 2 * it] != 0.0) },
    )
}
