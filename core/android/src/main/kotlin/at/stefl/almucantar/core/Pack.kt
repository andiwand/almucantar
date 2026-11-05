package at.stefl.almucantar.core

// Conversion between the Kotlin types and the flat double arrays of the JNI binding. The
// layouts are documented in core/android/jni/almucantar_jni.cpp and must stay the same.

internal fun Boolean.pack(): Double = if (this) 1.0 else 0.0

internal fun Observer.pack() = doubleArrayOf(latitudeDeg, longitudeDeg)

internal fun Horizontal.pack() = doubleArrayOf(azimuthDeg, altitudeDeg)

internal fun ScreenPoint.pack() = doubleArrayOf(x, y)

internal fun SunOptions.pack() =
    doubleArrayOf(
        model.ordinal.toDouble(),
        refraction.pack(),
        atmosphere.pressureHpa,
        atmosphere.temperatureC,
    )

internal fun EventOptions.pack() =
    doubleArrayOf(model.ordinal.toDouble(), altitudeDeg, searchStepS, toleranceS)

internal fun PathOptions.pack() =
    doubleArrayOf(
        model.ordinal.toDouble(),
        stepS,
        refraction.pack(),
        atmosphere.pressureHpa,
        atmosphere.temperatureC,
    )

internal fun SearchOptions.pack() =
    doubleArrayOf(
        model.ordinal.toDouble(),
        stepS,
        toleranceS,
        refraction.pack(),
        atmosphere.pressureHpa,
        atmosphere.temperatureC,
        maxSeparationDeg,
    )

internal fun Camera.pack() = doubleArrayOf(width, height, fx, fy, cx, cy, k1, k2, k3, p1, p2)

internal fun View.pack() =
    doubleArrayOf(
        deviceToEnu.w,
        deviceToEnu.x,
        deviceToEnu.y,
        deviceToEnu.z,
        screenRotation.ordinal.toDouble(),
        headingOffsetDeg,
    ) + camera.pack()

internal fun DoubleArray.toCamera() =
    Camera(
        this[0],
        this[1],
        this[2],
        this[3],
        this[4],
        this[5],
        this[6],
        this[7],
        this[8],
        this[9],
        this[10],
    )

internal fun DoubleArray.toHorizontal(offset: Int = 0) = Horizontal(this[offset], this[offset + 1])

internal fun DoubleArray.toTimeMatch(offset: Int = 0) =
    TimeMatch(this[offset], this[offset + 1], toHorizontal(offset + 2))

internal fun Quaternion.pack() = doubleArrayOf(w, x, y, z)

internal fun Vector3.pack() = doubleArrayOf(x, y, z)

internal fun HeadingEstimate.pack() = doubleArrayOf(offsetDeg, varianceDeg2)

internal fun HeadingMeasurement.pack() = doubleArrayOf(offsetDeg, varianceDeg2)

internal fun MagnetometerOptions.pack() = doubleArrayOf(sensorSigmaDeg)

internal fun DoubleArray.toHeadingEstimate() = HeadingEstimate(this[0], this[1])

internal fun DoubleArray.toHeadingMeasurement() = HeadingMeasurement(this[0], this[1])

internal fun HeadingFilter.pack() =
    doubleArrayOf(
        offsetDeg,
        biasUt.x,
        biasUt.y,
        biasUt.z,
        disturbanceUt.x,
        disturbanceUt.y,
        disturbanceUt.z,
    ) + covariance.toDoubleArray()

internal fun DoubleArray.toHeadingFilter() =
    HeadingFilter(
        this[0],
        Vector3(this[1], this[2], this[3]),
        Vector3(this[4], this[5], this[6]),
        copyOfRange(7, size).toList(),
    )

internal fun HeadingFilterOptions.pack() =
    doubleArrayOf(
        driftDeg2PerS,
        biasDriftUt2PerS,
        disturbanceSigmaUt,
        disturbanceTimeS,
        noiseSigmaUt,
    )

internal fun MoonOptions.pack() =
    doubleArrayOf(refraction.pack(), atmosphere.pressureHpa, atmosphere.temperatureC)

internal fun SkyOptions.pack() =
    doubleArrayOf(refraction.pack(), atmosphere.pressureHpa, atmosphere.temperatureC)

internal fun ElevationGrid.packHeader() =
    doubleArrayOf(
        northLatitudeDeg,
        westLongitudeDeg,
        latitudeStepDeg,
        longitudeStepDeg,
        rows.toDouble(),
        columns.toDouble(),
    )

internal fun HorizonOptions.pack() =
    doubleArrayOf(
        azimuthStepDeg,
        maxDistanceM,
        eyeHeightM,
        useGridElevation.pack(),
        observerElevationM,
        refractionCoefficient,
        minDistanceM,
    )

internal fun HorizonProfile.pack(): DoubleArray {
    require(altitudeDeg.size == distanceM.size) { "altitudeDeg and distanceM differ in size" }
    val n = altitudeDeg.size
    val out = DoubleArray(3 + 2 * n)
    out[0] = azimuthStepDeg
    out[1] = observerElevationM
    out[2] = n.toDouble()
    altitudeDeg.copyInto(out, 3)
    distanceM.copyInto(out, 3 + n)
    return out
}

internal fun DoubleArray.toHorizonProfile(): HorizonProfile {
    val n = this[2].toInt()
    return HorizonProfile(
        this[0],
        copyOfRange(3, 3 + n),
        copyOfRange(3 + n, 3 + 2 * n),
        this[1],
    )
}

internal fun TerrainEventOptions.pack() =
    doubleArrayOf(
        model.ordinal.toDouble(),
        refraction.pack(),
        atmosphere.pressureHpa,
        atmosphere.temperatureC,
        limbDeg,
        searchStepS,
        toleranceS,
        body.ordinal.toDouble(),
    )

internal fun List<ElevationGrid>.packHeaders(): DoubleArray {
    val headers = DoubleArray(6 * size)
    forEachIndexed { i, tile -> tile.packHeader().copyInto(headers, 6 * i) }
    return headers
}

internal fun List<ElevationGrid>.heights(): Array<FloatArray> = Array(size) { this[it].heightsM }

internal fun BodyOptions.pack() =
    doubleArrayOf(
        model.ordinal.toDouble(),
        refraction.pack(),
        atmosphere.pressureHpa,
        atmosphere.temperatureC,
    )

internal fun Site.pack() = doubleArrayOf(latitudeDeg, longitudeDeg, elevationM)

internal fun SightOptions.pack() = doubleArrayOf(refractionCoefficient, targetMarginM)

internal fun AlignmentOptions.pack() = body.pack() + doubleArrayOf(maxSeparationDeg, toleranceS)

internal fun AlignmentLineOptions.pack() =
    body.pack() +
        doubleArrayOf(minDistanceM, maxDistanceM, stepM, eyeHeightM, refractionCoefficient)

internal fun DarkOptions.pack() =
    doubleArrayOf(
        maxSunAltitudeDeg,
        maxMoonAltitudeDeg,
        maxMoonFraction,
        galacticCenter.pack(),
        minGalacticCenterAltitudeDeg,
        stepS,
        toleranceS,
    )

internal fun DoubleArray.toLinePoint(offset: Int) =
    LinePoint(
        Observer(this[offset], this[offset + 1]),
        this[offset + 2],
        this[offset + 3],
        this[offset + 4],
        toHorizontal(offset + 5),
    )

internal fun DoubleArray.toAlignmentLine(): AlignmentLine {
    val points = this[0].toInt()
    val spots = this[1].toInt()
    return AlignmentLine(
        List(points) { toLinePoint(2 + 7 * it) },
        List(spots) { toLinePoint(2 + 7 * (points + it)) },
    )
}

internal fun DoubleArray.toLunarEclipse(offset: Int) =
    LunarEclipse(
        EclipseKind.entries[this[offset].toInt()],
        this[offset + 1],
        this[offset + 2],
        this[offset + 3],
        this[offset + 4],
        this[offset + 5],
        this[offset + 6],
        this[offset + 7],
        this[offset + 8],
    )

internal fun DoubleArray.toSolarEclipse(offset: Int) =
    SolarEclipse(
        EclipseKind.entries[this[offset].toInt()],
        this[offset + 1],
        Observer(this[offset + 2], this[offset + 3]),
        this[offset + 4],
    )

internal fun DoubleArray.toLocalSolarEclipse(offset: Int) =
    LocalSolarEclipse(
        EclipseKind.entries[this[offset].toInt()],
        this[offset + 1],
        this[offset + 2],
        this[offset + 3],
        this[offset + 4],
        this[offset + 5],
        this[offset + 6],
        this[offset + 7],
    )

internal fun DoubleArray.toShadowPoint(offset: Int) =
    ShadowPoint(this[offset], Observer(this[offset + 1], this[offset + 2]), this[offset + 3])
