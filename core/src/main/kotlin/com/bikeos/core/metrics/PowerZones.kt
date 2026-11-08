package com.bikeos.core.metrics

import kotlin.math.roundToInt

/**
 * Seven-zone power model based on Functional Threshold Power (Coggan style), the same
 * structure used by power-zone classes on most platforms.
 */
object PowerZones {
    data class Zone(val number: Int, val name: String, val lowPct: Float, val highPct: Float?) {
        fun lowWatts(ftp: Int) = (ftp * lowPct).roundToInt()
        fun highWatts(ftp: Int) = highPct?.let { (ftp * it).roundToInt() }
        fun label(ftp: Int): String {
            val hi = highWatts(ftp)
            return if (hi == null) "${lowWatts(ftp)}+ W" else "${lowWatts(ftp)}-$hi W"
        }
    }

    val zones = listOf(
        Zone(1, "Active Recovery", 0f, 0.55f),
        Zone(2, "Endurance", 0.55f, 0.75f),
        Zone(3, "Tempo", 0.75f, 0.90f),
        Zone(4, "Threshold", 0.90f, 1.05f),
        Zone(5, "VO2 Max", 1.05f, 1.20f),
        Zone(6, "Anaerobic", 1.20f, 1.50f),
        Zone(7, "Neuromuscular", 1.50f, null),
    )

    fun zoneFor(watts: Float, ftp: Int): Zone {
        if (ftp <= 0) return zones.first()
        val pct = watts / ftp
        return zones.lastOrNull { pct >= it.lowPct } ?: zones.first()
    }

    fun zone(number: Int): Zone = zones[(number - 1).coerceIn(0, zones.lastIndex)]

    /** Target band for a zone, in watts. Zone 7 uses 150-200% of FTP as an open-ended band. */
    fun targetBand(number: Int, ftp: Int): IntRange {
        val z = zone(number)
        val lo = z.lowWatts(ftp)
        val hi = z.highWatts(ftp) ?: (ftp * 2.0f).roundToInt()
        return lo..hi
    }

    /** FTP estimate from a 20 minute all-out test: 95% of the average power. */
    fun ftpFrom20MinTest(avgPower20Min: Float): Int = (avgPower20Min * 0.95f).roundToInt()
}
