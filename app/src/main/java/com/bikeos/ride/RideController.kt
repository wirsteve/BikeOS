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
    /** Auto-pilot mode set by the rider, an app profile, or the overlay. */
    val control: com.bikeos.core.control.ControlMode = com.bikeos.core.control.ControlMode.Off,
    /** Latest command from another app on the bike via the BikeOS Bridge (with the app's name). */
    val bridgeTarget: Pair<String, TrainerTarget>? = null,
    /** Set by an app profile: hide the floating overlay while that app is open. */
    val overlayHidden: Boolean = false,
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
    private val autoPilot = com.bikeos.core.control.AutoPilot(hub.resistanceModel)
    /** App profiles can turn off following Bluetooth trainer apps while a given app is open. */
    var followTrainerOverride: Boolean? = null

    // ---------- bike control from anywhere (overlay, app profiles, Bridge) ----------

    fun setControl(mode: com.bikeos.core.control.ControlMode) {
        _state.value = _state.value.copy(control = mode)
    }

    fun nudgeControl(up: Boolean) = setControl(_state.value.control.nudge(up))

    fun cycleControl() {
        val st = _state.value
        setControl(
            com.bikeos.core.control.ControlMode.cycle(
                st.control, settings.current.ftp, st.sample.heartRateBpm != null, st.sample.resistancePct.roundToInt(),
            ),
        )
    }

    fun setOverlayHidden(hidden: Boolean) {
        _state.value = _state.value.copy(overlayHidden = hidden)
    }

    /** A command from another app on the bike (already permission-checked and range-validated). */
    fun applyBridge(cmd: com.bikeos.core.control.BridgeCommand, app: String) {
        val label = app.ifBlank { "App" }
        when (cmd) {
            is com.bikeos.core.control.BridgeCommand.SetPower -> _state.value = _state.value.copy(bridgeTarget = label to TrainerTarget.Erg(cmd.watts))
            is com.bikeos.core.control.BridgeCommand.SetResistance -> _state.value = _state.value.copy(bridgeTarget = label to TrainerTarget.Resistance(cmd.level.toFloat()))
            is com.bikeos.core.control.BridgeCommand.SetGrade -> _state.value = _state.value.copy(bridgeTarget = label to TrainerTarget.Simulation(cmd.percent))
            com.bikeos.core.control.BridgeCommand.Clear -> _state.value = _state.value.copy(bridgeTarget = null)
            com.bikeos.core.control.BridgeCommand.StartRide -> if (!_state.value.active) justRide(label)
            com.bikeos.core.control.BridgeCommand.PauseRide -> pause()
            com.bikeos.core.control.BridgeCommand.ResumeRide -> resume()
            com.bikeos.core.control.BridgeCommand.EndRide -> finish()
        }
    }

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
        autoPilot.reset()
        followTrainerOverride = null
        _state.value = RideState(
            sample = s.sample,
            lastFinished = if (keep) record.summary() else null,
            powerHistory = s.powerHistory,
            trainerTarget = s.trainerTarget,
            // Auto-pilot and app commands end with the ride, so the bike never keeps a target nobody asked for.
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
        val controller = resistanceController(cfg.autoResistance, cfg.autoResistanceTxCode, cfg.autoResistanceMax)
        val automatic = controller.isAutomatic && !controller.faulted
        val autoOut = autoPilot.update(st.control, sample.powerWatts, sample.cadenceRpm, sample.resistancePct, sample.heartRateBpm, dtSec)
        val suggestion = suggestion(sample, progress, trainer, st.bridgeTarget, autoOut, automatic)
        if (automatic && suggestion != null && st.phase != RidePhase.PAUSED) {
            controller.setTarget(suggestion.resistance, sample.resistancePct, sample.cadenceRpm, hub.isFresh)
        }

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

    private fun resistanceController(enabled: Boolean, txCode: Int?, max: Int): ResistanceController {
        val source = hub.activeSource
        val key = "$enabled/$txCode/$max/${source?.javaClass?.simpleName}"
        if (key != resistanceControllerKey) {
            resistanceControllerKey = key
            resistanceController = if (enabled && source is PelotonBikePlusSource) {
                BikePlusAutoResistance.createIfSafe({ source.currentBinder() }, txCode, max, { hub.protocol })
            } else ManualResistance
        }
        return resistanceController
    }

    /** Surfaces in Settings when auto-resistance gave up after failures. */
    val autoResistanceFaulted: Boolean get() = resistanceController.faulted

    /**
     * Who controls the bike, highest priority first:
     *  1. a Bluetooth trainer app (Zwift, Rouvy…) sending ERG/SIM/resistance,
     *  2. another app on the bike via the BikeOS Bridge,
     *  3. the rider's auto-pilot (overlay / app profile),
     *  4. the structured class's targets.
     */
    private fun suggestion(
        sample: BikeSample, progress: WorkoutProgress?, trainer: TrainerTarget,
        bridge: Pair<String, TrainerTarget>?, autoOut: com.bikeos.core.control.ControlOutput?, automatic: Boolean,
    ): Suggestion? {
        val cfg = settings.current
        val model = hub.resistanceModel
        fun fromTarget(t: TrainerTarget, who: String): Suggestion? = when (t) {
            is TrainerTarget.Erg -> Suggestion(model.resistanceFor(t.watts.toFloat(), sample.cadenceRpm), "$who · ERG ${t.watts} W", automatic)
            is TrainerTarget.Simulation -> Suggestion(cfg.terrain.resistanceForGrade(t.gradePct), "$who · grade %.1f%%".format(t.gradePct), automatic)
            is TrainerTarget.Resistance -> Suggestion(t.level.coerceIn(0f, 100f), "$who · resistance ${t.level.roundToInt()}", automatic)
            TrainerTarget.None -> null
        }
        if (followTrainerOverride ?: cfg.followTrainerApps) fromTarget(trainer, "Trainer app")?.let { return it }
        bridge?.let { (app, t) -> fromTarget(t, app)?.let { return it } }
        autoOut?.let { return Suggestion(it.resistance, "Auto-pilot · ${it.reason}", automatic) }

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
