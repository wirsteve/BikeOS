package com.bikeos.resistance

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bikeos.core.peloton.PelotonCallPolicy
import com.bikeos.core.peloton.PelotonService
import com.bikeos.core.peloton.ResistanceLimiter
import com.bikeos.sensor.guardedTransact

/**
 * Drives the bike's resistance automatically, where the hardware allows it.
 *
 * The Peloton Bike has a manual knob: BikeOS shows a "turn to NN" prompt instead.
 * The Peloton Bike+ has a motorised magnet. Its service exposes a set-resistance call, but the
 * transaction number is not publicly documented and differs between firmware builds, so this
 * controller is OFF unless the user enters the transaction code for their firmware in Settings
 * (see README: "Bike+ auto-resistance").
 */
interface ResistanceController {
    val isAutomatic: Boolean
    /** True once the controller has given up after repeated failures. */
    val faulted: Boolean get() = false
    fun setTarget(target: Float, current: Float, cadence: Float, dataFresh: Boolean)
}

object ManualResistance : ResistanceController {
    override val isAutomatic = false
    override fun setTarget(target: Float, current: Float, cadence: Float, dataFresh: Boolean) = Unit
}

/**
 * Safety layers, outermost first:
 *  1. Only constructed on a real Bike+ (model check) with a code that passes [PelotonCallPolicy].
 *  2. [ResistanceLimiter]: user ceiling, ramped steps, ≤1 command/s, never harder when not pedaling
 *     or when sensor data is stale.
 *  3. [guardedTransact]: the call is re-checked against the allowlist at send time.
 *  4. Self-disables after 3 failures; nothing is retried in a loop.
 */
class BikePlusAutoResistance private constructor(
    private val binderProvider: () -> IBinder?,
    private val transactionCode: Int,
    maxResistance: Int,
) : ResistanceController {
    override val isAutomatic = true
    private val limiter = ResistanceLimiter(maxResistance = maxResistance)
    override val faulted: Boolean get() = limiter.disabled

    override fun setTarget(target: Float, current: Float, cadence: Float, dataFresh: Boolean) {
        val cmd = limiter.next(target, current, cadence, dataFresh, System.currentTimeMillis()) ?: return
        val b = binderProvider() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PelotonService.BIKE_PLUS_INTERFACE)
            data.writeInt(cmd)
            guardedTransact(b, PelotonService.BIKE_PLUS_INTERFACE, transactionCode, data, reply, userResistanceCode = transactionCode)
            reply.readException()
            limiter.reportSuccess()
        } catch (e: Exception) {
            limiter.reportFailure()
            Log.w("BikeOS.Resistance", "set resistance via tx $transactionCode failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        /** Returns a controller only when every precondition holds; otherwise manual prompts are used. */
        fun createIfSafe(binderProvider: () -> IBinder?, transactionCode: Int?, maxResistance: Int): ResistanceController {
            if (Build.MODEL != PelotonService.BIKE_PLUS_MODEL) return ManualResistance
            if (!PelotonCallPolicy.isValidResistanceCode(transactionCode)) return ManualResistance
            return BikePlusAutoResistance(binderProvider, transactionCode!!, maxResistance.coerceIn(10, 100))
        }
    }
}
