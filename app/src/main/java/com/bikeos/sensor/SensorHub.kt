package com.bikeos.sensor

import android.content.Context
import android.os.Build
import android.util.Log
import com.bikeos.core.metrics.ResistanceModel
import com.bikeos.core.metrics.SpikeFilter
import com.bikeos.core.model.BikeSample
import com.bikeos.core.peloton.PelotonProtocol
import com.bikeos.core.peloton.PelotonService
import com.bikeos.settings.SensorSourceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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

    /** Transaction numbers in use; updated from Peloton's own code by [runBikeCheck]. */
    @Volatile var protocol: PelotonProtocol = PelotonProtocol()
        private set

    private val _check = MutableStateFlow<BikeCheck.Result?>(null)
    val check: StateFlow<BikeCheck.Result?> = _check.asStateFlow()

    /** Inspect Peloton's sensor service on this bike (read-only) and adopt the protocol it uses. */
    suspend fun runBikeCheck(): BikeCheck.Result? {
        if (!isPeloton && !hasPelotonService()) return null
        val r = withContext(Dispatchers.IO) { runCatching { BikeCheck.run(context) }.getOrElse { BikeCheck.Result.Unreadable(it.message ?: "error") } }
        _check.value = r
        if (r is BikeCheck.Result.Ok) protocol = r.report.protocol
        return r
    }

    private fun hasPelotonService() = runCatching { context.packageManager.getApplicationInfo(PelotonService.AFFERNET_PACKAGE, 0) }.isSuccess

    private fun create(choice: SensorSourceChoice): SensorSource? = when (choice) {
        SensorSourceChoice.PELOTON_BIKE -> PelotonV1Source(context) { protocol }
        SensorSourceChoice.PELOTON_BIKE_PLUS -> PelotonBikePlusSource(context, { protocol })
        SensorSourceChoice.PELOTON_LEGACY -> PelotonLegacySource(context)
        SensorSourceChoice.SIMULATOR -> SimulatedSource(resistanceModel)
        SensorSourceChoice.AUTO, SensorSourceChoice.NONE -> null
    }

    /**
     * Order to try sources in for AUTO. Uses the bike check when available (which interfaces exist
     * in Peloton's code on this firmware), otherwise the model name.
     */
    fun autoOrder(): List<SensorSourceChoice> {
        val report = (check.value as? BikeCheck.Result.Ok)?.report
        val bikePlusFirst = when {
            report != null && report.bikePlusInterfaceFound && !report.v1InterfaceFound -> true
            report != null && report.v1InterfaceFound && !report.bikePlusInterfaceFound -> false
            else -> isBikePlus
        }
        return if (bikePlusFirst) listOf(SensorSourceChoice.PELOTON_BIKE_PLUS, SensorSourceChoice.PELOTON_BIKE, SensorSourceChoice.PELOTON_LEGACY)
        else listOf(SensorSourceChoice.PELOTON_BIKE, SensorSourceChoice.PELOTON_BIKE_PLUS, SensorSourceChoice.PELOTON_LEGACY)
    }

    fun start(choice: SensorSourceChoice) {
        stop()
        if (choice == SensorSourceChoice.AUTO) {
            if (!isPeloton && !hasPelotonService()) {
                _status.value = SensorStatus(SensorState.UNAVAILABLE, "No sensor", "Not running on a Peloton. Choose a sensor in Settings.")
                return
            }
            startChain(autoOrder())
            return
        }
        val src = create(choice)
        if (src == null) {
            _status.value = SensorStatus(SensorState.UNAVAILABLE, "No sensor", null)
            return
        }
        attach(src)
    }

    /** Try each source in turn until one connects; fall through on "service not found" or no data. */
    private fun startChain(order: List<SensorSourceChoice>) {
        val first = order.firstOrNull() ?: return
        val src = create(first) ?: return
        attach(src)
        if (order.size <= 1) return
        scope.launch {
            delay(4_000)
            val st = src.status.value.state
            // Only move on when this interface clearly doesn't exist / refused us; never while it's working.
            if (source === src && st == SensorState.UNAVAILABLE) {
                Log.i("BikeOS.Sensor", "${src.name} unavailable (${src.status.value.detail}); trying ${order[1]}")
                stop()
                startChain(order.drop(1))
            }
        }
    }

    private fun attach(src: SensorSource) {
        source = src
        statusJob = scope.launch { src.status.collectLatest { _status.value = it } }
        src.start(::onReading)

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
