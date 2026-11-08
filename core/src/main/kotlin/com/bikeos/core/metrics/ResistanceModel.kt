package com.bikeos.core.metrics

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow

/**
 * Models power as a function of Peloton resistance and cadence:
 *
 *     P(r, rpm) = C(r) * (rpm / 90) ^ EXPONENT
 *
 * The flywheel uses an eddy-current (magnetic) brake, so at a fixed magnet position torque rises
 * roughly linearly with flywheel speed, giving power ≈ cadence². C(r) is "watts at 90 rpm" for
 * each resistance step. It starts from a generic curve and is continuously re-fit from the
 * rider's own bike, because every bike's calibration is slightly different.
 *
 * Used for:
 *  - ERG mode (an app like Zwift asks for a target power): which resistance gets you there?
 *  - Power-zone classes on a manual Bike: "turn the knob to ~42".
 */
class ResistanceModel(initial: List<Float>? = null) {
    @Serializable
    data class Snapshot(val wattsAt90: List<Float>, val samples: Long)

    companion object {
        const val EXPONENT = 2.0
        const val STEP = 5 // resistance bin width
        val BIN_COUNT = 100 / STEP + 1

        /** Generic starting curve: watts at 90 rpm for resistance 0, 5, 10, ... 100. */
        val SEED: List<Float> = listOf(
            10f, 17f, 25f, 37f, 50f, 67f, 85f, 107f, 130f, 157f, 185f,
            220f, 255f, 295f, 335f, 380f, 425f, 472f, 520f, 570f, 620f,
        )
        private const val LEARN_RATE = 0.04f
    }

    private val table: FloatArray = (initial?.takeIf { it.size == BIN_COUNT } ?: SEED).toFloatArray()
    var samplesLearned: Long = 0
        private set

    fun snapshot() = Snapshot(table.toList(), samplesLearned)

    fun restore(s: Snapshot) {
        if (s.wattsAt90.size == BIN_COUNT) {
            s.wattsAt90.forEachIndexed { i, v -> table[i] = v }
            samplesLearned = s.samples
        }
    }

    fun wattsAt90(resistance: Float): Float {
        val r = resistance.coerceIn(0f, 100f)
        val pos = r / STEP
        val i = pos.toInt().coerceAtMost(BIN_COUNT - 2)
        val f = pos - i
        return table[i] * (1 - f) + table[i + 1] * f
    }

    fun predictPower(resistance: Float, cadence: Float): Float {
        if (cadence <= 0f) return 0f
        return wattsAt90(resistance) * (cadence / 90f).toDouble().pow(EXPONENT).toFloat()
    }

    /** Resistance (0-100) that would produce [targetWatts] at [cadence]. */
    fun resistanceFor(targetWatts: Float, cadence: Float): Float {
        val cad = if (cadence < 40f) 80f else cadence // assume a sensible cadence if coasting
        var best = 0f
        var bestErr = Float.MAX_VALUE
        var r = 0f
        while (r <= 100f) {
            val err = abs(predictPower(r, cad) - targetWatts)
            if (err < bestErr) {
                bestErr = err; best = r
            }
            r += 0.5f
        }
        return best
    }

    /** Update the curve from a live reading. Only steady, meaningful readings are used. */
    fun learn(resistance: Float, cadence: Float, watts: Float) {
        if (cadence < 45f || cadence > 130f || watts < 15f || resistance <= 0f) return
        val observedAt90 = watts / (cadence / 90f).toDouble().pow(EXPONENT).toFloat()
        val pos = resistance.coerceIn(0f, 100f) / STEP
        val i = pos.toInt().coerceAtMost(BIN_COUNT - 2)
        val f = pos - i
        // Split the correction between the two neighbouring bins by proximity.
        val predicted = wattsAt90(resistance)
        val ratio = (observedAt90 / predicted).coerceIn(0.5f, 2f)
        table[i] *= 1 + (ratio - 1) * LEARN_RATE * (1 - f)
        table[i + 1] *= 1 + (ratio - 1) * LEARN_RATE * f
        enforceMonotonic()
        samplesLearned++
    }

    private fun enforceMonotonic() {
        for (k in 1 until BIN_COUNT) if (table[k] < table[k - 1] + 1f) table[k] = table[k - 1] + 1f
    }
}

/**
 * Turns "virtual terrain" from apps (FTMS simulation mode: grade in %) into a resistance
 * suggestion, the way outdoor riding feels: flat road = your chosen base resistance,
 * every 1% of climb adds [pointsPerPercent] of resistance.
 */
data class TerrainMapping(val baseResistance: Float = 30f, val pointsPerPercent: Float = 3f) {
    fun resistanceForGrade(gradePct: Float): Float =
        (baseResistance + gradePct * pointsPerPercent).coerceIn(0f, 100f)
}
