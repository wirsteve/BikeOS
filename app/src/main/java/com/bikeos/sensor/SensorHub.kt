package com.bikeos.sensor

import android.content.Context
import android.os.Build
import android.util.Log
import com.bikeos.core.metrics.ResistanceModel
import com.bikeos.core.metrics.SpikeFilter
import com.bikeos.core.model.BikeSample
import com.bikeos.core.peloton.PelotonService
import com.bikeos.settings.SensorSourceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Produces believable ride data on devices without a bike, for demos and development. */
class SimulatedSource(private val model: ResistanceModel) : SensorSource {
    override val name = "Simulator"
    private val _status = MutableStateFlow(SensorStatus(SensorState.STOPPED, name))
    override val status: StateFlow<SensorStatus> = _status.asStateFlow()
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(onReading: (RawReading) -> Unit) {
        running = true
        _status.value = SensorStatus(SensorState.CONNECTED, name, "Simulated rider")
        val t0 = System.currentTimeMillis()
        thread = Thread({
            while (running) {
                val t = (System.currentTimeMillis() - t0) / 1000.0
                val cadence = (85 + 12 * sin(2 * PI * t / 95) + Random.nextDouble(-2.0, 2.0)).toFloat()
                val resistance = (38 + 14 * sin(2 * PI * t / 240)).toFloat().coerceIn(0f, 100f)
                val power = model.predictPower(resistance, cadence) * (1 + Random.nextDouble(-0.03, 0.03)).toFloat()
                onReading(RawReading(cadence, power, resistance))
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
        }, "BikeOS-Sim").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        _status.value = SensorStatus(SensorState.STOPPED, name)
    }
}

/**
 * Owns the active sensor source, cleans its data and merges heart rate.
 * Publishes [sample] (latest cleaned reading) and [status].
 */
class SensorHub(
    private val context: Context,
    private val scope: CoroutineScope,
    val resistanceModel: ResistanceModel,
    private val heartRate: StateFlow<Int?>,
) {
    private val _sample = MutableStateFlow(BikeSample.idle(System.currentTimeMillis()))
    val sample: StateFlow<BikeSample> = _sample.asStateFlow()

    private val _status = MutableStateFlow(SensorStatus(SensorState.STOPPED, "No sensor"))
    val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private var source: SensorSource? = null
    private var statusJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var lastReadingMs = 0L
    private val resistanceFilter = SpikeFilter(3)
    private val powerFilter = SpikeFilter(2)

    val activeSource: SensorSource? get() = source

    /** True if the bike sent a reading in the last 2 seconds. Auto-resistance only increases while fresh. */
    val isFresh: Boolean get() = lastReadingMs != 0L && System.currentTimeMillis() - lastReadingMs < 2_000

    val isPeloton: Boolean get() = Build.BRAND.equals(PelotonService.BRAND, ignoreCase = true)
    val isBikePlus: Boolean get() = Build.MODEL == PelotonService.BIKE_PLUS_MODEL

    fun start(choice: SensorSourceChoice) {
        stop()
        val src = when (choice) {
            SensorSourceChoice.AUTO -> when {
                isPeloton && isBikePlus -> PelotonBikePlusSource(context)
                isPeloton -> PelotonV1Source(context)
                else -> null
            }
            SensorSourceChoice.PELOTON_BIKE -> PelotonV1Source(context)
            SensorSourceChoice.PELOTON_BIKE_PLUS -> PelotonBikePlusSource(context)
            SensorSourceChoice.PELOTON_LEGACY -> PelotonLegacySource(context)
            SensorSourceChoice.SIMULATOR -> SimulatedSource(resistanceModel)
            SensorSourceChoice.NONE -> null
        }
        if (src == null) {
            _status.value = SensorStatus(
                SensorState.UNAVAILABLE, "No sensor",
                if (choice == SensorSourceChoice.AUTO) "Not running on a Peloton. Choose a sensor in Settings." else null,
            )
            return
        }
        source = src
        statusJob = scope.launch { src.status.collectLatest { _status.value = it } }
        src.start(::onReading)

        // On AUTO for a Peloton Bike, fall back to the legacy service if the V1 one is absent.
        if (choice == SensorSourceChoice.AUTO && src is PelotonV1Source) {
            scope.launch {
                delay(4_000)
                if (src.status.value.state == SensorState.UNAVAILABLE && source === src) {
                    Log.i("BikeOS.Sensor", "V1 interface unavailable, trying legacy service")
                    start(SensorSourceChoice.PELOTON_LEGACY)
                }
            }
        }

        // Zero metrics if the sensor goes silent so a stalled service does not freeze the display.
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                if (lastReadingMs != 0L && now - lastReadingMs > 3_000) {
                    _sample.value = BikeSample(now, 0f, 0f, _sample.value.resistancePct, heartRate.value)
                }
                if (lastReadingMs != 0L && now - lastReadingMs > 15_000 && _status.value.state == SensorState.CONNECTED) {
                    _status.value = _status.value.copy(state = SensorState.NO_DATA, detail = "No data for 15 s")
                }
            }
        }
    }

    private fun onReading(r: RawReading) {
        lastReadingMs = r.timestampMs
        val res = resistanceFilter.push(r.resistancePct.coerceIn(0f, 100f))
        // Low-power spikes: Peloton occasionally reports a huge value for a single sample.
        val pwr = if (r.powerWatts > 2500f) 0f else powerFilter.push(r.powerWatts.coerceAtLeast(0f))
        val cad = r.cadenceRpm.coerceIn(0f, 200f)
        _sample.value = BikeSample(r.timestampMs, cad, pwr, res, heartRate.value)
        if (source !is SimulatedSource) resistanceModel.learn(res, cad, pwr)
    }

    fun stop() {
        statusJob?.cancel()
        watchdogJob?.cancel()
        source?.stop()
        source = null
        lastReadingMs = 0L
    }
}
