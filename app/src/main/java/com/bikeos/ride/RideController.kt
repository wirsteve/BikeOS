package com.bikeos.ride

import com.bikeos.ble.BikePeripheral
import com.bikeos.ble.TrainerTarget
import com.bikeos.core.ble.IndoorBikeFrame
import com.bikeos.core.export.RideRecord
import com.bikeos.core.metrics.PowerZones
import com.bikeos.core.metrics.RideAccumulator
import com.bikeos.core.metrics.RideStats
import com.bikeos.core.metrics.SpeedModel
import com.bikeos.core.model.BikeSample
import com.bikeos.core.workout.ComplianceTracker
import com.bikeos.core.workout.SegmentType
import com.bikeos.core.workout.TargetEvaluation
import com.bikeos.core.workout.Workout
import com.bikeos.core.workout.WorkoutLibrary
import com.bikeos.core.workout.WorkoutProgress
import com.bikeos.core.workout.WorkoutRunner
import com.bikeos.data.RideRepository
import com.bikeos.resistance.BikePlusAutoResistance
import com.bikeos.resistance.ManualResistance
import com.bikeos.resistance.ResistanceController
import com.bikeos.sensor.PelotonBikePlusSource
import com.bikeos.sensor.SensorHub
import com.bikeos.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RidePhase { IDLE, RIDING, PAUSED }

data class Suggestion(val resistance: Float, val reason: String, val automatic: Boolean)

data class RideState(
    val phase: RidePhase = RidePhase.IDLE,
    val workout: Workout? = null,
    val progress: WorkoutProgress? = null,
    val evaluation: TargetEvaluation? = null,
    val stats: RideStats = RideStats(),
    val sample: BikeSample = BikeSample.idle(0),
    val zone: PowerZones.Zone = PowerZones.zones.first(),
    val suggestion: Suggestion? = null,
    val targetScore: Int? = null,
    val startedAtMs: Long = 0,
    val powerHistory: List<Float> = emptyList(),
    val companionApp: String? = null,
    val trainerTarget: TrainerTarget = TrainerTarget.None,
    val autoStarted: Boolean = false,
    val lastFinished: RideRecord? = null,
) {
    val active: Boolean get() = phase != RidePhase.IDLE
}

/**
 * The ride engine. Ticks at 4 Hz on the main thread, integrating sensor samples into the
 * current ride, running the structured workout, turning trainer-app requests into resistance
 * targets and broadcasting live data over Bluetooth.
 */
class RideController(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val hub: SensorHub,
    private val heartRate: StateFlow<Int?>,
    private val peripheral: BikePeripheral,
    private val rides: RideRepository,
) {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    private var acc: RideAccumulator? = null
    private var runner: WorkoutRunner? = null
    private var compliance: ComplianceTracker? = null
    private var pedalStreakTicks = 0
    private var idleTicks = 0
    private var autoPaused = false
    private var tickCount = 0L
    private var lastTickMs = 0L
    private var ftpAtStart = 0
    private val powerHistory = ArrayDeque<Float>()
    private var resistanceController: ResistanceController = ManualResistance
    private var resistanceControllerKey: String? = null

    companion object {
        const val TICK_MS = 250L
        private const val TICKS_PER_SEC = (1000 / TICK_MS).toInt()
        private const val AUTO_START_SEC = 3
        private const val MIN_SAVED_MOVING_SEC = 60
    }

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(TICK_MS)
            }
        }
    }

    // ---------- commands ----------

    fun startWorkout(workout: Workout, companionApp: String? = null, auto: Boolean = false) {
        if (_state.value.active) finish()
        val cfg = settings.current
        acc = RideAccumulator(cfg.calorieConfig)
        runner = WorkoutRunner(workout)
        compliance = ComplianceTracker(workout.segments.size)
        ftpAtStart = cfg.ftp
        powerHistory.clear()
        idleTicks = 0
        _state.value = _state.value.copy(
            phase = RidePhase.RIDING, workout = workout, startedAtMs = System.currentTimeMillis(),
            stats = RideStats(), companionApp = companionApp, autoStarted = auto, lastFinished = null,
            targetScore = null, progress = runner?.progressAt(0),
        )
    }

    fun justRide(companionApp: String? = null) = startWorkout(WorkoutLibrary.justRide, companionApp)

    fun setCompanionApp(label: String?) {
        if (_state.value.active && _state.value.companionApp == null) _state.value = _state.value.copy(companionApp = label)
    }

    fun pause() {
        if (_state.value.phase != RidePhase.RIDING) return
        autoPaused = false
        acc?.paused = true
        _state.value = _state.value.copy(phase = RidePhase.PAUSED)
    }

    fun resume() {
        if (_state.value.phase != RidePhase.PAUSED) return
        autoPaused = false
        acc?.paused = false
        idleTicks = 0
        _state.value = _state.value.copy(phase = RidePhase.RIDING)
    }

    fun togglePause() = if (_state.value.phase == RidePhase.PAUSED) resume() else pause()

    /** End the ride and save it (unless it was too short to be meaningful). */
    fun finish(save: Boolean = true) {
        val s = _state.value
        val a = acc
        val w = s.workout
        if (!s.active || a == null || w == null) return
        val stats = a.stats()
        val trace = a.trace()
        var newFtp: Int? = null
        if (w.segments.any { it.type == SegmentType.TEST }) {
            val idx = w.segments.indexOfFirst { it.type == SegmentType.TEST }
            val start = runner?.segmentStart(idx) ?: 0
            val testSec = w.segments[idx].durationSec
            val slice = trace.filter { it.t >= start && it.t < start + testSec }
            if (slice.size >= testSec * 0.9) {
                newFtp = PowerZones.ftpFrom20MinTest(slice.map { it.power }.average().toFloat())
                    .takeIf { it in 50..600 }
            }
        }
        val record = RideRecord(
            id = UUID.randomUUID().toString(),
            startedAtMs = s.startedAtMs,
            title = if (w.id == WorkoutLibrary.justRide.id) {
                s.companionApp?.let { "Ride with $it" } ?: "Free Ride · " + SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(s.startedAtMs))
            } else w.title,
            workoutId = w.id,
            stats = stats,
            targetScore = compliance?.score(),
            ftpAtRide = ftpAtStart,
            newFtp = newFtp,
            companionApp = s.companionApp,
            trace = trace,
        )
        acc = null
        runner = null
        compliance = null
        val keep = save && (stats.movingSec >= MIN_SAVED_MOVING_SEC)
        if (keep) {
            scope.launch { rides.save(record) }
            newFtp?.let { ftp -> settings.update { it.copy(ftp = ftp) } }
        }
        settings.saveResistanceModel(hub.resistanceModel)
        _state.value = RideState(
            sample = s.sample,
            lastFinished = if (keep) record.summary() else null,
            powerHistory = s.powerHistory,
            trainerTarget = s.trainerTarget,
        )
    }

    fun dismissSummary() {
        _state.value = _state.value.copy(lastFinished = null)
    }

    // ---------- engine ----------

    private fun tick() {
        val now = System.currentTimeMillis()
        val dtSec = if (lastTickMs == 0L) TICK_MS / 1000.0 else ((now - lastTickMs) / 1000.0).coerceIn(0.0, 2.0)
        lastTickMs = now
        tickCount++
        val cfg = settings.current
        val sample = hub.sample.value.copy(timestampMs = now, heartRateBpm = heartRate.value)
        var st = _state.value

        // Auto start: a few seconds of pedaling while idle begins a free ride.
        if (st.phase == RidePhase.IDLE && cfg.autoStartRide) {
            pedalStreakTicks = if (sample.isPedaling) pedalStreakTicks + 1 else 0
            if (pedalStreakTicks >= AUTO_START_SEC * TICKS_PER_SEC) {
                pedalStreakTicks = 0
                startWorkout(WorkoutLibrary.justRide, auto = true)
                st = _state.value
            }
        }

        var progress: WorkoutProgress? = null
        var eval: TargetEvaluation? = null
        var stats = st.stats
        val a = acc
        if (st.active && a != null) {
            a.add(sample)
            stats = a.stats()
            val r = runner
            if (r != null) {
                progress = r.progressAt(stats.elapsedSec)
                eval = WorkoutRunner.evaluate(progress.segment, sample, cfg.ftp)
                if (st.phase == RidePhase.RIDING) compliance?.record(progress.segmentIndex, eval, dtSec)
            }
            // Idle detection: free rides end themselves, structured ones pause.
            if (st.phase == RidePhase.RIDING) {
                idleTicks = if (sample.isPedaling) 0 else idleTicks + 1
                if (idleTicks >= cfg.autoEndIdleMinutes * 60 * TICKS_PER_SEC) {
                    idleTicks = 0
                    if (st.workout?.id == WorkoutLibrary.justRide.id) {
                        finish()
                        return
                    } else {
                        pause()
                        autoPaused = true
                        st = _state.value
                    }
                }
            }
            // A workout paused for inactivity resumes as soon as you pedal again.
            if (st.phase == RidePhase.PAUSED && autoPaused && sample.isPedaling) {
                resume()
                st = _state.value
            }
            if (progress?.finished == true && st.workout?.id != WorkoutLibrary.justRide.id) {
                finish()
                return
            }
        }

        if (tickCount % TICKS_PER_SEC == 0L) {
            powerHistory.addLast(sample.powerWatts)
            while (powerHistory.size > 180) powerHistory.removeFirst()
        }

        val trainer = peripheral.target.value
        val controller = resistanceController(cfg.autoResistance, cfg.autoResistanceTxCode)
        val suggestion = suggestion(sample, progress, trainer, controller.isAutomatic)
        if (controller.isAutomatic && suggestion != null && st.phase != RidePhase.PAUSED) controller.setTarget(suggestion.resistance)

        _state.value = st.copy(
            sample = sample,
            stats = stats,
            progress = progress ?: st.progress.takeIf { st.active },
            evaluation = eval,
            zone = PowerZones.zoneFor(sample.powerWatts, cfg.ftp),
            suggestion = suggestion,
            targetScore = compliance?.score(),
            powerHistory = powerHistory.toList(),
            trainerTarget = trainer,
        )

        // Broadcast to Zwift / Apple Watch / etc. at 2 Hz.
        if (tickCount % 2 == 0L) {
            peripheral.publish(
                IndoorBikeFrame(
                    speedKmh = SpeedModel.kmhFromPower(sample.powerWatts),
                    cadenceRpm = sample.cadenceRpm,
                    totalDistanceM = (stats.distanceMiles * 1609.344).roundToInt(),
                    resistanceLevel = sample.resistancePct,
                    powerW = sample.powerWatts.roundToInt(),
                    totalEnergyKcal = stats.calories.roundToInt(),
                    heartRate = sample.heartRateBpm,
                    elapsedSec = stats.elapsedSec,
                ),
                now,
            )
        }
        if (tickCount % (60 * TICKS_PER_SEC) == 0L) settings.saveResistanceModel(hub.resistanceModel)
    }

    private fun resistanceController(enabled: Boolean, txCode: Int?): ResistanceController {
        val source = hub.activeSource
        val key = "$enabled/$txCode/${source?.javaClass?.simpleName}"
        if (key != resistanceControllerKey) {
            resistanceControllerKey = key
            resistanceController = if (enabled && txCode != null && source is PelotonBikePlusSource) {
                BikePlusAutoResistance({ source.currentBinder() }, txCode)
            } else ManualResistance
        }
        return resistanceController
    }

    private fun suggestion(sample: BikeSample, progress: WorkoutProgress?, trainer: TrainerTarget, automatic: Boolean): Suggestion? {
        val cfg = settings.current
        val model = hub.resistanceModel
        val fromTrainer = if (cfg.followTrainerApps) when (trainer) {
            is TrainerTarget.Erg -> Suggestion(model.resistanceFor(trainer.watts.toFloat(), sample.cadenceRpm), "ERG ${trainer.watts} W", automatic)
            is TrainerTarget.Simulation -> Suggestion(cfg.terrain.resistanceForGrade(trainer.gradePct), "Grade %.1f%%".format(trainer.gradePct), automatic)
            is TrainerTarget.Resistance -> Suggestion(trainer.level.coerceIn(0f, 100f), "App resistance", automatic)
            TrainerTarget.None -> null
        } else null
        if (fromTrainer != null) return fromTrainer

        val seg = progress?.segment ?: return null
        seg.resistance?.let { range ->
            // Manual bikes already show the target range; only steer when outside it.
            if (!automatic && sample.resistancePct in range) return null
            val target = if (automatic) range.mid else sample.resistancePct.coerceIn(range.min.toFloat(), range.max.toFloat())
            return Suggestion(target, "Target ${range.min}-${range.max}", automatic)
        }
        seg.powerZone?.let { z ->
            val band = PowerZones.targetBand(z, cfg.ftp)
            val cadence = seg.cadence?.mid ?: sample.cadenceRpm.takeIf { it > 50f } ?: 85f
            val r = model.resistanceFor((band.first + band.last) / 2f, cadence)
            if (!automatic && abs(r - sample.resistancePct) < 3f) return null
            return Suggestion(r, "Zone $z · ${band.first}-${band.last} W", automatic)
        }
        return null
    }
}
