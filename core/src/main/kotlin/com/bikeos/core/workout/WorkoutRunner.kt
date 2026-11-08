package com.bikeos.core.workout

import com.bikeos.core.metrics.PowerZones
import com.bikeos.core.model.BikeSample

data class WorkoutProgress(
    val workout: Workout,
    val segmentIndex: Int,
    val segment: Segment,
    val segmentElapsedSec: Int,
    val segmentRemainingSec: Int,
    val totalElapsedSec: Int,
    val totalRemainingSec: Int,
    val next: Segment?,
    val finished: Boolean,
) {
    val segmentFraction: Float get() = segmentElapsedSec.toFloat() / segment.durationSec.coerceAtLeast(1)
    val totalFraction: Float get() = totalElapsedSec.toFloat() / workout.durationSec.coerceAtLeast(1)
}

data class TargetEvaluation(
    val cadence: TargetStatus,
    val resistance: TargetStatus,
    val power: TargetStatus,
    val powerBand: IntRange?,
) {
    /** True if every target that is set is being hit. */
    val onTarget: Boolean
        get() = listOf(cadence, resistance, power).all { it == TargetStatus.NONE || it == TargetStatus.IN_RANGE } &&
            listOf(cadence, resistance, power).any { it != TargetStatus.NONE }
    val hasTargets: Boolean get() = listOf(cadence, resistance, power).any { it != TargetStatus.NONE }
}

/** Stateless mapping from elapsed time to position inside a workout. */
class WorkoutRunner(val workout: Workout) {
    private val starts: IntArray = IntArray(workout.segments.size).also { arr ->
        var acc = 0
        workout.segments.forEachIndexed { i, s -> arr[i] = acc; acc += s.durationSec }
    }

    fun progressAt(elapsedSec: Int): WorkoutProgress {
        val total = workout.durationSec
        val clamped = elapsedSec.coerceIn(0, total)
        var idx = starts.indexOfLast { it <= clamped }.coerceAtLeast(0)
        if (clamped >= total) idx = workout.segments.lastIndex
        val seg = workout.segments[idx]
        val segElapsed = (clamped - starts[idx]).coerceAtMost(seg.durationSec)
        return WorkoutProgress(
            workout = workout,
            segmentIndex = idx,
            segment = seg,
            segmentElapsedSec = segElapsed,
            segmentRemainingSec = seg.durationSec - segElapsed,
            totalElapsedSec = clamped,
            totalRemainingSec = total - clamped,
            next = workout.segments.getOrNull(idx + 1),
            finished = elapsedSec >= total,
        )
    }

    fun segmentStart(index: Int): Int = starts[index]

    companion object {
        fun evaluate(segment: Segment, sample: BikeSample, ftp: Int): TargetEvaluation {
            val band = segment.powerZone?.let { PowerZones.targetBand(it, ftp) }
            val powerStatus = when {
                band == null -> TargetStatus.NONE
                sample.powerWatts < band.first -> TargetStatus.BELOW
                sample.powerWatts > band.last -> TargetStatus.ABOVE
                else -> TargetStatus.IN_RANGE
            }
            return TargetEvaluation(
                cadence = segment.cadence.status(sample.cadenceRpm),
                resistance = segment.resistance.status(sample.resistancePct),
                power = powerStatus,
                powerBand = band,
            )
        }
    }
}

/** Tracks the share of time spent on target, overall and per segment. */
class ComplianceTracker(segmentCount: Int) {
    private val onTargetSec = DoubleArray(segmentCount)
    private val targetedSec = DoubleArray(segmentCount)

    fun record(segmentIndex: Int, eval: TargetEvaluation, dtSec: Double) {
        if (!eval.hasTargets || segmentIndex !in onTargetSec.indices) return
        targetedSec[segmentIndex] += dtSec
        if (eval.onTarget) onTargetSec[segmentIndex] += dtSec
    }

    /** 0-100, or null when the workout had no targets. */
    fun score(): Int? {
        val total = targetedSec.sum()
        if (total < 1.0) return null
        return (onTargetSec.sum() / total * 100).toInt()
    }

    fun segmentScore(index: Int): Int? {
        val t = targetedSec.getOrNull(index) ?: return null
        if (t < 1.0) return null
        return (onTargetSec[index] / t * 100).toInt()
    }
}
