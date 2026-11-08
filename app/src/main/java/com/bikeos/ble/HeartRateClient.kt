package com.bikeos.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.bikeos.core.ble.GattIds
import com.bikeos.core.ble.HeartRateCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FoundDevice(val address: String, val name: String, val rssi: Int)

enum class HrState { DISABLED, SCANNING, CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Connects to any standard Bluetooth heart-rate monitor (Polar, Wahoo, Garmin HRM-Dual,
 * Apple Watch apps that broadcast HR, etc.).
 */
@SuppressLint("MissingPermission")
class HeartRateClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _bpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = _bpm.asStateFlow()
    private val _state = MutableStateFlow(HrState.DISABLED)
    val state: StateFlow<HrState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var lastBeatMs = 0L

    private val staleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastBeatMs > 5_000) _bpm.value = null
            main.postDelayed(this, 2_000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = FoundDevice(result.device.address, result.device.name ?: result.scanRecord?.deviceName ?: "Heart rate monitor", result.rssi)
            _found.value = (_found.value.filterNot { it.address == d.address } + d).sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("BikeOS.HR", "scan failed $errorCode")
        }
    }

    fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        _found.value = emptyList()
        runCatching {
            a.bluetoothLeScanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattIds.HR_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
        }.onFailure { Log.w("BikeOS.HR", "cannot scan", it) }
        main.postDelayed({ stopScan() }, 20_000)
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    fun connect(address: String) {
        disconnect()
        targetAddress = address
        val a = adapter ?: return
        if (!a.isEnabled) {
            _state.value = HrState.DISCONNECTED
            return
        }
        val device: BluetoothDevice = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return
        _state.value = HrState.CONNECTING
        gatt = device.connectGatt(context, true, callback)
        main.removeCallbacks(staleCheck)
        main.post(staleCheck)
    }

    fun disconnect() {
        targetAddress = null
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        _bpm.value = null
        _state.value = HrState.DISABLED
        main.removeCallbacks(staleCheck)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = HrState.CONNECTED
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = HrState.DISCONNECTED
                _bpm.value = null
                // autoConnect=true makes Android reconnect when the strap is back in range.
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(GattIds.HR_SERVICE)?.getCharacteristic(GattIds.HR_MEASUREMENT) ?: return
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(GattIds.CCCD)?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == GattIds.HR_MEASUREMENT) {
                HeartRateCodec.parse(ch.value ?: return)?.takeIf { it in 25..250 }?.let {
                    lastBeatMs = System.currentTimeMillis()
                    _bpm.value = it
                }
            }
        }
    }
}
