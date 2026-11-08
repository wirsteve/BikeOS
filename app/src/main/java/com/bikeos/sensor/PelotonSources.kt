package com.bikeos.sensor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.util.Log
import com.bikeos.core.peloton.PelotonBikeData
import com.bikeos.core.peloton.PelotonService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BikeOS.Sensor"

/**
 * Binds to one of Peloton's on-tablet services and keeps the binding alive, re-binding with
 * back-off if Peloton's service crashes or is restarted (it is, after OTA updates).
 */
internal class ServiceBinder(
    private val context: Context,
    private val intent: Intent,
    private val onConnected: (IBinder) -> Unit,
    private val onLost: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var bound = false
    private var wanted = false
    private var retryDelayMs = 2_000L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            retryDelayMs = 2_000L
            if (service == null) onLost("Peloton service returned no binder") else onConnected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) = onLost("Peloton service disconnected")
        override fun onBindingDied(name: ComponentName?) {
            onLost("Peloton service binding died")
            rebindLater()
        }

        override fun onNullBinding(name: ComponentName?) = onLost("Peloton service refused the connection")
    }

    /** @return false if the service does not exist on this device. */
    fun bind(): Boolean {
        wanted = true
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "bind refused", e)
            false
        }
        if (!bound) rebindLater()
        return bound
    }

    private fun rebindLater() {
        if (!wanted) return
        unbindQuietly()
        main.postDelayed({ if (wanted) bind() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
    }

    fun unbind() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        unbindQuietly()
    }

    private fun unbindQuietly() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }
}

/**
 * Peloton Bike on current OS builds: `IV1Interface` exposes registerCallback(IV1Callback, String)
 * and pushes a BikeData parcel to the callback on every sensor update.
 */
class PelotonV1Source(private val context: Context) : SensorSource {
    override val name = "Peloton Bike"
    private val _status = MutableStateFlow(SensorStatus(SensorState.STOPPED, name))
    override val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private var binder: ServiceBinder? = null
    private var service: IBinder? = null
    private var listener: ((RawReading) -> Unit)? = null

    private val callback = object : Binder() {
        init {
            attachInterface(null, PelotonService.V1_CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                PelotonService.V1_CB_ON_SENSOR_DATA -> {
                    runCatching {
                        data.enforceInterface(PelotonService.V1_CALLBACK)
                        if (data.readInt() != 0) {
                            val d = PelotonBikeData.decode(AndroidParcelSource(data))
                            _status.value = SensorStatus(SensorState.CONNECTED, name, d.firmwareVersion?.let { "Firmware $it" })
                            listener?.invoke(RawReading(d.rpm.toFloat(), d.powerWatts, d.currentResistance.toFloat()))
                        }
                    }.onFailure { Log.w(TAG, "bad V1 sensor parcel", it) }
                    reply?.writeNoException()
                    true
                }
                PelotonService.V1_CB_ON_SENSOR_ERROR -> {
                    runCatching {
                        data.enforceInterface(PelotonService.V1_CALLBACK)
                        val err = data.readLong()
                        _status.value = SensorStatus(SensorState.NO_DATA, name, "Sensor error $err")
                    }
                    reply?.writeNoException()
                    true
                }
                PelotonService.V1_CB_ON_CALIBRATION -> {
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun start(onReading: (RawReading) -> Unit) {
        listener = onReading
        _status.value = SensorStatus(SensorState.CONNECTING, name)
        val intent = Intent(PelotonService.V1_INTERFACE).setPackage(PelotonService.AFFERNET_PACKAGE)
        binder = ServiceBinder(context, intent, onConnected = { b ->
            service = b
            register(b)
        }, onLost = { why ->
            service = null
            _status.value = SensorStatus(SensorState.CONNECTING, name, why)
        }).also {
            if (!it.bind()) _status.value = SensorStatus(SensorState.UNAVAILABLE, name, "Peloton sensor service not found")
        }
    }

    private fun register(b: IBinder) = transactCallback(b, PelotonService.V1_TX_REGISTER_CALLBACK)

    private fun transactCallback(b: IBinder, code: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PelotonService.V1_INTERFACE)
            data.writeStrongBinder(callback)
            data.writeString("BikeOS")
            b.transact(code, data, reply, 0)
            reply.readException()
            if (code == PelotonService.V1_TX_REGISTER_CALLBACK) {
                _status.value = SensorStatus(SensorState.NO_DATA, name, "Waiting for first reading… start pedaling")
            }
        } catch (e: Exception) {
            Log.w(TAG, "V1 transact $code failed", e)
            _status.value = SensorStatus(SensorState.UNAVAILABLE, name, e.message)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun stop() {
        service?.let { transactCallback(it, PelotonService.V1_TX_UNREGISTER_CALLBACK) }
        binder?.unbind()
        binder = null
        service = null
        listener = null
        _status.value = SensorStatus(SensorState.STOPPED, name)
    }
}

/**
 * Peloton Bike+: `IBikeInterface` is polled; transaction 14 returns the current BikeData.
 * The Bike+ reports its resistance in targetResistance.
 */
class PelotonBikePlusSource(private val context: Context, private val pollMs: Long = 250L) : SensorSource {
    override val name = "Peloton Bike+"
    private val _status = MutableStateFlow(SensorStatus(SensorState.STOPPED, name))
    override val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private var binder: ServiceBinder? = null
    @Volatile private var service: IBinder? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** Exposed so the experimental auto-resistance controller can share the binding. */
    fun currentBinder(): IBinder? = service

    override fun start(onReading: (RawReading) -> Unit) {
        _status.value = SensorStatus(SensorState.CONNECTING, name)
        val intent = Intent(PelotonService.BIKE_PLUS_INTERFACE).setPackage(PelotonService.AFFERNET_PACKAGE)
        binder = ServiceBinder(context, intent, onConnected = { b -> service = b }, onLost = { why ->
            service = null
            _status.value = SensorStatus(SensorState.CONNECTING, name, why)
        }).also {
            if (!it.bind()) _status.value = SensorStatus(SensorState.UNAVAILABLE, name, "Peloton sensor service not found")
        }
        running.set(true)
        thread = Thread({
            var errors = 0
            while (running.get()) {
                val b = service
                if (b != null) {
                    try {
                        val d = poll(b)
                        errors = 0
                        _status.value = SensorStatus(SensorState.CONNECTED, name, d.firmwareVersion?.let { "Firmware $it" })
                        onReading(RawReading(d.rpm.toFloat(), d.powerWatts, d.targetResistance.toFloat()))
                    } catch (e: Exception) {
                        errors++
                        if (errors == 5) {
                            Log.w(TAG, "Bike+ poll failing", e)
                            _status.value = SensorStatus(SensorState.NO_DATA, name, e.message)
                        }
                    }
                }
                try {
                    Thread.sleep(if (errors > 5) 2_000L else pollMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "BikeOS-BikePlusPoll").apply { isDaemon = true; start() }
    }

    private fun poll(b: IBinder): PelotonBikeData {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PelotonService.BIKE_PLUS_INTERFACE)
            b.transact(PelotonService.BIKE_PLUS_TX_GET_BIKE_DATA, data, reply, 0)
            reply.readException()
            reply.readInt() // non-null marker
            return PelotonBikeData.decode(AndroidParcelSource(reply))
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        binder?.unbind()
        binder = null
        service = null
        _status.value = SensorStatus(SensorState.STOPPED, name)
    }
}

/**
 * Older Peloton Bike OS builds: a Messenger service. One repeating request per metric; each
 * reply carries a float in "data".
 */
class PelotonLegacySource(private val context: Context) : SensorSource {
    override val name = "Peloton Bike (legacy)"
    private val _status = MutableStateFlow(SensorStatus(SensorState.STOPPED, name))
    override val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private var binder: ServiceBinder? = null
    private var handlerThread: HandlerThread? = null
    @Volatile private var cadence = 0f
    @Volatile private var power = 0f
    @Volatile private var resistance = 0f

    override fun start(onReading: (RawReading) -> Unit) {
        _status.value = SensorStatus(SensorState.CONNECTING, name)
        val ht = HandlerThread("BikeOS-LegacySensor").also { it.start() }
        handlerThread = ht
        val handler = object : Handler(ht.looper) {
            override fun handleMessage(msg: Message) {
                val reply: Bundle = msg.data ?: return
                if (reply.getString(PelotonService.LEGACY_KEY_HEX) == PelotonService.LEGACY_TIMEOUT) return
                if (!reply.containsKey(PelotonService.LEGACY_KEY_DATA)) return
                val v = reply.getFloat(PelotonService.LEGACY_KEY_DATA)
                when (msg.what) {
                    PelotonService.LEGACY_CMD_RPM -> cadence = v
                    PelotonService.LEGACY_CMD_POWER -> power = v / 100f
                    PelotonService.LEGACY_CMD_RESISTANCE -> resistance = v
                    else -> return
                }
                _status.value = SensorStatus(SensorState.CONNECTED, name)
                onReading(RawReading(cadence, power, resistance))
            }
        }
        val intent = Intent(PelotonService.LEGACY_ACTION).setPackage(PelotonService.LEGACY_PACKAGE)
        binder = ServiceBinder(context, intent, onConnected = { b ->
            val messenger = Messenger(b)
            val replyTo = Messenger(handler)
            listOf(PelotonService.LEGACY_CMD_RPM, PelotonService.LEGACY_CMD_POWER, PelotonService.LEGACY_CMD_RESISTANCE).forEach { cmd ->
                runCatching {
                    messenger.send(Message.obtain().apply {
                        what = cmd
                        data = Bundle().apply { putString(PelotonService.LEGACY_KEY_REQUEST_ID, UUID.randomUUID().toString()) }
                        this.replyTo = replyTo
                    })
                }.onFailure { Log.w(TAG, "legacy request $cmd failed", it) }
            }
            _status.value = SensorStatus(SensorState.NO_DATA, name, "Waiting for first reading… start pedaling")
        }, onLost = { why -> _status.value = SensorStatus(SensorState.CONNECTING, name, why) }).also {
            if (!it.bind()) _status.value = SensorStatus(SensorState.UNAVAILABLE, name, "Legacy sensor service not found")
        }
    }

    override fun stop() {
        binder?.unbind()
        binder = null
        handlerThread?.quitSafely()
        handlerThread = null
        _status.value = SensorStatus(SensorState.STOPPED, name)
    }
}
