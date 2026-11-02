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

    @JvmStatic
    external fun headingFilterStart(
        bias: DoubleArray,
        biasSigmaUt: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun headingFilterPredict(
        filter: DoubleArray,
        dtS: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun headingFilterUpdate(filter: DoubleArray, measurement: DoubleArray): DoubleArray

    @JvmStatic
    external fun headingFilterUpdateMagnetometer(
        filter: DoubleArray,
        quaternion: DoubleArray,
        field: DoubleArray,
        observer: DoubleArray,
        unixUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun findAzimuthTimes(
        observer: DoubleArray,
        azimuthDeg: Double,
        startUtc: Double,
        endUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic
    external fun moonPosition(
        unixUtc: Double,
        observer: DoubleArray,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic external fun moonIlluminatedFraction(unixUtc: Double): Double

    /** Returns the heights and writes the other 6 values of the grid into [header]. */
    @JvmStatic
    external fun readElevationGeotiff(data: ByteArray, level: Int, header: DoubleArray): FloatArray

    @JvmStatic external fun elevationGeotiffPrefixSize(header: ByteArray, level: Int): Long

    @JvmStatic external fun elevationTiles(observer: DoubleArray, maxDistanceM: Double): DoubleArray

    @JvmStatic
    external fun horizonProfile(
        observer: DoubleArray,
        headers: DoubleArray,
        heights: Array<FloatArray>,
        options: DoubleArray,
    ): DoubleArray

    @JvmStatic external fun terrainAltitudeDeg(profile: DoubleArray, azimuthDeg: Double): Double

    @JvmStatic
    external fun terrainEvents(
        observer: DoubleArray,
        profile: DoubleArray,
        startUtc: Double,
        endUtc: Double,
        options: DoubleArray,
    ): DoubleArray

    /**
     * The C++ defaults of the option structs, Camera, View, and the heading filter, for the tests.
     */
    @JvmStatic external fun defaults(): DoubleArray
}
