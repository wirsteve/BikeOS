package com.bikeos.settings

import android.content.Context
import android.content.SharedPreferences
import com.bikeos.core.metrics.Calories
import com.bikeos.core.metrics.CalorieConfig
import com.bikeos.core.metrics.ResistanceModel
import com.bikeos.core.metrics.TerrainMapping
import com.bikeos.core.model.Units
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

enum class SensorSourceChoice(val label: String) {
    AUTO("Automatic"),
    PELOTON_BIKE("Peloton Bike (V1 service)"),
    PELOTON_BIKE_PLUS("Peloton Bike+"),
    PELOTON_LEGACY("Peloton Bike (older OS)"),
    SIMULATOR("Simulator (demo / testing)"),
    NONE("None"),
}

data class Settings(
    val ftp: Int = 150,
    val units: Units = Units.IMPERIAL,
    val weightKg: Double = 75.0,
    val ageYears: Int = 35,
    val sex: Calories.Sex = Calories.Sex.MALE,
    val useHrCalories: Boolean = false,
    val autoStartRide: Boolean = true,
    val autoEndIdleMinutes: Int = 3,
    val overlayEnabled: Boolean = true,
    val overlayWhenIdle: Boolean = false,
    val overlayX: Int = 40,
    val overlayY: Int = 24,
    val overlayCompact: Boolean = false,
    val bleBroadcast: Boolean = true,
    val bleName: String = "BikeOS Bike",
    val hrDeviceAddress: String? = null,
    val hrDeviceName: String? = null,
    val sensorSource: SensorSourceChoice = SensorSourceChoice.AUTO,
    val autoResistance: Boolean = false,
    val autoResistanceTxCode: Int? = null,
    val autoResistanceMax: Int = 75,
    val bleRenameAdapter: Boolean = false,
    val dashboardEnabled: Boolean = false,
    val dashboardPort: Int = 8080,
    val terrainBase: Float = 30f,
    val terrainGain: Float = 3f,
    val followTrainerApps: Boolean = true,
    val pinnedApps: List<String> = emptyList(),
    val hiddenCatalogIds: Set<String> = emptySet(),
    val riderName: String = "",
) {
    val calorieConfig get() = CalorieConfig(useHrCalories, weightKg, ageYears, sex)
    val terrain get() = TerrainMapping(terrainBase, terrainGain)
}

const val CRASH_WINDOW_MS = 120_000L

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bikeos", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): Settings = with(prefs) {
        val d = Settings()
        Settings(
            ftp = getInt("ftp", d.ftp),
            units = runCatching { Units.valueOf(getString("units", d.units.name)!!) }.getOrDefault(d.units),
            weightKg = getFloat("weightKg", d.weightKg.toFloat()).toDouble(),
            ageYears = getInt("ageYears", d.ageYears),
            sex = runCatching { Calories.Sex.valueOf(getString("sex", d.sex.name)!!) }.getOrDefault(d.sex),
            useHrCalories = getBoolean("useHrCalories", d.useHrCalories),
            autoStartRide = getBoolean("autoStartRide", d.autoStartRide),
            autoEndIdleMinutes = getInt("autoEndIdleMinutes", d.autoEndIdleMinutes),
            overlayEnabled = getBoolean("overlayEnabled", d.overlayEnabled),
            overlayWhenIdle = getBoolean("overlayWhenIdle", d.overlayWhenIdle),
            overlayX = getInt("overlayX", d.overlayX),
            overlayY = getInt("overlayY", d.overlayY),
            overlayCompact = getBoolean("overlayCompact", d.overlayCompact),
            bleBroadcast = getBoolean("bleBroadcast", d.bleBroadcast),
            bleName = getString("bleName", d.bleName) ?: d.bleName,
            hrDeviceAddress = getString("hrDeviceAddress", null),
            hrDeviceName = getString("hrDeviceName", null),
            sensorSource = runCatching { SensorSourceChoice.valueOf(getString("sensorSource", d.sensorSource.name)!!) }
                .getOrDefault(d.sensorSource),
            autoResistance = getBoolean("autoResistance", d.autoResistance),
            autoResistanceTxCode = getInt("autoResistanceTxCode", -1).takeIf { it > 0 },
            autoResistanceMax = getInt("autoResistanceMax", d.autoResistanceMax),
            bleRenameAdapter = getBoolean("bleRenameAdapter", d.bleRenameAdapter),
            dashboardEnabled = getBoolean("dashboardEnabled", d.dashboardEnabled),
            dashboardPort = getInt("dashboardPort", d.dashboardPort),
            terrainBase = getFloat("terrainBase", d.terrainBase),
            terrainGain = getFloat("terrainGain", d.terrainGain),
            followTrainerApps = getBoolean("followTrainerApps", d.followTrainerApps),
            pinnedApps = getString("pinnedApps", "")!!.split(',').filter { it.isNotBlank() },
            hiddenCatalogIds = getString("hiddenCatalogIds", "")!!.split(',').filter { it.isNotBlank() }.toSet(),
            riderName = getString("riderName", "") ?: "",
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putInt("ftp", s.ftp)
            .putString("units", s.units.name)
            .putFloat("weightKg", s.weightKg.toFloat())
            .putInt("ageYears", s.ageYears)
            .putString("sex", s.sex.name)
            .putBoolean("useHrCalories", s.useHrCalories)
            .putBoolean("autoStartRide", s.autoStartRide)
            .putInt("autoEndIdleMinutes", s.autoEndIdleMinutes)
            .putBoolean("overlayEnabled", s.overlayEnabled)
            .putBoolean("overlayWhenIdle", s.overlayWhenIdle)
            .putInt("overlayX", s.overlayX)
            .putInt("overlayY", s.overlayY)
            .putBoolean("overlayCompact", s.overlayCompact)
            .putBoolean("bleBroadcast", s.bleBroadcast)
            .putString("bleName", s.bleName)
            .putString("hrDeviceAddress", s.hrDeviceAddress)
            .putString("hrDeviceName", s.hrDeviceName)
            .putString("sensorSource", s.sensorSource.name)
            .putBoolean("autoResistance", s.autoResistance)
            .putInt("autoResistanceTxCode", s.autoResistanceTxCode ?: -1)
            .putInt("autoResistanceMax", s.autoResistanceMax)
            .putBoolean("bleRenameAdapter", s.bleRenameAdapter)
            .putBoolean("dashboardEnabled", s.dashboardEnabled)
            .putInt("dashboardPort", s.dashboardPort)
            .putFloat("terrainBase", s.terrainBase)
            .putFloat("terrainGain", s.terrainGain)
            .putBoolean("followTrainerApps", s.followTrainerApps)
            .putString("pinnedApps", s.pinnedApps.joinToString(","))
            .putString("hiddenCatalogIds", s.hiddenCatalogIds.joinToString(","))
            .putString("riderName", s.riderName)
            .apply()
    }

    // The learned resistance->power curve is stored separately; it changes often.
    fun loadResistanceModel(): ResistanceModel = ResistanceModel().also { m ->
        prefs.getString("resistanceModel", null)?.let { text ->
            runCatching { Json.decodeFromString(ResistanceModel.Snapshot.serializer(), text) }.getOrNull()?.let(m::restore)
        }
    }

    fun saveResistanceModel(m: ResistanceModel) {
        prefs.edit().putString("resistanceModel", Json.encodeToString(ResistanceModel.Snapshot.serializer(), m.snapshot())).apply()
    }

    /** The tablet's own Bluetooth name before BikeOS ever changed it, so it can always be restored. */
    var originalBluetoothName: String?
        get() = prefs.getString("originalBluetoothName", null)
        set(v) { prefs.edit().putString("originalBluetoothName", v).commit() }

    /** Crash timestamps for the safe-mode guard. Uses commit() because the process is about to die. */
    fun recordCrash(now: Long) {
        val recent = crashTimes().filter { now - it < CRASH_WINDOW_MS } + now
        prefs.edit().putString("crashTimes", recent.joinToString(",")).commit()
    }

    fun crashTimes(): List<Long> = prefs.getString("crashTimes", "")!!.split(',').mapNotNull { it.toLongOrNull() }

    fun clearCrashes() { prefs.edit().remove("crashTimes").apply() }

    /** Restore every setting to its default (keeps ride history). */
    fun resetAll() {
        val keepName = originalBluetoothName
        prefs.edit().clear().commit()
        originalBluetoothName = keepName
        _state.value = Settings()
    }

    fun resetResistanceModel() {
        prefs.edit().remove("resistanceModel").apply()
    }
}
