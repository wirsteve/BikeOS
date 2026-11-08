package com.bikeos.core.peloton

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The ONLY calls BikeOS is ever allowed to make into Peloton's system service.
 *
 * Peloton's service also exposes calibration, firmware and factory functions on the same
 * interfaces. Calling one of those by accident could leave the bike needing a recalibration
 * (or worse), so every Binder transaction goes through this allowlist first. Anything not listed
 * is refused before it leaves BikeOS.
 */
object PelotonCallPolicy {
    enum class Kind { READ_ONLY, REGISTER_LISTENER, MOTOR_WRITE }

    data class Call(val iface: String, val code: Int, val kind: Kind)

    private val fixed = listOf(
        Call(PelotonService.V1_INTERFACE, PelotonService.V1_TX_REGISTER_CALLBACK, Kind.REGISTER_LISTENER),
        Call(PelotonService.V1_INTERFACE, PelotonService.V1_TX_UNREGISTER_CALLBACK, Kind.REGISTER_LISTENER),
        Call(PelotonService.BIKE_PLUS_INTERFACE, PelotonService.BIKE_PLUS_TX_GET_BIKE_DATA, Kind.READ_ONLY),
    )

    /** Codes that are known to be something other than "set resistance", and so never accepted as one. */
    private val reservedBikePlusCodes = setOf(PelotonService.BIKE_PLUS_TX_GET_BIKE_DATA)

    /**
     * A user-supplied Bike+ resistance code is accepted only on the Bike+ interface, must be a plausible
     * AIDL transaction number (1..64), and must not collide with a known read call.
     */
    fun isValidResistanceCode(code: Int?): Boolean = code != null && code in 1..64 && code !in reservedBikePlusCodes

    fun check(iface: String, code: Int, userResistanceCode: Int? = null): Call? {
        fixed.firstOrNull { it.iface == iface && it.code == code }?.let { return it }
        if (iface == PelotonService.BIKE_PLUS_INTERFACE && isValidResistanceCode(userResistanceCode) && code == userResistanceCode) {
            return Call(iface, code, Kind.MOTOR_WRITE)
        }
        return null
    }
}

class BlockedCallException(iface: String, code: Int) :
    SecurityException("BikeOS safety: refused Peloton call $iface#$code (not on the allowlist)")

/**
 * Safety envelope for automatic (motorised) resistance on the Bike+.
 *
 *  - Never above [maxResistance] (user setting, default 75).
 *  - Changes at most [maxStepPerCommand] points per command and one command per [minIntervalMs],
 *    so the magnet ramps instead of jumping.
 *  - Only increases while the rider is actually pedaling (cadence ≥ 30) and data is fresh;
 *    when they stop, or the sensor goes quiet, it only ever lowers resistance.
 *  - After a run of failed commands it disables itself until re-armed.
 */
class ResistanceLimiter(
    val maxResistance: Int = 75,
    private val maxStepPerCommand: Int = 10,
    private val minIntervalMs: Long = 1_000,
    private val maxFailures: Int = 3,
) {
    private var lastCommand: Int? = null
    private var lastAt = Long.MIN_VALUE / 2
    private var failures = 0

    val disabled: Boolean get() = failures >= maxFailures

    /**
     * @return the resistance (0-100) to command now, or null to send nothing.
     */
    fun next(target: Float, current: Float, cadence: Float, dataFresh: Boolean, nowMs: Long): Int? {
        if (disabled) return null
        if (nowMs - lastAt < minIntervalMs) return null
        val cur = current.roundToInt().coerceIn(0, 100)
        var want = target.roundToInt().coerceIn(0, maxResistance)
        val riding = dataFresh && cadence >= 30f
        if (!riding && want > cur) want = cur // never make it harder for someone who isn't pedaling
        val base = lastCommand ?: cur
        if (abs(want - base) < 1) return null
        val step = (want - base).coerceIn(-maxStepPerCommand, maxStepPerCommand)
        val cmd = (base + step).coerceIn(0, maxResistance)
        if (cmd == lastCommand) return null
        lastCommand = cmd
        lastAt = nowMs
        return cmd
    }

    fun reportSuccess() { failures = 0 }
    fun reportFailure() { failures++ }
    fun rearm() { failures = 0; lastCommand = null }
}
