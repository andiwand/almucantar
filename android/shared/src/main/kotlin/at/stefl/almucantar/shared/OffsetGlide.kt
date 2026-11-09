package at.stefl.almucantar.shared

import kotlin.math.abs
import kotlin.math.exp

/**
 * The heading offset that the screen shows. The heading filter changes its offset once per second,
 * in steps of some degrees while it calibrates the magnetometer. So the shown offset glides to the
 * offset of the filter, and the sky does not jump. Only the offset glides: the orientation of the
 * device goes to the screen without a delay, so the overlay stays on the camera image in a turn.
 *
 * The same rules are in `ios/Shared/MotionTracker.swift`.
 */
class OffsetGlide {
    /** The shown offset in degrees, in [0, 360). */
    var offsetDeg = 0.0
        private set

    private var started = false

    /** Sets the shown offset to [targetDeg] at once, for example for the first estimate. */
    fun jump(targetDeg: Double) {
        offsetDeg = wrap360(targetDeg)
        started = true
    }

    /**
     * Moves the shown offset toward [targetDeg] for a time step of [dtS] seconds. A step larger
     * than [JUMP_DEG] does not glide, because a glide over a large angle looks like a turn of the
     * sky.
     */
    fun follow(targetDeg: Double, dtS: Double) {
        val difference = wrap180(targetDeg - offsetDeg)
        if (!started || abs(difference) > JUMP_DEG) {
            jump(targetDeg)
            return
        }
        val dt = dtS.coerceIn(0.0, MAX_STEP_S)
        offsetDeg = wrap360(offsetDeg + difference * (1 - exp(-dt / TIME_CONSTANT_S)))
    }

    companion object {
        /**
         * The time constant of the glide: after 1 s (the next filter step), 92 % of a step is done.
         */
        const val TIME_CONSTANT_S = 0.4
        /** A step of the offset larger than this jumps at once. */
        const val JUMP_DEG = 45.0
        /** The longest time step: after a pause of the sensors, the glide does not jump. */
        const val MAX_STEP_S = 0.1

        private fun wrap360(deg: Double) = ((deg % 360) + 360) % 360

        private fun wrap180(deg: Double) = ((deg % 360) + 540) % 360 - 180
    }
}
