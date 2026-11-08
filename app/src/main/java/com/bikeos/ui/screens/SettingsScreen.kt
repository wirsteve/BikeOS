package com.bikeos.ui.screens

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.BuildConfig
import com.bikeos.Permissions
import com.bikeos.core.metrics.Calories
import com.bikeos.core.metrics.ResistanceModel
import com.bikeos.core.peloton.PelotonCallPolicy
import com.bikeos.core.model.Units
import com.bikeos.graph
import com.bikeos.settings.SensorSourceChoice
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Card
import com.bikeos.ui.components.SectionTitle
import com.bikeos.ui.theme.BikeColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settings.state.collectAsStateWithLifecycle()
    val sensor by g.sensors.status.collectAsStateWithLifecycle()
    val ble by g.peripheral.status.collectAsStateWithLifecycle()
    val hrState by g.heartRate.state.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    val update = g.settings::update

    ScreenScaffold("Settings", nav) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("Rider") {
                    SettingRow("FTP (functional threshold power)", "Used for power zones. Take the FTP test to measure it.") {
                        Stepper("${s.ftp} W", { update { it.copy(ftp = (it.ftp - 5).coerceAtLeast(50)) } }, { update { it.copy(ftp = (it.ftp + 5).coerceAtMost(600)) } })
                    }
                    SettingRow("Weight") {
                        val lbs = s.units == Units.IMPERIAL
                        val shown = if (lbs) "${(s.weightKg * 2.20462).roundToInt()} lb" else "${s.weightKg.roundToInt()} kg"
                        Stepper(shown, { update { it.copy(weightKg = (it.weightKg - if (lbs) 0.4536 else 1.0).coerceAtLeast(30.0)) } },
                            { update { it.copy(weightKg = (it.weightKg + if (lbs) 0.4536 else 1.0).coerceAtMost(250.0)) } })
                    }
                    SettingRow("Age") { Stepper("${s.ageYears}", { update { it.copy(ageYears = (it.ageYears - 1).coerceAtLeast(10)) } }, { update { it.copy(ageYears = (it.ageYears + 1).coerceAtMost(100)) } }) }
                    SettingRow("Sex (for heart-rate calories)") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Calories.Sex.entries.forEach { x -> FilterChip(s.sex == x, { update { it.copy(sex = x) } }, { Text(x.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
                        }
                    }
                    SettingRow("Units") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(s.units == Units.IMPERIAL, { update { it.copy(units = Units.IMPERIAL) } }, { Text("mi / lb") })
                            FilterChip(s.units == Units.METRIC, { update { it.copy(units = Units.METRIC) } }, { Text("km / kg") })
                        }
                    }
                    ToggleRow("Heart-rate based calories", "Uses your HR strap instead of power output. Power-based is usually more accurate on a bike.", s.useHrCalories) { v -> update { it.copy(useHrCalories = v) } }
                }
                Section("Rides") {
                    ToggleRow("Auto-start rides", "Begin recording after 3 seconds of pedaling, in any app.", s.autoStartRide) { v -> update { it.copy(autoStartRide = v) } }
                    SettingRow("Auto-end after idle", "Free rides end and save; workouts pause.") {
                        Stepper("${s.autoEndIdleMinutes} min", { update { it.copy(autoEndIdleMinutes = (it.autoEndIdleMinutes - 1).coerceAtLeast(1)) } }, { update { it.copy(autoEndIdleMinutes = (it.autoEndIdleMinutes + 1).coerceAtMost(30)) } })
                    }
                }
                Section("Overlay (stats on top of other apps)") {
                    val activity = ctx as? Activity
                    var tick by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
                    val allowed = remember(tick) { Permissions.canDrawOverlays(ctx) }
                    SettingRow("Permission", if (allowed) "Granted" else "Not granted. If the button does nothing on your bike, run: ${Permissions.ADB_OVERLAY}") {
                        if (!allowed && activity != null) Button(onClick = { Permissions.requestOverlay(activity) }) { Text("Allow") }
                    }
                    ToggleRow("Show overlay during rides", null, s.overlayEnabled) { v -> update { it.copy(overlayEnabled = v) } }
                    ToggleRow("Show overlay even when not riding", null, s.overlayWhenIdle) { v -> update { it.copy(overlayWhenIdle = v) } }
                    ToggleRow("Compact overlay", null, s.overlayCompact) { v -> update { it.copy(overlayCompact = v) } }
                }
                Section("Home screen") {
                    SettingRow("Make BikeOS the home screen", "Press Home and choose BikeOS → Always. If no chooser appears, run:\n${Permissions.ADB_HOME}") {}
                    SettingRow("Show these tiles") {}
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        g.catalog.entries.forEach { app ->
                            val shown = app.id !in s.hiddenCatalogIds
                            FilterChip(shown, { update { it.copy(hiddenCatalogIds = if (shown) it.hiddenCatalogIds + app.id else it.hiddenCatalogIds - app.id) } }, { Text(app.label) })
                        }
                    }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("Safety & Peloton") {
                    val health = remember { g.pelotonHealth() }
                    Text(
                        "BikeOS never modifies, disables or updates Peloton's software. It only reads sensor data through Peloton's own " +
                            "service, using an allowlist of read-only calls. Uninstalling BikeOS returns the bike to exactly how it was.",
                        color = BikeColors.TextDim,
                    )
                    if (health.isPeloton) {
                        Text(
                            (if (health.ok) "✓ " else "⚠ ") +
                                "Peloton app: ${if (!health.pelotonAppInstalled) "missing" else if (health.pelotonAppEnabled) "installed" else "disabled"} · " +
                                "Peloton sensor service: ${if (!health.sensorServiceInstalled) "missing" else if (health.sensorServiceEnabled) "running normally" else "disabled"}",
                            color = if (health.ok) BikeColors.Good else BikeColors.Warn,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { g.openPeloton() }) { Text("Open Peloton app") }
                        OutlinedButton(onClick = { (ctx as? Activity)?.let(Permissions::openHomeAppPicker) }) { Text("Choose home screen…") }
                        g.settings.originalBluetoothName?.let { original ->
                            OutlinedButton(onClick = {
                                update { it.copy(bleRenameAdapter = false) }
                                g.peripheral.restoreName(original)
                                g.restartBluetoothBroadcast()
                            }) { Text("Restore Bluetooth name \"$original\"") }
                        }
                    }
                    Text("To remove BikeOS completely: ${Permissions.ADB_UNINSTALL}", color = BikeColors.TextDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Section("Bike sensor") {
                    Text("Status: ${sensor.sourceName} · ${sensor.state.name.lowercase().replace('_', ' ')}" + (sensor.detail?.let { " · $it" } ?: ""), color = BikeColors.TextDim)
                    Text("Device: ${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = BikeColors.TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SensorSourceChoice.entries.forEach { c -> FilterChip(s.sensorSource == c, { update { it.copy(sensorSource = c) } }, { Text(c.label) }) }
                    }
                    BikeCheckBlock()
                    SettingRow("Learned resistance curve", "${g.sensors.resistanceModel.samplesLearned} samples from your bike. Used for ERG mode and power-zone resistance hints.") {
                        OutlinedButton(onClick = {
                            g.settings.resetResistanceModel()
                            g.sensors.resistanceModel.restore(ResistanceModel.Snapshot(ResistanceModel.SEED, 0))
                        }) { Text("Reset") }
                    }
                }
                Section("Bluetooth: share the bike with other apps") {
                    Text(
                        "BikeOS appears as a smart trainer (FTMS) and power meter (Cycling Power). Pair it in Zwift, Rouvy, MyWhoosh, " +
                            "Kinomap, TrainerRoad, or on an Apple Watch / Garmin as a power + cadence sensor.",
                        color = BikeColors.TextDim,
                    )
                    ToggleRow("Broadcast", ble.error ?: if (ble.advertising) "Advertising" + (if (ble.connectedDevices.isNotEmpty()) " · connected: ${ble.connectedDevices.joinToString()}" else "") else "Off", s.bleBroadcast) { v ->
                        update { it.copy(bleBroadcast = v) }
                        g.restartBluetoothBroadcast()
                    }
                    ToggleRow(
                        "Use a custom Bluetooth name",
                        "Off (recommended): other devices see the tablet's existing name \"${g.peripheral.advertisedName ?: "?"}\". " +
                            "On: BikeOS renames the tablet while broadcasting and restores the original name when it stops.",
                        s.bleRenameAdapter,
                    ) { v -> update { it.copy(bleRenameAdapter = v) }; g.restartBluetoothBroadcast() }
                    var name by remember(s.bleName) { mutableStateOf(s.bleName) }
                    if (s.bleRenameAdapter) SettingRow("Custom name") {
                        OutlinedTextField(name, { name = it.take(20) }, singleLine = true, modifier = Modifier.widthIn(max = 220.dp))
                        TextButton(onClick = { update { it.copy(bleName = name.ifBlank { "BikeOS Bike" }) }; g.restartBluetoothBroadcast() }) { Text("Save") }
                    }
                    ToggleRow("Follow resistance requests from apps", "ERG (target watts) and SIM (hills) from Zwift etc. become resistance prompts, or auto-resistance on a Bike+.", s.followTrainerApps) { v -> update { it.copy(followTrainerApps = v) } }
                    SettingRow("Hill feel: flat-road resistance") { Stepper("${s.terrainBase.roundToInt()}", { update { it.copy(terrainBase = (it.terrainBase - 1).coerceAtLeast(0f)) } }, { update { it.copy(terrainBase = (it.terrainBase + 1).coerceAtMost(80f)) } }) }
                    SettingRow("Hill feel: resistance per 1% grade") { Stepper("%.1f".format(s.terrainGain), { update { it.copy(terrainGain = (it.terrainGain - 0.5f).coerceAtLeast(0.5f)) } }, { update { it.copy(terrainGain = (it.terrainGain + 0.5f).coerceAtMost(10f)) } }) }
                }
                Section("Second screen (Wi-Fi dashboard)") {
                    Text(
                        "Show live bike stats in Safari/Chrome on any phone, iPad or laptop on your Wi-Fi — e.g. next to Apple Fitness+ " +
                            "on an iPad or Apple TV. Read-only: nothing on the bike can be changed from it.",
                        color = BikeColors.TextDim,
                    )
                    ToggleRow("Enable dashboard", g.dashboardUrl?.let { "Open $it" } ?: "Off", s.dashboardEnabled) { v -> update { it.copy(dashboardEnabled = v) } }
                }
                Section("Heart rate monitor") {
                    Text(
                        when {
                            s.hrDeviceAddress == null -> "None paired"
                            else -> "${s.hrDeviceName ?: s.hrDeviceAddress} · ${hrState.name.lowercase()}"
                        },
                        color = BikeColors.TextDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (!Permissions.hasBluetooth(ctx)) (ctx as? Activity)?.let(Permissions::requestBluetooth)
                            else { scanning = true; g.heartRate.startScan() }
                        }) { Text("Find heart rate monitor") }
                        if (s.hrDeviceAddress != null) OutlinedButton(onClick = { update { it.copy(hrDeviceAddress = null, hrDeviceName = null) } }) { Text("Forget") }
                    }
                }
                Section("Bike+ auto-resistance (experimental)") {
                    Text(
                        "On a Bike+ BikeOS can move the resistance motor to follow workouts and ERG mode. The required service call " +
                            "differs between Peloton firmware versions; enter the transaction code for your firmware (see README). " +
                            "The regular Bike has a manual knob — BikeOS shows turn-up / turn-down prompts instead.",
                        color = BikeColors.TextDim,
                    )
                    var code by remember(s.autoResistanceTxCode) { mutableStateOf(s.autoResistanceTxCode?.toString() ?: "") }
                    val codeValid = code.isEmpty() || PelotonCallPolicy.isValidResistanceCode(code.toIntOrNull(), g.sensors.protocol)
                    SettingRow("Transaction code", if (codeValid) null else "Not accepted: must be 1-64 and not a known read call.") {
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(3) }, singleLine = true, isError = !codeValid, modifier = Modifier.width(120.dp))
                        TextButton(enabled = codeValid, onClick = { update { it.copy(autoResistanceTxCode = code.toIntOrNull()) } }) { Text("Save") }
                    }
                    SettingRow("Maximum automatic resistance", "Auto-resistance never goes above this, ramps at most 10 points per second, and never increases while you're not pedaling.") {
                        Stepper("${s.autoResistanceMax}", { update { it.copy(autoResistanceMax = (it.autoResistanceMax - 5).coerceAtLeast(10)) } }, { update { it.copy(autoResistanceMax = (it.autoResistanceMax + 5).coerceAtMost(100)) } })
                    }
                    if (g.ride.autoResistanceFaulted) Text("Auto-resistance stopped after repeated errors — check the transaction code. Manual prompts are active.", color = BikeColors.Bad)
                    ToggleRow("Enable auto-resistance", if (g.sensors.isBikePlus) "Bike+ detected" else "Requires a Peloton Bike+", s.autoResistance) { v -> update { it.copy(autoResistance = v) } }
                }
                Section("About") {
                    Text("BikeOS ${BuildConfig.VERSION_NAME}. Independent software; not affiliated with or endorsed by Peloton.", color = BikeColors.TextDim)
                    OutlinedButton(onClick = { nav.go(Screen.History) }) { Text("Ride history") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (scanning) {
        val found by g.heartRate.found.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { scanning = false; g.heartRate.stopScan() },
            title = { Text("Heart rate monitors nearby") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (found.isEmpty()) Text("Searching… wear your strap (moisten the contacts) or start HR broadcast on your watch.")
                    found.forEach { d ->
                        OutlinedButton(onClick = {
                            update { it.copy(hrDeviceAddress = d.address, hrDeviceName = d.name) }
                            g.heartRate.stopScan()
                            scanning = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("${d.name}  (${d.rssi} dBm)") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scanning = false; g.heartRate.stopScan() }) { Text("Close") } },
        )
    }
}

/** Shows what the read-only inspection of Peloton's sensor service found on this bike. */
@Composable
private fun BikeCheckBlock() {
    val g = LocalContext.current.graph
    val check by g.sensors.check.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Bike check (reads Peloton's sensor-service code; changes nothing)", color = BikeColors.Text, style = MaterialTheme.typography.titleMedium)
        when (val r = check) {
            null -> Text(if (g.sensors.isPeloton) "Not run yet." else "Not a Peloton — nothing to check.", color = BikeColors.TextDim)
            is com.bikeos.sensor.BikeCheck.Result.NotInstalled -> Text(r.why, color = BikeColors.Warn)
            is com.bikeos.sensor.BikeCheck.Result.Unreadable -> Text(r.why, color = BikeColors.Warn)
            is com.bikeos.sensor.BikeCheck.Result.Ok -> {
                val rep = r.report
                fun mark(b: Boolean) = if (b) "✓" else "✗"
                Text(
                    "Sensor service ${r.version} · Bike interface ${mark(rep.v1InterfaceFound)} · callback ${mark(rep.v1CallbackFound)} · " +
                        "Bike+ interface ${mark(rep.bikePlusInterfaceFound)} · BikeData ${mark(rep.bikeDataFound)}",
                    color = if (rep.anyInterfaceFound) BikeColors.Good else BikeColors.Bad,
                )
                Text(
                    "Using: register #${rep.protocol.v1RegisterCallback}, unregister #${rep.protocol.v1UnregisterCallback}, " +
                        "Bike+ read #${rep.protocol.bikePlusGetBikeData} (${rep.protocol.source})",
                    color = BikeColors.TextDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
                rep.notes.forEach { Text("• $it", color = BikeColors.Warn, fontSize = 13.sp) }
                rep.protocol.bikePlusSetResistance?.let { code ->
                    val name = rep.resistanceCandidates.firstOrNull { it.second == code }?.first ?: "set resistance"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Found Bike+ resistance call: $name (#$code)", color = BikeColors.Accent, modifier = Modifier.weight(1f))
                        if (settings.autoResistanceTxCode != code) {
                            OutlinedButton(onClick = { g.settings.update { it.copy(autoResistanceTxCode = code) } }) { Text("Use it") }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { scope.launch { g.sensors.runBikeCheck(); g.sensors.start(settings.sensorSource) } }) { Text("Run bike check again") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String? = null, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = BikeColors.Text, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, color = BikeColors.TextDim, style = MaterialTheme.typography.bodyMedium, fontFamily = if (it.contains("adb ")) FontFamily.Monospace else null, fontSize = 13.sp)
            }
        }
        control()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle) { Switch(checked = checked, onCheckedChange = onChange) }
}
