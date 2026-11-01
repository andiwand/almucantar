package at.stefl.sunradar.core

// Conversion between the Kotlin types and the flat double arrays of the JNI binding. The
// layouts are documented in core/android/jni/sunradar_jni.cpp and must stay the same.

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

internal fun MoonOptions.pack() =
    doubleArrayOf(refraction.pack(), atmosphere.pressureHpa, atmosphere.temperatureC)
