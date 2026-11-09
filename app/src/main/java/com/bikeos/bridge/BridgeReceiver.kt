package com.bikeos.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bikeos.core.control.BridgeCommand
import com.bikeos.graph

/**
 * BikeOS Bridge, control side. Other apps on the bike send
 * `com.bikeos.bridge.CONTROL` broadcasts; Android only delivers them if the sending app holds the
 * `com.bikeos.permission.CONTROL_BIKE` permission, which the rider must approve. Commands are
 * range-checked and then go through exactly the same path (and Bike+ safety limiter) as Zwift's.
 */
class BridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BridgeCommand.ACTION_CONTROL) return
        val g = context.graph
        if (!g.settings.current.bridgeEnabled) {
            Log.i("BikeOS.Bridge", "ignored: Bridge disabled in Settings")
            return
        }
        val value = if (intent.hasExtra(BridgeCommand.EXTRA_VALUE)) {
            intent.extras?.get(BridgeCommand.EXTRA_VALUE)?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }
        } else null
        val cmd = BridgeCommand.parse(intent.getStringExtra(BridgeCommand.EXTRA_COMMAND), value)
        if (cmd == null) {
            Log.w("BikeOS.Bridge", "rejected command=${intent.getStringExtra(BridgeCommand.EXTRA_COMMAND)} value=$value")
            return
        }
        g.ride.applyBridge(cmd, intent.getStringExtra(BridgeCommand.EXTRA_APP)?.take(40) ?: "App")
    }
}
