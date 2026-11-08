package com.bikeos.core.metrics

import com.bikeos.core.model.BikeSample
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.pow

/** One second of a ride, stored for charts and exports. */
@Serializable
data class TracePoint(
    val t: Int,
    val power: Float,
    val cadence: Float,
    val resistance: Float,
    val speedMph: Float,
    val hr: Int? = null,
)

/** Live ride totals, cheap to copy and publish to UI every tick. */
@Serializable
data class RideStats(
    val elapsedSec: Int = 0,
    val movingSec: Int = 0,
    val distanceMiles: Double = 0.0,
    val workKj: Double = 0.0,
    val calories: Double = 0.0,
    val avgPower: Float = 0f,
    val maxPower: Float = 0f,
    val avgCadence: Float = 0f,
    val maxCadence: Float = 0f,
    val avgResistance: Float = 0f,
    val maxResistance: Float = 0f,
    val avgSpeedMph: Float = 0f,
    val maxSpeedMph: Float = 0f,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val normalizedPower: Float = 0f,
)

data class CalorieConfig(
    val useHeartRate: Boolean = false,
    val weightKg: Double = 75.0,
    val ageYears: Int = 35,
    val sex: Calories.Sex = Calories.Sex.MALE,
)

/**
 * Integrates raw bike samples into ride totals and a 1 Hz trace.
 * Not thread-safe: feed it from a single coroutine.
 */
class RideAccumulator(
    private val calorieConfig: CalorieConfig = CalorieConfig(),
    /** Gaps longer than this (sensor drop-outs) are only credited this many seconds. */
    private val maxGapSec: Double = 2.0,
) {
    private var lastTs: Long? = null
    private var elapsedMs = 0.0
    private var movingMs = 0.0
    private var distanceMiles = 0.0
    private var workJ = 0.0
    private var hrCalories = 0.0

    private var powerTime = 0.0 // sum(power * dt) over moving time
    private var cadenceTime = 0.0
    private var resistanceTime = 0.0
    private var speedTime = 0.0
    private var hrTime = 0.0
    private var hrDuration = 0.0
    private var maxPower = 0f
    private var maxCadence = 0f
    private var maxResistance = 0f
    private var maxSpeed = 0f
    private var maxHr: Int? = null

    private val trace = ArrayList<TracePoint>()
    private var nextTraceSec = 0
    private var latest: BikeSample? = null

    var paused: Boolean = false
        set(value) {
            field = value
            lastTs = null // never credit time spent paused
        }

    fun add(sample: BikeSample) {
        latest = sample
        val prev = lastTs
        lastTs = sample.timestampMs
        if (paused || prev == null) return
        val dtSec = ((sample.timestampMs - prev) / 1000.0).coerceIn(0.0, maxGapSec)
        if (dtSec <= 0.0) return

        elapsedMs += dtSec * 1000
        val speed = sample.speedMph
        if (sample.isPedaling) {
            movingMs += dtSec * 1000
            powerTime += sample.powerWatts * dtSec
            cadenceTime += sample.cadenceRpm * dtSec
            resistanceTime += sample.resistancePct * dtSec
            speedTime += speed * dtSec
        }
        workJ += sample.powerWatts * dtSec
        distanceMiles += speed * dtSec / 3600.0
        maxPower = max(maxPower, sample.powerWatts)
        maxCadence = max(maxCadence, sample.cadenceRpm)
        maxResistance = max(maxResistance, sample.resistancePct)
        maxSpeed = max(maxSpeed, speed)
        sample.heartRateBpm?.takeIf { it in 30..240 }?.let { hr ->
            hrTime += hr * dtSec
            hrDuration += dtSec
            maxHr = max(maxHr ?: 0, hr)
            hrCalories += Calories.keytelKcalPerMinute(
                hr, calorieConfig.weightKg, calorieConfig.ageYears, calorieConfig.sex,
            ) * dtSec / 60.0
        }

        // 1 Hz trace, holding the latest sample for each elapsed second.
        val elapsedSec = (elapsedMs / 1000).toInt()
        while (nextTraceSec <= elapsedSec) {
            trace += TracePoint(
                t = nextTraceSec,
                power = sample.powerWatts,
                cadence = sample.cadenceRpm,
                resistance = sample.resistancePct,
                speedMph = speed,
                hr = sample.heartRateBpm,
            )
            nextTraceSec++
        }
    }

    fun latestSample(): BikeSample? = latest

    fun stats(): RideStats {
        val moving = movingMs / 1000.0
        val workKj = workJ / 1000.0
        val calories = if (calorieConfig.useHeartRate && hrDuration > 60) hrCalories else Calories.fromWorkKj(workKj)
        return RideStats(
            elapsedSec = (elapsedMs / 1000).toInt(),
            movingSec = moving.toInt(),
            distanceMiles = distanceMiles,
            workKj = workKj,
            calories = calories,
            avgPower = if (moving > 0) (powerTime / moving).toFloat() else 0f,
            maxPower = maxPower,
            avgCadence = if (moving > 0) (cadenceTime / moving).toFloat() else 0f,
            maxCadence = maxCadence,
            avgResistance = if (moving > 0) (resistanceTime / moving).toFloat() else 0f,
            maxResistance = maxResistance,
            avgSpeedMph = if (moving > 0) (speedTime / moving).toFloat() else 0f,
            maxSpeedMph = maxSpeed,
            avgHr = if (hrDuration > 0) (hrTime / hrDuration).toInt() else null,
            maxHr = maxHr,
            normalizedPower = normalizedPower(trace),
        )
    }

    fun trace(): List<TracePoint> = trace.toList()

    companion object {
        /** Normalized Power: 4th-power mean of the 30 s rolling average power. */
        fun normalizedPower(trace: List<TracePoint>): Float {
            if (trace.size < 30) return 0f
            var windowSum = 0.0
            var sum4 = 0.0
            var n = 0
            for (i in trace.indices) {
                windowSum += trace[i].power
                if (i >= 30) windowSum -= trace[i - 30].power
                if (i >= 29) {
                    sum4 += (windowSum / 30.0).pow(4)
                    n++
                }
            }
            return (sum4 / n).pow(0.25).toFloat()
        }

        /** Best average power over any window of [seconds] (e.g. 1200 for a 20 min FTP test). */
        fun bestAverage(trace: List<TracePoint>, seconds: Int): Float {
            if (trace.size < seconds || seconds <= 0) return 0f
            var sum = 0.0
            var best = 0.0
            for (i in trace.indices) {
                sum += trace[i].power
                if (i >= seconds) sum -= trace[i - seconds].power
                if (i >= seconds - 1) best = max(best, sum / seconds)
            }
            return best.toFloat()
        }
    }
}

/**
 * Peloton's resistance ADC occasionally spikes for a single reading. Reporting the minimum of the
 * last few readings removes the spikes with almost no lag.
 */
class SpikeFilter(private val window: Int = 3) {
    private val buf = ArrayDeque<Float>()
    fun push(v: Float): Float {
        buf.addLast(v)
        while (buf.size > window) buf.removeFirst()
        return buf.min()
    }
}
