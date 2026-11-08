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

    /**
     * Codes that are never accepted as "set resistance": the read call and anything that is not a
     * plausible AIDL transaction number.
     */
    fun isValidResistanceCode(code: Int?, protocol: PelotonProtocol = PelotonProtocol()): Boolean =
        code != null && code in 1..64 && code != protocol.bikePlusGetBikeData

    /**
     * @param protocol the transaction numbers in use (defaults, or as read from Peloton's own code by
     *   the bike check). Only the specific named calls in it are allowed, never anything else.
     * @param userResistanceCode the Bike+ set-resistance code, if the rider enabled auto-resistance.
     */
    fun check(iface: String, code: Int, protocol: PelotonProtocol = PelotonProtocol(), userResistanceCode: Int? = null): Call? {
        val allowed = listOf(
            Call(PelotonService.V1_INTERFACE, protocol.v1RegisterCallback, Kind.REGISTER_LISTENER),
            Call(PelotonService.V1_INTERFACE, protocol.v1UnregisterCallback, Kind.REGISTER_LISTENER),
            Call(PelotonService.BIKE_PLUS_INTERFACE, protocol.bikePlusGetBikeData, Kind.READ_ONLY),
        )
        allowed.firstOrNull { it.iface == iface && it.code == code }?.let { return it }
        if (iface == PelotonService.BIKE_PLUS_INTERFACE && code == userResistanceCode && isValidResistanceCode(userResistanceCode, protocol)) {
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
