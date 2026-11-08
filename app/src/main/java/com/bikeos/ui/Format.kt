package com.bikeos.ui

import com.bikeos.core.metrics.SpeedModel
import com.bikeos.core.model.Units
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    fun clock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    fun speed(mph: Float, units: Units) = if (units == Units.METRIC) "%.1f".format(mph * SpeedModel.KM_PER_MILE) else "%.1f".format(mph)
    fun speedUnit(units: Units) = if (units == Units.METRIC) "km/h" else "mph"
    fun distance(miles: Double, units: Units) = if (units == Units.METRIC) "%.2f".format(miles * SpeedModel.KM_PER_MILE) else "%.2f".format(miles)
    fun distanceUnit(units: Units) = if (units == Units.METRIC) "km" else "mi"
    fun int(v: Float) = v.roundToInt().toString()
    fun int(v: Double) = v.roundToInt().toString()
    fun date(ms: Long): String = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(ms))
    fun time(ms: Long): String = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(ms))
}
