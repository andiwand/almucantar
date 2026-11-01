package at.stefl.sunradar.core

/** The JNI functions of libsunradar_jni.so. See core/android/jni/sunradar_jni.cpp. */
internal object SunradarNative {
    init {
        System.loadLibrary("sunradar_jni")
    }

    @JvmStatic external fun version(): String

    @JvmStatic
    external fun sunPosition(
        unixUtc: Double,
        observer: DoubleArray,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun sunEvents(
        observer: DoubleArray,
        startUtc: Double,
        endUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun sunPath(
        observer: DoubleArray,
        startUtc: Double,
        endUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic external fun magneticDeclinationDeg(observer: DoubleArray, unixUtc: Double): Double

    @JvmStatic
    external fun cameraFromFov(width: Double, height: Double, horizontalFovDeg: Double): DoubleArray

    @JvmStatic external fun project(view: DoubleArray, direction: DoubleArray): DoubleArray

    @JvmStatic external fun unproject(view: DoubleArray, point: DoubleArray): DoubleArray

    @JvmStatic external fun projectLine(view: DoubleArray, line: DoubleArray): DoubleArray

    @JvmStatic external fun horizonLine(view: DoubleArray): DoubleArray

    @JvmStatic
    external fun findTime(
        observer: DoubleArray,
        direction: DoubleArray,
        startUtc: Double,
        endUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun findDates(
        observer: DoubleArray,
        direction: DoubleArray,
        startUtc: Double,
        endUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun headingPredict(
        estimate: DoubleArray,
        dtS: Double,
        driftDeg2PerS: Double,
    ): DoubleArray

    @JvmStatic
    external fun headingUpdate(estimate: DoubleArray, measurement: DoubleArray): DoubleArray

    @JvmStatic
    external fun headingFromMagnetometer(
        quaternion: DoubleArray,
        field: DoubleArray,
        observer: DoubleArray,
        unixUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun headingFromDirection(
        view: DoubleArray,
        point: DoubleArray,
        trueAzimuthDeg: Double,
        sigmaDeg: Double,
    ): DoubleArray

    /** The C++ defaults of the option structs, Camera, View, and the heading, for the tests. */
    @JvmStatic external fun defaults(): DoubleArray
}
