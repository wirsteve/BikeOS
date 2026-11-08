package com.bikeos.core.ble

import java.util.UUID
import kotlin.math.roundToInt

/** Bluetooth SIG assigned numbers used by BikeOS. */
object GattIds {
    private fun uuid16(short: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

    // Fitness Machine Service (FTMS) - Zwift, Rouvy, MyWhoosh, Kinomap, TrainerRoad...
    val FTMS_SERVICE = uuid16(0x1826)
    val FTMS_FEATURE = uuid16(0x2ACC)
    val INDOOR_BIKE_DATA = uuid16(0x2AD2)
    val TRAINING_STATUS = uuid16(0x2AD3)
    val SUPPORTED_RESISTANCE_RANGE = uuid16(0x2AD6)
    val SUPPORTED_POWER_RANGE = uuid16(0x2AD8)
    val FTMS_CONTROL_POINT = uuid16(0x2AD9)
    val FTMS_STATUS = uuid16(0x2ADA)

    // Cycling Power Service (CPS) - Apple Watch (watchOS 10+), Garmin, Wahoo, Strava...
    val CPS_SERVICE = uuid16(0x1818)
    val CP_MEASUREMENT = uuid16(0x2A63)
    val CP_FEATURE = uuid16(0x2A65)
    val SENSOR_LOCATION = uuid16(0x2A5D)

    // Heart Rate Service - consumed from chest straps / arm bands
    val HR_SERVICE = uuid16(0x180D)
    val HR_MEASUREMENT = uuid16(0x2A37)

    // Device information
    val DIS_SERVICE = uuid16(0x180A)
    val MANUFACTURER_NAME = uuid16(0x2A29)
    val MODEL_NUMBER = uuid16(0x2A24)

    val CCCD = uuid16(0x2902)
}

private class ByteWriter {
    private val out = java.io.ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun s16(v: Int) = u16(v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
    fun u24(v: Int) = apply { u8(v); u8(v shr 8); u8(v shr 16) }
    fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
    fun bytes(): ByteArray = out.toByteArray()
}

data class IndoorBikeFrame(
    val speedKmh: Float,
    val cadenceRpm: Float,
    val totalDistanceM: Int,
    val resistanceLevel: Float,
    val powerW: Int,
    val totalEnergyKcal: Int,
    val heartRate: Int?,
    val elapsedSec: Int,
)

/** Encoders for the characteristics BikeOS publishes as a BLE peripheral. */
object FtmsCodec {
    // Fitness Machine Feature bits
    private const val F_CADENCE = 1 shl 1
    private const val F_TOTAL_DISTANCE = 1 shl 2
    private const val F_RESISTANCE_LEVEL = 1 shl 7
    private const val F_EXPENDED_ENERGY = 1 shl 9
    private const val F_HEART_RATE = 1 shl 10
    private const val F_ELAPSED_TIME = 1 shl 12
    private const val F_POWER = 1 shl 14

    // Target setting feature bits
    private const val T_RESISTANCE = 1 shl 2
    private const val T_POWER = 1 shl 3
    private const val T_SIMULATION = 1 shl 13

    fun feature(): ByteArray = ByteWriter()
        .u32((F_CADENCE or F_TOTAL_DISTANCE or F_RESISTANCE_LEVEL or F_EXPENDED_ENERGY or F_HEART_RATE or F_ELAPSED_TIME or F_POWER).toLong())
        .u32((T_RESISTANCE or T_POWER or T_SIMULATION).toLong())
        .bytes()

    /** Resistance range 0-100 in steps of 1 (resolution 0.1 on the wire). */
    fun supportedResistanceRange(): ByteArray = ByteWriter().s16(0).s16(1000).u16(10).bytes()

    fun supportedPowerRange(): ByteArray = ByteWriter().s16(0).s16(2000).u16(1).bytes()

    /**
     * Indoor Bike Data (0x2AD2). Flag bit 0 ("More Data") is 0, which means instantaneous speed
     * IS present.
     */
    fun indoorBikeData(f: IndoorBikeFrame): ByteArray {
        var flags = 0
        flags = flags or (1 shl 2) // instantaneous cadence
        flags = flags or (1 shl 4) // total distance
        flags = flags or (1 shl 5) // resistance level
        flags = flags or (1 shl 6) // instantaneous power
        flags = flags or (1 shl 8) // expended energy
        if (f.heartRate != null) flags = flags or (1 shl 9)
        flags = flags or (1 shl 11) // elapsed time
        val w = ByteWriter().u16(flags)
        w.u16((f.speedKmh * 100).roundToInt().coerceIn(0, 0xFFFF))
        w.u16((f.cadenceRpm * 2).roundToInt().coerceIn(0, 0xFFFF)) // 0.5 rpm resolution
        w.u24(f.totalDistanceM.coerceIn(0, 0xFFFFFF))
        w.s16(f.resistanceLevel.roundToInt()) // unitless, resolution 1
        w.s16(f.powerW)
        w.u16(f.totalEnergyKcal.coerceIn(0, 0xFFFE)) // total energy
        w.u16(0xFFFF) // energy per hour: not available
        w.u8(0xFF) // energy per minute: not available
        f.heartRate?.let { w.u8(it.coerceIn(0, 255)) }
        w.u16(f.elapsedSec.coerceIn(0, 0xFFFF))
        return w.bytes()
    }

    /** FTMS service data for advertising: machine available + indoor bike supported. */
    fun advertisingServiceData(): ByteArray = ByteWriter().u8(0x01).u16(1 shl 5).bytes()

    // ---- Control point ----
    sealed class ControlRequest(val opCode: Int) {
        object RequestControl : ControlRequest(0x00)
        object Reset : ControlRequest(0x01)
        data class SetTargetResistance(val level: Float) : ControlRequest(0x04)
        data class SetTargetPower(val watts: Int) : ControlRequest(0x05)
        object StartOrResume : ControlRequest(0x07)
        data class StopOrPause(val pause: Boolean) : ControlRequest(0x08)
        data class SetSimulation(val windMps: Float, val gradePct: Float, val crr: Float, val cw: Float) : ControlRequest(0x11)
        data class Unsupported(val code: Int) : ControlRequest(code)
        data class Malformed(val code: Int) : ControlRequest(code)
    }

    const val RESULT_SUCCESS = 0x01
    const val RESULT_NOT_SUPPORTED = 0x02
    const val RESULT_INVALID_PARAMETER = 0x03
    const val RESULT_FAILED = 0x04
    const val RESULT_CONTROL_NOT_PERMITTED = 0x05

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.s16(i: Int) = u16(i).toShort().toInt()

    fun parseControlPoint(data: ByteArray): ControlRequest {
        if (data.isEmpty()) return ControlRequest.Malformed(-1)
        val op = data.u8(0)
        return try {
            when (op) {
                0x00 -> ControlRequest.RequestControl
                0x01 -> ControlRequest.Reset
                // FTMS 1.0: uint8 resolution 0.1. Some apps send sint16; accept both.
                0x04 -> ControlRequest.SetTargetResistance(
                    if (data.size >= 3) data.s16(1) / 10f else data.u8(1) / 10f,
                )
                0x05 -> ControlRequest.SetTargetPower(data.s16(1))
                0x07 -> ControlRequest.StartOrResume
                0x08 -> ControlRequest.StopOrPause(data.size < 2 || data.u8(1) == 0x02)
                0x11 -> ControlRequest.SetSimulation(
                    windMps = data.s16(1) / 1000f,
                    gradePct = data.s16(3) / 100f,
                    crr = data.u8(5) / 10000f,
                    cw = data.u8(6) / 100f,
                )
                else -> ControlRequest.Unsupported(op)
            }
        } catch (_: IndexOutOfBoundsException) {
            ControlRequest.Malformed(op)
        }
    }

    fun controlResponse(requestOp: Int, result: Int): ByteArray = byteArrayOf(0x80.toByte(), requestOp.toByte(), result.toByte())

    /** Fitness Machine Status notifications for state changes. */
    fun statusTargetPowerChanged(watts: Int) = ByteWriter().u8(0x08).s16(watts).bytes()
    fun statusTargetResistanceChanged(level: Float) = ByteWriter().u8(0x07).u8((level * 10).roundToInt().coerceIn(0, 255)).bytes()
    fun statusSimulationChanged(windMps: Float, gradePct: Float, crr: Float, cw: Float) = ByteWriter().u8(0x12)
        .s16((windMps * 1000).roundToInt()).s16((gradePct * 100).roundToInt())
        .u8((crr * 10000).roundToInt()).u8((cw * 100).roundToInt()).bytes()
    fun statusStarted() = byteArrayOf(0x04)
    fun statusStopped(pause: Boolean) = byteArrayOf(0x02, if (pause) 0x02 else 0x01)
    fun statusReset() = byteArrayOf(0x01)
}

/**
 * Cycling Power Service encoder. Includes crank revolution data, which lets Apple Watch,
 * Garmin and others derive cadence from the same sensor.
 */
class CyclingPowerEncoder {
    private var crankRevs = 0.0
    private var lastCrankEventTime1024 = 0
    private var cumulativeRevs = 0
    private var lastUpdateMs: Long? = null

    fun feature(): ByteArray = ByteWriter().u32(1L shl 3).bytes() // crank revolution data supported

    /** Sensor location: 0 = Other. */
    fun sensorLocation(): ByteArray = byteArrayOf(0)

    fun measurement(powerW: Int, cadenceRpm: Float, nowMs: Long): ByteArray {
        val prev = lastUpdateMs
        lastUpdateMs = nowMs
        if (prev != null && cadenceRpm > 0f) {
            val dtSec = ((nowMs - prev) / 1000.0).coerceIn(0.0, 2.0)
            val before = crankRevs
            crankRevs += cadenceRpm / 60.0 * dtSec
            val whole = crankRevs.toInt()
            if (whole > before.toInt()) {
                // Time of the most recent whole revolution, interpolated inside this interval.
                val revsSinceWhole = crankRevs - whole
                val secSinceWhole = revsSinceWhole / (cadenceRpm / 60.0)
                val eventMs = nowMs - (secSinceWhole * 1000).toLong()
                cumulativeRevs = whole
                lastCrankEventTime1024 = ((eventMs * 1024) / 1000 % 65536).toInt()
            }
        }
        val flags = 1 shl 5 // crank revolution data present
        return ByteWriter().u16(flags).s16(powerW).u16(cumulativeRevs and 0xFFFF).u16(lastCrankEventTime1024).bytes()
    }
}

object HeartRateCodec {
    /** Parse a Heart Rate Measurement (0x2A37) notification. Returns bpm or null. */
    fun parse(data: ByteArray): Int? {
        if (data.size < 2) return null
        val flags = data[0].toInt()
        val is16 = flags and 0x01 != 0
        return if (is16) {
            if (data.size < 3) null else (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            data[1].toInt() and 0xFF
        }
    }
}
