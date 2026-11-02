package at.stefl.sunradar.core

// The value types of the public C++ API (core/src/sunradar/*.hpp). The names, the order of
// the enum constants, and the defaults are the same as in C++. An instrumented test checks
// the defaults against the native library.

/** A place on the Earth, in geodetic coordinates (WGS 84). */
data class Observer(
    /** North is positive, -90 to 90. */
    val latitudeDeg: Double = 0.0,
    /** East is positive, -180 to 180. */
    val longitudeDeg: Double = 0.0,
)

/** A direction in the local horizontal system of an observer. */
data class Horizontal(
    /** From true north, clockwise (east is 90), 0 to 360. */
    val azimuthDeg: Double = 0.0,
    /** Above the horizon, -90 to 90. */
    val altitudeDeg: Double = 0.0,
)

/** The state of the air near the observer. The refraction depends on it. */
data class Atmosphere(val pressureHpa: Double = 1010.0, val temperatureC: Double = 10.0)

/**
 * A unit quaternion. For the view, it rotates from the device frame to East-North-Up, the same as
 * `SensorManager.getQuaternionFromVector()`.
 */
data class Quaternion(
    val w: Double = 1.0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
)

/** A point on the screen, in pixels. The origin is the top left corner, and y goes down. */
data class ScreenPoint(val x: Double = 0.0, val y: Double = 0.0)

/** The model that calculates the position of the sun. */
enum class SolarModel {
    /** NOAA solar calculator (Meeus). */
    NOAA,
    /** Astronomy Engine: more precise and slower. */
    ASTRONOMY_ENGINE,
}

enum class DayKind {
    /** The sun crosses the threshold at least once. */
    NORMAL,
    /** The sun stays above the threshold for the full interval. */
    POLAR_DAY,
    /** The sun stays below the threshold for the full interval. */
    POLAR_NIGHT,
}

/**
 * The rotation of the screen content, with the meaning of Android `Display.getRotation()`: the
 * ordinal is the `Surface.ROTATION_*` value. [ROTATION_90] means that the device is turned 90
 * degrees counterclockwise.
 */
enum class ScreenRotation {
    ROTATION_0,
    /** Device turned 90 degrees counterclockwise: screen right is device -y. */
    ROTATION_90,
    ROTATION_180,
    /** Device turned 90 degrees clockwise: screen right is device +y. */
    ROTATION_270,
}

/** The airless altitude of the center of the sun at the standard sunrise and sunset. */
const val SUNRISE_ALTITUDE_DEG: Double = -0.8333

data class SunOptions(
    val model: SolarModel = SolarModel.NOAA,
    val refraction: Boolean = true,
    val atmosphere: Atmosphere = Atmosphere(),
)

data class EventOptions(
    val model: SolarModel = SolarModel.NOAA,
    val altitudeDeg: Double = SUNRISE_ALTITUDE_DEG,
    val searchStepS: Double = 600.0,
    val toleranceS: Double = 0.5,
)

/** The sun crosses the threshold altitude. */
data class Crossing(val unixUtc: Double, val rising: Boolean)

data class DayEvents(
    val kind: DayKind,
    val crossings: List<Crossing>,
    val culminationUtc: Double,
    val culminationAltitudeDeg: Double,
)

data class PathOptions(
    val model: SolarModel = SolarModel.NOAA,
    val stepS: Double = 300.0,
    val refraction: Boolean = true,
    val atmosphere: Atmosphere = Atmosphere(),
)

data class PathPoint(val unixUtc: Double, val position: Horizontal)

data class SearchOptions(
    val model: SolarModel = SolarModel.NOAA,
    val stepS: Double = 300.0,
    val toleranceS: Double = 0.5,
    val refraction: Boolean = true,
    val atmosphere: Atmosphere = Atmosphere(),
    val maxSeparationDeg: Double = 2.0,
)

/** The time when the sun is nearest to a direction. */
data class TimeMatch(val unixUtc: Double, val separationDeg: Double, val sun: Horizontal)

/**
 * A pinhole camera with Brown-Conrady distortion (OpenCV convention), in the pixels of the screen.
 * The default is a virtual camera of 1000 x 1000 px with a horizontal field of view of 60 degrees.
 */
data class Camera(
    val width: Double = 1000.0,
    val height: Double = 1000.0,
    val fx: Double = 866.0254037844386,
    val fy: Double = 866.0254037844386,
    val cx: Double = 500.0,
    val cy: Double = 500.0,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val k3: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
)

/** Everything that defines what the screen shows. */
data class View(
    /** Device frame to East-North-Up. North is the north of the sensor. */
    val deviceToEnu: Quaternion = Quaternion(),
    val screenRotation: ScreenRotation = ScreenRotation.ROTATION_0,
    /** True azimuth = sensor azimuth + this offset (declination plus calibration). */
    val headingOffsetDeg: Double = 0.0,
    val camera: Camera = Camera(),
)

/** The result of [project]. [point] has a meaning only if [valid] is true. */
data class Projection(val valid: Boolean, val point: ScreenPoint)

/** A vector for sensor values. The frame and the unit depend on the use. */
data class Vector3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)

/** The variance of a heading that is not known at all, in square degrees. */
const val UNKNOWN_HEADING_VARIANCE_DEG2: Double = 180.0 * 180.0

/** The default growth of the variance in [headingPredict], in square degrees per second. */
const val DEFAULT_HEADING_DRIFT_DEG2_PER_S: Double = 1.0 / 60.0

/**
 * The estimate of a heading filter: true azimuth = frame azimuth + [offsetDeg]. Use [offsetDeg] as
 * [View.headingOffsetDeg].
 */
data class HeadingEstimate(
    val offsetDeg: Double = 0.0,
    val varianceDeg2: Double = UNKNOWN_HEADING_VARIANCE_DEG2,
)

/** One measurement of the heading offset. */
data class HeadingMeasurement(val offsetDeg: Double, val varianceDeg2: Double)

data class MagnetometerOptions(val sensorSigmaDeg: Double = 2.0)

/** The number of values in the state of a [HeadingFilter]. */
const val HEADING_FILTER_SIZE: Int = 7

/** The noise model of a [HeadingFilter]. */
data class HeadingFilterOptions(
    /** The growth of the variance of the offset, because the yaw of the frame drifts. */
    val driftDeg2PerS: Double = DEFAULT_HEADING_DRIFT_DEG2_PER_S,
    /** The growth of the variance of each bias component (1 µT² in 10 minutes). */
    val biasDriftUt2PerS: Double = 1.0 / 600.0,
    /** The size of a local disturbance of the field, per component. */
    val disturbanceSigmaUt: Double = 3.0,
    /** The correlation time of a local disturbance. */
    val disturbanceTimeS: Double = 120.0,
    /** The noise of one magnetometer sample, per component. */
    val noiseSigmaUt: Double = 1.0,
)

/**
 * The heading filter with the magnetometer: the offset of the frame to true north, the hard-iron
 * bias of the magnetometer in the device frame, and a local disturbance in East-North-Up, with
 * their covariance (row by row, in that order). Make it with [headingFilterStart].
 */
data class HeadingFilter(
    val offsetDeg: Double,
    val biasUt: Vector3,
    val disturbanceUt: Vector3,
    val covariance: List<Double>,
) {
    /** The variance of the offset. */
    val varianceDeg2: Double
        get() = covariance[0]

    /** The offset and its variance. */
    val estimate: HeadingEstimate
        get() = HeadingEstimate(offsetDeg, varianceDeg2)
}

data class MoonOptions(val refraction: Boolean = true, val atmosphere: Atmosphere = Atmosphere())

/** The sun has a given azimuth. */
data class AzimuthCrossing(val unixUtc: Double, val sun: Horizontal)

/**
 * A digital elevation model (DEM) as a plain grid of heights, for example a tile of Copernicus
 * GLO-90. The sample in row `r` and column `c` is at the latitude `northLatitudeDeg - r *
 * latitudeStepDeg` and the longitude `westLongitudeDeg + c * longitudeStepDeg`. So the rows go from
 * north to south. The heights stay in a [FloatArray], because a tile has more than a million.
 */
class ElevationGrid(
    /** The latitude of row 0. */
    val northLatitudeDeg: Double,
    /** The longitude of column 0. */
    val westLongitudeDeg: Double,
    /** The distance between two rows, positive. */
    val latitudeStepDeg: Double,
    /** The distance between two columns, positive. */
    val longitudeStepDeg: Double,
    /** At least 2. */
    val rows: Int,
    /** At least 2. */
    val columns: Int,
    /** The heights above sea level in meters, row by row. NaN is a sample without data. */
    val heightsM: FloatArray,
)

data class HorizonOptions(
    /** The distance between two azimuths of the profile. 360 must be a multiple of it. */
    val azimuthStepDeg: Double = 0.25,
    /** The terrain farther away is not used, at most 500 km. */
    val maxDistanceM: Double = 100_000.0,
    /** The height of the eye above the ground. */
    val eyeHeightM: Double = 1.6,
    /**
     * If true, the elevation of the ground comes from the tiles, else from [observerElevationM].
     */
    val useGridElevation: Boolean = true,
    val observerElevationM: Double = 0.0,
    /** The coefficient of the terrestrial refraction. 0.13 is the standard value. */
    val refractionCoefficient: Double = 0.13,
)

/**
 * The altitude of the terrain horizon for each azimuth. The entry `i` is for the azimuth `i *
 * azimuthStepDeg`. NaN is a direction without data. Do not change the arrays after the first use,
 * because the class keeps them in packed form for the native calls.
 */
class HorizonProfile(
    val azimuthStepDeg: Double,
    val altitudeDeg: DoubleArray,
    val distanceM: DoubleArray,
    /** The elevation of the ground at the observer. */
    val observerElevationM: Double = 0.0,
) {
    internal val packed: DoubleArray by lazy { pack() }
}

/**
 * A tile of 1° by 1° of a global DEM, named by its south-west corner. Copernicus names the tile
 * with [latitudeDeg] 47 and [longitudeDeg] 11 `N47_00_E011_00`.
 */
data class ElevationTile(
    /** -90 to 89. */
    val latitudeDeg: Int,
    /** -180 to 179. */
    val longitudeDeg: Int,
    /** The distance from the observer to the nearest point of the tile, 0 for the own tile. */
    val distanceM: Double = 0.0,
)

/** A body that can rise and set behind the terrain. */
enum class Body {
    SUN,
    MOON,
}

data class TerrainEventOptions(
    /** The sun or the moon. */
    val body: Body = Body.SUN,
    /** Used only for the sun. */
    val model: SolarModel = SolarModel.NOAA,
    val refraction: Boolean = true,
    val atmosphere: Atmosphere = Atmosphere(),
    /** The body is visible if its center plus this angle is above the terrain. */
    val limbDeg: Double = 0.2667,
    /** The interval of the first search. */
    val searchStepS: Double = 60.0,
    val toleranceS: Double = 0.5,
)

/** The times when the body appears or goes away behind the terrain. */
data class TerrainEvents(
    /** POLAR_DAY: the sun stays above the terrain. POLAR_NIGHT: it stays behind or below. */
    val kind: DayKind,
    /** All crossings, in time order. `rising` is an appearance. */
    val crossings: List<Crossing>,
)
