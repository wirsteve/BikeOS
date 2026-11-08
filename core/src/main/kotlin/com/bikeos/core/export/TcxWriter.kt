package com.bikeos.core.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Garmin Training Center XML export. Strava, Garmin Connect, TrainingPeaks, intervals.icu and
 * most other platforms accept TCX uploads including power, cadence and heart rate.
 */
object TcxWriter {
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(ms))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun write(ride: RideRecord): String {
        val s = ride.stats
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" """ +
                """xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">""",
        ).append('\n')
        sb.append("<Activities><Activity Sport=\"Biking\">\n")
        sb.append("<Id>").append(iso(ride.startedAtMs)).append("</Id>\n")
        sb.append("<Lap StartTime=\"").append(iso(ride.startedAtMs)).append("\">\n")
        sb.append("<TotalTimeSeconds>").append(s.elapsedSec).append("</TotalTimeSeconds>\n")
        sb.append("<DistanceMeters>").append((s.distanceMiles * 1609.344).roundToInt()).append("</DistanceMeters>\n")
        sb.append("<MaximumSpeed>").append("%.2f".format(Locale.US, s.maxSpeedMph * 0.44704)).append("</MaximumSpeed>\n")
        sb.append("<Calories>").append(s.calories.roundToInt()).append("</Calories>\n")
        s.avgHr?.let { sb.append("<AverageHeartRateBpm><Value>").append(it).append("</Value></AverageHeartRateBpm>\n") }
        s.maxHr?.let { sb.append("<MaximumHeartRateBpm><Value>").append(it).append("</Value></MaximumHeartRateBpm>\n") }
        sb.append("<Intensity>Active</Intensity>\n")
        sb.append("<Cadence>").append(s.avgCadence.roundToInt()).append("</Cadence>\n")
        sb.append("<TriggerMethod>Manual</TriggerMethod>\n")
        sb.append("<Track>\n")
        var distM = 0.0
        for (p in ride.trace) {
            distM += p.speedMph * 0.44704 // 1 second per point
            sb.append("<Trackpoint><Time>").append(iso(ride.startedAtMs + p.t * 1000L)).append("</Time>")
            sb.append("<DistanceMeters>").append("%.1f".format(Locale.US, distM)).append("</DistanceMeters>")
            p.hr?.let { sb.append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
            sb.append("<Cadence>").append(p.cadence.roundToInt().coerceIn(0, 254)).append("</Cadence>")
            sb.append("<Extensions><ns3:TPX><ns3:Speed>")
                .append("%.2f".format(Locale.US, p.speedMph * 0.44704))
                .append("</ns3:Speed><ns3:Watts>").append(p.power.roundToInt()).append("</ns3:Watts></ns3:TPX></Extensions>")
            sb.append("</Trackpoint>\n")
        }
        sb.append("</Track>\n")
        sb.append("<Extensions><ns3:LX><ns3:AvgWatts>").append(s.avgPower.roundToInt())
            .append("</ns3:AvgWatts><ns3:MaxWatts>").append(s.maxPower.roundToInt()).append("</ns3:MaxWatts></ns3:LX></Extensions>\n")
        sb.append("</Lap>\n")
        sb.append("<Notes>").append(esc(ride.title)).append(" - recorded with BikeOS</Notes>\n")
        sb.append("<Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>BikeOS</Name>")
            .append("<UnitId>0</UnitId><ProductID>0</ProductID></Creator>\n")
        sb.append("</Activity></Activities>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }
}
