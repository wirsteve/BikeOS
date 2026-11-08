package com.bikeos

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bikeos.apps.AppCatalog
import com.bikeos.ble.BikePeripheral
import com.bikeos.ble.HeartRateClient
import com.bikeos.data.RideRepository
import com.bikeos.data.WorkoutRepository
import com.bikeos.ride.RideController
import com.bikeos.ride.RideService
import com.bikeos.sensor.SensorHub
import com.bikeos.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Process-wide singletons. BikeOS is the home screen, so the process lives as long as the bike is on. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val heartRate = HeartRateClient(context)
    val sensors = SensorHub(context, scope, settings.loadResistanceModel(), heartRate.bpm)
    val peripheral = BikePeripheral(context)
    val rides = RideRepository(context)
    val workouts = WorkoutRepository(context)
    val catalog = AppCatalog(context)
    val ride = RideController(scope, settings, sensors, heartRate.bpm, peripheral, rides)

    fun start() {
        scope.launch { rides.refresh() }
        scope.launch { workouts.refresh() }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.sensorSource }.collect { sensors.start(it.sensorSource) }
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.hrDeviceAddress }.collect { s ->
                s.hrDeviceAddress?.let(heartRate::connect) ?: heartRate.disconnect()
            }
        }
        restartBluetoothBroadcast()
        ride.start()
    }

    /** Called at start and whenever Bluetooth permission is granted or the setting changes. */
    fun restartBluetoothBroadcast() {
        peripheral.stop()
        val s = settings.current
        if (s.bleBroadcast && Permissions.hasBluetooth(context)) runCatching { peripheral.start(s.bleName) }
        if (Permissions.hasBluetooth(context)) s.hrDeviceAddress?.let(heartRate::connect)
    }

    fun ensureServiceRunning() {
        ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
    }
}

class BikeOSApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }
}

val Context.graph: AppGraph get() = (applicationContext as BikeOSApp).graph
