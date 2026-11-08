package com.bikeos.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bikeos.core.ble.CrankCounter
import com.bikeos.core.ble.CscEncoder
import com.bikeos.core.ble.CyclingPowerEncoder
import com.bikeos.core.ble.FtmsCodec
import com.bikeos.core.ble.FtmsCodec.ControlRequest
import com.bikeos.core.ble.GattIds
import com.bikeos.core.ble.IndoorBikeFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a connected training app (Zwift, Rouvy, …) has asked the bike to do. */
sealed class TrainerTarget(val atMs: Long = System.currentTimeMillis()) {
    object None : TrainerTarget(0)
    class Erg(val watts: Int) : TrainerTarget()
    class Simulation(val gradePct: Float) : TrainerTarget()
    class Resistance(val level: Float) : TrainerTarget()
}

data class PeripheralStatus(
    val advertising: Boolean = false,
    val supported: Boolean = true,
    val connectedDevices: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Makes the bike look like a standard smart trainer and power meter over Bluetooth LE:
 *  - Fitness Machine Service (Indoor Bike) for Zwift, Rouvy, MyWhoosh, Kinomap, TrainerRoad…
 *  - Cycling Power Service (with crank revolutions = cadence) for Apple Watch, Garmin, Wahoo…
 */
@SuppressLint("MissingPermission")
class BikePeripheral(private val context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var server: BluetoothGattServer? = null
    private val subscriptions = ConcurrentHashMap<UUID, MutableSet<BluetoothDevice>>()
    private val connected = ConcurrentHashMap<String, BluetoothDevice>()
    private val crank = CrankCounter()
    private val cps = CyclingPowerEncoder(crank)
    private val csc = CscEncoder(crank)
    private var renamedFrom: String? = null
    private val pendingServices = ArrayDeque<BluetoothGattService>()

    private val _status = MutableStateFlow(PeripheralStatus())
    val status: StateFlow<PeripheralStatus> = _status.asStateFlow()
    private val _target = MutableStateFlow<TrainerTarget>(TrainerTarget.None)
    val target: StateFlow<TrainerTarget> = _target.asStateFlow()

    private lateinit var indoorBikeData: BluetoothGattCharacteristic
    private lateinit var controlPoint: BluetoothGattCharacteristic
    private lateinit var machineStatus: BluetoothGattCharacteristic
    private lateinit var cpMeasurement: BluetoothGattCharacteristic
    private lateinit var cscMeasurement: BluetoothGattCharacteristic

    /** The name other devices see in their pairing lists. */
    val advertisedName: String? get() = runCatching { manager?.adapter?.name }.getOrNull()

    /**
     * @param renameTo if set, temporarily rename the tablet's Bluetooth adapter while broadcasting.
     *   Off by default: the adapter name is a tablet-wide setting that Peloton's own Bluetooth
     *   features also use. The original name is saved first and restored by [stop] (and by
     *   "Restore Bluetooth name" in Settings if BikeOS was ever killed mid-broadcast).
     * @param saveOriginalName persists the pre-rename name so it survives a crash.
     */
    fun start(renameTo: String?, saveOriginalName: (String) -> Unit = {}) {
        if (server != null) return
        val manager = manager ?: run {
            _status.value = PeripheralStatus(supported = false, error = "No Bluetooth on this device")
            return
        }
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            _status.value = PeripheralStatus(supported = adapter != null, error = "Bluetooth is off")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            _status.value = PeripheralStatus(supported = false, error = "This tablet cannot act as a Bluetooth sensor")
            return
        }
        if (!renameTo.isNullOrBlank()) {
            runCatching {
                val original = adapter.name
                if (original != null && original != renameTo) {
                    saveOriginalName(original)
                    renamedFrom = original
                    adapter.name = renameTo
                }
            }
        }
        server = manager.openGattServer(context, callback) ?: run {
            _status.value = PeripheralStatus(error = "Could not open GATT server")
            return
        }
        pendingServices.clear()
        pendingServices.addAll(listOf(buildFtms(), buildCps(), buildCsc(), buildDis()))
        // addService is asynchronous: add the next one from onServiceAdded.
        server?.addService(pendingServices.removeFirst())
    }

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(GattIds.FTMS_SERVICE))
            .addServiceUuid(ParcelUuid(GattIds.CPS_SERVICE))
            .addServiceUuid(ParcelUuid(GattIds.CSC_SERVICE))
            .addServiceData(ParcelUuid(GattIds.FTMS_SERVICE), FtmsCodec.advertisingServiceData())
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _status.value = _status.value.copy(advertising = true, error = null)
        }

        override fun onStartFailure(errorCode: Int) {
            _status.value = _status.value.copy(advertising = false, error = "Advertising failed ($errorCode)")
        }
    }

    fun stop() {
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        renamedFrom?.let { original -> runCatching { manager?.adapter?.name = original } }
        renamedFrom = null
        subscriptions.clear()
        connected.clear()
        _target.value = TrainerTarget.None
        _status.value = PeripheralStatus()
    }

    /** Push the latest numbers to every subscribed app. Call ~1-4 times a second. */
    fun publish(frame: IndoorBikeFrame, nowMs: Long) {
        val s = server ?: return
        if (connected.isEmpty()) return
        notify(s, indoorBikeData, FtmsCodec.indoorBikeData(frame))
        crank.update(frame.cadenceRpm, nowMs)
        notify(s, cpMeasurement, cps.measurement(frame.powerW, frame.cadenceRpm, nowMs, advanceCrank = false))
        notify(s, cscMeasurement, csc.measurement(frame.cadenceRpm, nowMs, advanceCrank = false))
    }

    private fun notify(s: BluetoothGattServer, ch: BluetoothGattCharacteristic, value: ByteArray, indicate: Boolean = false) {
        val subs = subscriptions[ch.uuid] ?: return
        ch.value = value
        for (d in subs) runCatching { s.notifyCharacteristicChanged(d, ch, indicate) }
    }

    // ---- GATT definition ----

    private fun cccd() = BluetoothGattDescriptor(GattIds.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

    private fun readOnly(uuid: UUID, value: ByteArray) = BluetoothGattCharacteristic(uuid, PROPERTY_READ, PERMISSION_READ).apply { this.value = value }

    private fun buildFtms(): BluetoothGattService {
        val svc = BluetoothGattService(GattIds.FTMS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(readOnly(GattIds.FTMS_FEATURE, FtmsCodec.feature()))
        indoorBikeData = BluetoothGattCharacteristic(GattIds.INDOOR_BIKE_DATA, PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
        svc.addCharacteristic(indoorBikeData)
        svc.addCharacteristic(readOnly(GattIds.SUPPORTED_RESISTANCE_RANGE, FtmsCodec.supportedResistanceRange()))
        svc.addCharacteristic(readOnly(GattIds.SUPPORTED_POWER_RANGE, FtmsCodec.supportedPowerRange()))
        controlPoint = BluetoothGattCharacteristic(GattIds.FTMS_CONTROL_POINT, PROPERTY_WRITE or PROPERTY_INDICATE, PERMISSION_WRITE)
            .apply { addDescriptor(cccd()) }
        svc.addCharacteristic(controlPoint)
        machineStatus = BluetoothGattCharacteristic(GattIds.FTMS_STATUS, PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
        svc.addCharacteristic(machineStatus)
        return svc
    }

    private fun buildCps(): BluetoothGattService {
        val svc = BluetoothGattService(GattIds.CPS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        cpMeasurement = BluetoothGattCharacteristic(GattIds.CP_MEASUREMENT, PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
        svc.addCharacteristic(cpMeasurement)
        svc.addCharacteristic(readOnly(GattIds.CP_FEATURE, cps.feature()))
        svc.addCharacteristic(readOnly(GattIds.SENSOR_LOCATION, cps.sensorLocation()))
        return svc
    }

    private fun buildCsc(): BluetoothGattService {
        val svc = BluetoothGattService(GattIds.CSC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        cscMeasurement = BluetoothGattCharacteristic(GattIds.CSC_MEASUREMENT, PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
        svc.addCharacteristic(cscMeasurement)
        svc.addCharacteristic(readOnly(GattIds.CSC_FEATURE, csc.feature()))
        svc.addCharacteristic(readOnly(GattIds.SENSOR_LOCATION, cps.sensorLocation()))
        return svc
    }

    /** Restores a Bluetooth name saved before a crash. */
    fun restoreName(original: String) {
        runCatching { manager?.adapter?.name = original }
    }

    private fun buildDis(): BluetoothGattService {
        val svc = BluetoothGattService(GattIds.DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(readOnly(GattIds.MANUFACTURER_NAME, "BikeOS".toByteArray()))
        svc.addCharacteristic(readOnly(GattIds.MODEL_NUMBER, "Indoor Bike".toByteArray()))
        return svc
    }

    // ---- Control point ----

    private fun handleControl(device: BluetoothDevice, value: ByteArray) {
        val s = server ?: return
        val req = FtmsCodec.parseControlPoint(value)
        val result = when (req) {
            ControlRequest.RequestControl, ControlRequest.StartOrResume -> FtmsCodec.RESULT_SUCCESS
            ControlRequest.Reset -> {
                _target.value = TrainerTarget.None
                notify(s, machineStatus, FtmsCodec.statusReset())
                FtmsCodec.RESULT_SUCCESS
            }
            is ControlRequest.SetTargetPower -> {
                _target.value = TrainerTarget.Erg(req.watts)
                notify(s, machineStatus, FtmsCodec.statusTargetPowerChanged(req.watts))
                FtmsCodec.RESULT_SUCCESS
            }
            is ControlRequest.SetTargetResistance -> {
                _target.value = TrainerTarget.Resistance(req.level)
                notify(s, machineStatus, FtmsCodec.statusTargetResistanceChanged(req.level))
                FtmsCodec.RESULT_SUCCESS
            }
            is ControlRequest.SetSimulation -> {
                _target.value = TrainerTarget.Simulation(req.gradePct)
                notify(s, machineStatus, FtmsCodec.statusSimulationChanged(req.windMps, req.gradePct, req.crr, req.cw))
                FtmsCodec.RESULT_SUCCESS
            }
            is ControlRequest.StopOrPause -> {
                notify(s, machineStatus, FtmsCodec.statusStopped(req.pause))
                FtmsCodec.RESULT_SUCCESS
            }
            is ControlRequest.Malformed -> FtmsCodec.RESULT_INVALID_PARAMETER
            is ControlRequest.Unsupported -> FtmsCodec.RESULT_NOT_SUPPORTED
        }
        controlPoint.value = FtmsCodec.controlResponse(req.opCode, result)
        runCatching { s.notifyCharacteristicChanged(device, controlPoint, true) }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            val next = pendingServices.removeFirstOrNull()
            if (next != null) server?.addService(next) else startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected[device.address] = device
            } else {
                connected.remove(device.address)
                subscriptions.values.forEach { it.remove(device) }
                if (connected.isEmpty()) _target.value = TrainerTarget.None
            }
            _status.value = _status.value.copy(connectedDevices = connected.values.map { it.name ?: it.address })
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: ByteArray(0)
            val slice = if (offset < v.size) v.copyOfRange(offset, v.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (ch.uuid == GattIds.FTMS_CONTROL_POINT && value != null) handleControl(device, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val on = subscriptions[descriptor.characteristic.uuid]?.contains(device) == true
            val v = when {
                !on -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                descriptor.characteristic.uuid == GattIds.FTMS_CONTROL_POINT -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == GattIds.CCCD) {
                val uuid = descriptor.characteristic.uuid
                val enable = value != null && value.isNotEmpty() && value[0].toInt() != 0
                val set = subscriptions.getOrPut(uuid) { ConcurrentHashMap.newKeySet<BluetoothDevice>() }
                if (enable) set.add(device) else set.remove(device)
                Log.d("BikeOS.BLE", "${device.address} ${if (enable) "subscribed" else "unsubscribed"} $uuid")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }
}
