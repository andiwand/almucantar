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
    NOAA
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
