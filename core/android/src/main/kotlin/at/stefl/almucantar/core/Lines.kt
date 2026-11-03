package at.stefl.almucantar.core

/**
 * A line of directions in packed form. Pack a path once (for example once per day), and project it
 * in each frame with [projectLine]. Then a frame allocates only the result.
 */
class DirectionLine internal constructor(internal val packed: DoubleArray) {
    /** The number of directions. */
    val size: Int
        get() = packed.size / 2

    operator fun get(index: Int): Horizontal = packed.toHorizontal(2 * index)

    companion object {
        fun of(directions: List<Horizontal>): DirectionLine {
            val packed = DoubleArray(2 * directions.size)
            directions.forEachIndexed { i, d ->
                packed[2 * i] = d.azimuthDeg
                packed[2 * i + 1] = d.altitudeDeg
            }
            return DirectionLine(packed)
        }

        fun fromPath(path: List<PathPoint>): DirectionLine = of(path.map { it.position })
    }
}

/**
 * Polylines on the screen in packed form, the result of [projectLine] and [horizonLine]. Read the
 * points with [x] and [y], or convert them with [toList].
 */
class Polylines internal constructor(private val data: DoubleArray) {
    private val starts: IntArray
    private val sizes: IntArray

    init {
        val count = data[0].toInt()
        starts = IntArray(count)
        sizes = IntArray(count)
        var i = 1
        for (k in 0 until count) {
            sizes[k] = data[i].toInt()
            starts[k] = i + 1
            i += 1 + 2 * sizes[k]
        }
    }

    /** The number of polylines. */
    val count: Int
        get() = starts.size

    /** The number of points of polyline [line]. */
    fun size(line: Int): Int = sizes[line]

    fun x(line: Int, point: Int): Double = data[starts[line] + 2 * point]

    fun y(line: Int, point: Int): Double = data[starts[line] + 2 * point + 1]

    fun toList(): List<List<ScreenPoint>> =
        (0 until count).map { line ->
            (0 until size(line)).map { ScreenPoint(x(line, it), y(line, it)) }
        }
}
