package com.bikeos

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bikeos.apps.AppCatalog
import com.bikeos.ble.BikePeripheral
import com.bikeos.ble.HeartRateClient
import com.bikeos.core.metrics.SpeedModel
import com.bikeos.core.model.Units
import com.bikeos.core.peloton.PelotonService
import com.bikeos.core.remote.DashboardServer
import com.bikeos.core.remote.LiveSnapshot
import com.bikeos.core.workout.WorkoutLibrary
import com.bikeos.data.RideRepository
import com.bikeos.data.WorkoutRepository
import com.bikeos.ride.RidePhase
import com.bikeos.ride.RideController
import com.bikeos.ride.RideService
import com.bikeos.sensor.SensorHub
import com.bikeos.settings.CRASH_WINDOW_MS
import com.bikeos.settings.SettingsStore
import com.bikeos.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

/** Status of the Peloton software BikeOS depends on but never modifies. */
data class PelotonHealth(
    val isPeloton: Boolean,
    val pelotonAppInstalled: Boolean,
    val pelotonAppEnabled: Boolean,
    val sensorServiceInstalled: Boolean,
    val sensorServiceEnabled: Boolean,
) {
    val ok: Boolean get() = !isPeloton || (pelotonAppInstalled && pelotonAppEnabled && sensorServiceInstalled && sensorServiceEnabled)
}

/** Process-wide singletons. BikeOS is the home screen, so the process lives as long as the bike is on. */
class AppGraph(val context: Context, val safeMode: Boolean) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val heartRate = HeartRateClient(context)
    val sensors = SensorHub(context, scope, settings.loadResistanceModel(), heartRate.bpm)
    val peripheral = BikePeripheral(context)
    val rides = RideRepository(context)
    val workouts = WorkoutRepository(context)
    val catalog = AppCatalog(context)
    val ride = RideController(scope, settings, sensors, heartRate.bpm, peripheral, rides)
    private var dashboard: DashboardServer? = null

    /** Wall-clock tick every 2 s for screens that poll system state (clock, permissions, installed apps). */
    val clock: kotlinx.coroutines.flow.StateFlow<Long> = kotlinx.coroutines.flow.flow {
        while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(2_000) }
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    fun start() {
        scope.launch { rides.refresh() }
        scope.launch { workouts.refresh() }
        healBluetoothName()
        if (safeMode) {
            // Safe mode: show the home screen and nothing else. No sensors, Bluetooth or motor control.
            Log.w("BikeOS", "Starting in SAFE MODE after repeated crashes")
            return
        }
        scope.launch {
            // Inspect Peloton's code first (read-only, takes a second), so the right interface and
            // transaction numbers for this firmware are used from the very first call.
            sensors.runBikeCheck()
            settings.state.distinctUntilChangedBy { it.sensorSource }.collect { sensors.start(it.sensorSource) }
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.hrDeviceAddress }.collect { s ->
                s.hrDeviceAddress?.let(heartRate::connect) ?: heartRate.disconnect()
            }
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.dashboardEnabled to it.dashboardPort }.collect { s ->
                dashboard?.stop()
                dashboard = null
                if (s.dashboardEnabled) {
                    dashboard = DashboardServer(s.dashboardPort, ::liveSnapshot).also { srv ->
                        runCatching { srv.start() }.onFailure { Log.w("BikeOS", "dashboard failed to start", it) }
                    }
                }
            }
        }
        restartBluetoothBroadcast()
        ride.start()
        // Running cleanly for the crash window resets the safe-mode counter.
        scope.launch { kotlinx.coroutines.delay(CRASH_WINDOW_MS); settings.clearCrashes() }
    }

    /** Called at start and whenever Bluetooth permission is granted or the setting changes. */
    fun restartBluetoothBroadcast() {
        if (safeMode) return
        peripheral.stop()
        val s = settings.current
        if (s.bleBroadcast && Permissions.hasBluetooth(context)) {
            runCatching {
                peripheral.start(renameTo = s.bleName.takeIf { s.bleRenameAdapter }) { original ->
                    if (settings.originalBluetoothName == null) settings.originalBluetoothName = original
                }
            }
        }
        if (!s.bleRenameAdapter) healBluetoothName()
        if (Permissions.hasBluetooth(context)) s.hrDeviceAddress?.let(heartRate::connect)
    }

    /** If BikeOS renamed the tablet's Bluetooth earlier and renaming is now off, put the original name back. */
    fun healBluetoothName() {
        val original = settings.originalBluetoothName ?: return
        if (settings.current.bleRenameAdapter) return
        if (peripheral.advertisedName != original) peripheral.restoreName(original)
    }

    fun ensureServiceRunning() {
        if (safeMode) return
        ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
    }

    fun pelotonHealth(): PelotonHealth {
        val pm = context.packageManager
        fun state(pkg: String): Pair<Boolean, Boolean> = try {
            val info = pm.getApplicationInfo(pkg, 0)
            true to info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false to false
        }
        val (appIn, appOn) = state(PelotonService.PELOTON_APP_PACKAGE)
        val (svcIn, svcOn) = state(PelotonService.AFFERNET_PACKAGE)
        return PelotonHealth(sensors.isPeloton, appIn, appOn, svcIn, svcOn)
    }

    /** Hands the screen back to Peloton's own app (it stays installed and untouched). */
    fun openPeloton(): Boolean = catalog.launchPackage(PelotonService.PELOTON_APP_PACKAGE)

    val dashboardUrl: String?
        get() {
            val s = settings.current
            if (!s.dashboardEnabled) return null
            return localIpv4()?.let { "http://$it:${s.dashboardPort}" }
        }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private fun liveSnapshot(): LiveSnapshot {
        val st = ride.state.value
        val s = settings.current
        val sample = st.sample
        val seg = st.progress?.segment?.takeIf { st.workout?.id != WorkoutLibrary.justRide.id }
        return LiveSnapshot(
            riding = st.active,
            paused = st.phase == RidePhase.PAUSED,
            workout = st.workout?.title,
            segment = seg?.name,
            segmentRemainingSec = st.progress?.segmentRemainingSec?.takeIf { seg != null },
            elapsedSec = st.stats.elapsedSec,
            power = sample.powerWatts.roundToInt(),
            zone = st.zone.number,
            cadence = sample.cadenceRpm.roundToInt(),
            resistance = sample.resistancePct.roundToInt(),
            heartRate = sample.heartRateBpm,
            speed = if (s.units == Units.METRIC) sample.speedMph * SpeedModel.KM_PER_MILE else sample.speedMph,
            speedUnit = Format.speedUnit(s.units),
            distance = (if (s.units == Units.METRIC) st.stats.distanceMiles * SpeedModel.KM_PER_MILE else st.stats.distanceMiles).toFloat(),
            distanceUnit = Format.distanceUnit(s.units),
            outputKj = st.stats.workKj.roundToInt(),
            calories = st.stats.calories.roundToInt(),
            targetCadence = seg?.cadence?.let { "$it rpm" },
            targetResistance = seg?.resistance?.toString(),
            targetPower = st.evaluation?.powerBand?.let { "${it.first}-${it.last} W" },
            suggestion = st.suggestion?.let { sug ->
                val diff = sug.resistance - sample.resistancePct
                if (!sug.automatic && kotlin.math.abs(diff) < 3f) null
                else "${if (diff > 0) "▲" else "▼"} resistance ${sug.resistance.roundToInt()} · ${sug.reason}"
            },
        )
    }
}

class BikeOSApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val store = SettingsStore(this)
        val now = System.currentTimeMillis()
        // Three crashes within two minutes => safe mode, so a bug can never trap the bike in a crash loop.
        val safe = store.crashTimes().count { now - it < CRASH_WINDOW_MS } >= 3
        installCrashGuard(store)
        graph = AppGraph(this, safe)
        graph.start()
    }

    private fun installCrashGuard(store: SettingsStore) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { store.recordCrash(System.currentTimeMillis()) }
            // Hand the screen back to Peloton so the rider is never stuck on a crashing launcher.
            runCatching {
                packageManager.getLaunchIntentForPackage(PelotonService.PELOTON_APP_PACKAGE)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(::startActivity)
            }
            previous?.uncaughtException(t, e)
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as BikeOSApp).graph
