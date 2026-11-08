package com.bikeos.core.stats

import com.bikeos.core.export.RideRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

data class TrainingSummary(
    val totalRides: Int,
    val totalMinutes: Int,
    val totalKj: Int,
    val totalMiles: Double,
    val weekRides: Int,
    val weekMinutes: Int,
    val weekKj: Int,
    val dayStreak: Int,
    val todayCalories: Int,
    val bestOutputKj: Int,
)

object Streaks {
    fun summarize(rides: List<RideRecord>, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): TrainingSummary {
        fun dayOf(r: RideRecord) = Instant.ofEpochMilli(r.startedAtMs).atZone(zone).toLocalDate()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week = rides.filter { !dayOf(it).isBefore(weekStart) }
        val days = rides.map(::dayOf).toSet()
        var streak = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) {
            streak++
            d = d.minus(1, ChronoUnit.DAYS)
        }
        return TrainingSummary(
            totalRides = rides.size,
            totalMinutes = rides.sumOf { it.stats.elapsedSec } / 60,
            totalKj = rides.sumOf { it.stats.workKj }.toInt(),
            totalMiles = rides.sumOf { it.stats.distanceMiles },
            weekRides = week.size,
            weekMinutes = week.sumOf { it.stats.elapsedSec } / 60,
            weekKj = week.sumOf { it.stats.workKj }.toInt(),
            dayStreak = streak,
            todayCalories = rides.filter { dayOf(it) == today }.sumOf { it.stats.calories }.toInt(),
            bestOutputKj = rides.maxOfOrNull { it.stats.workKj }?.toInt() ?: 0,
        )
    }
}
