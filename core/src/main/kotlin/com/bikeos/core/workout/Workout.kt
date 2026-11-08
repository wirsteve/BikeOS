package com.bikeos.core.workout

import kotlinx.serialization.Serializable

@Serializable
enum class SegmentType { WARMUP, FLAT, CLIMB, SPRINT, INTERVAL, RECOVERY, COOLDOWN, TEST }

@Serializable
data class Range(val min: Int, val max: Int) {
    operator fun contains(v: Float) = v >= min && v <= max
    val mid: Float get() = (min + max) / 2f
    override fun toString() = "$min-$max"
}

/**
 * One block of a structured ride. Any combination of targets may be set:
 * cadence + resistance (classic class style), or a power zone (power-zone style), or none
 * (free riding, e.g. a "just ride" block).
 */
@Serializable
data class Segment(
    val name: String,
    val durationSec: Int,
    val type: SegmentType = SegmentType.FLAT,
    val cadence: Range? = null,
    val resistance: Range? = null,
    val powerZone: Int? = null,
    val cue: String? = null,
)

@Serializable
enum class WorkoutCategory { INTERVALS, ENDURANCE, CLIMB, POWER_ZONE, LOW_IMPACT, TEST, CUSTOM }

@Serializable
data class Workout(
    val id: String,
    val title: String,
    val description: String,
    val category: WorkoutCategory,
    val segments: List<Segment>,
    val difficulty: Int = 5, // 1-10
) {
    val durationSec: Int get() = segments.sumOf { it.durationSec }
    val durationMin: Int get() = (durationSec + 30) / 60
    val usesPowerZones: Boolean get() = segments.any { it.powerZone != null }
}

enum class TargetStatus { NONE, BELOW, IN_RANGE, ABOVE }

fun Range?.status(value: Float): TargetStatus = when {
    this == null -> TargetStatus.NONE
    value < min -> TargetStatus.BELOW
    value > max -> TargetStatus.ABOVE
    else -> TargetStatus.IN_RANGE
}
