package com.bikeos

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {
    const val REQUEST_CODE = 42

    /** Runtime permissions needed for BLE (scan for HR straps, act as a sensor). */
    // BikeOS targets API 28 (the Peloton tablets run Android 7-10), so the legacy BLUETOOTH
    // permissions apply on every OS version and only location needs a runtime grant for scanning.
    fun bluetoothPermissions(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBluetooth(ctx: Context) = bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requestBluetooth(activity: Activity) = activity.requestPermissions(bluetoothPermissions(), REQUEST_CODE)

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    /**
     * Opens the "draw over other apps" screen. Peloton hides Android Settings, so if this fails
     * the README shows the one-line adb command that grants it instead.
     */
    fun requestOverlay(activity: Activity): Boolean = runCatching {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
    }.isSuccess

    const val ADB_OVERLAY = "adb shell appops set com.bikeos SYSTEM_ALERT_WINDOW allow"
    const val ADB_HOME = "adb shell cmd package set-home-activity com.bikeos/.ui.HomeActivity"
}
