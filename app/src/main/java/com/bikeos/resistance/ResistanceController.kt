package com.bikeos.resistance

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bikeos.core.peloton.PelotonService
import kotlin.math.abs
import kotlin.math.roundToInt

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
    fun setTarget(resistance: Float)
}

object ManualResistance : ResistanceController {
    override val isAutomatic = false
    override fun setTarget(resistance: Float) = Unit
}

class BikePlusAutoResistance(
    private val binderProvider: () -> IBinder?,
    private val transactionCode: Int,
) : ResistanceController {
    override val isAutomatic = true
    private var lastSent: Int? = null
    private var lastSentAt = 0L
    private var failures = 0

    override fun setTarget(resistance: Float) {
        if (failures >= 3) return // stop trying on firmware that rejects the call
        val target = resistance.roundToInt().coerceIn(0, 100)
        val now = System.currentTimeMillis()
        val prev = lastSent
        if (prev != null && abs(prev - target) < 1) return
        if (now - lastSentAt < 1_000) return // the motor needs time to move
        val b = binderProvider() ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PelotonService.BIKE_PLUS_INTERFACE)
            data.writeInt(target)
            b.transact(transactionCode, data, reply, 0)
            reply.readException()
            lastSent = target
            lastSentAt = now
            failures = 0
        } catch (e: Exception) {
            failures++
            Log.w("BikeOS.Resistance", "set resistance via tx $transactionCode failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
