package com.bikeos.core.metrics

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Peloton does not measure speed; it derives a "virtual" speed from power.
 * This piecewise cubic in sqrt(power) is the regression published by the PeloMon project
 * (https://ihaque.org/posts/2020/12/25/pelomon-part-ib-computing-speed/), which matches the
 * Peloton app's displayed speed closely.
 */
object SpeedModel {
    fun mphFromPower(power: Float): Float {
        if (power < 0.1f) return 0f
        val r = sqrt(power)
        val mph = if (power < 26f) {
            0.057f - 0.172f * r + 0.759f * r.pow(2) - 0.079f * r.pow(3)
        } else {
            -1.635f + 2.325f * r - 0.064f * r.pow(2) + 0.001f * r.pow(3)
        }
        return mph.coerceAtLeast(0f)
    }

    const val KM_PER_MILE = 1.609344f
    fun kmhFromPower(power: Float) = mphFromPower(power) * KM_PER_MILE
}
