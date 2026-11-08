package com.bikeos.core.export

import com.bikeos.core.metrics.RideStats
import com.bikeos.core.metrics.TracePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A finished ride, as persisted on the bike. */
@Serializable
data class RideRecord(
    val id: String,
    val startedAtMs: Long,
    val title: String,
    val workoutId: String? = null,
    val stats: RideStats,
    val targetScore: Int? = null,
    val ftpAtRide: Int? = null,
    val newFtp: Int? = null,
    val companionApp: String? = null, // e.g. "YouTube" if the overlay ride was started from an app tile
    val trace: List<TracePoint> = emptyList(),
) {
    /** Header-only copy for list screens. */
    fun summary() = copy(trace = emptyList())

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): RideRecord = json.decodeFromString(serializer(), text)
        fun encode(r: RideRecord): String = json.encodeToString(serializer(), r)
    }
}
