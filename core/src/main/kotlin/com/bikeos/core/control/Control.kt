package com.bikeos.core.control

import com.bikeos.core.metrics.ResistanceModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Auto-pilot: what the rider (or an app profile) wants the bike to do, independent of whatever app
 * is on screen. Produces a resistance target every tick; the ride engine sends it to the Bike+ motor
 * (through the safety limiter) or shows it as a knob prompt on the Bike.
 */
@Serializable
sealed class ControlMode {
    abstract val label: String

    @Serializable
    object Off : ControlMode() { override val label get() = "Off" }

    /** BikeOS ERG: hold this power regardless of cadence. */
    @Serializable
    data class TargetPower(val watts: Int) : ControlMode() { override val label get() = "Power $watts W" }

    @Serializable
    data class FixedResistance(val level: Int) : ControlMode() { override val label get() = "Resistance $level" }

    /** Keep heart rate in [low, high] by nudging resistance. */
    @Serializable
    data class HeartRateZone(val low: Int, val high: Int) : ControlMode() { override val label get() = "Heart rate $low-$high" }

    /** Step this mode's target up or down by one notch (the overlay's −/+ buttons). */
    fun nudge(up: Boolean): ControlMode = when (this) {
        Off -> this
        is TargetPower -> copy(watts = (watts + if (up) 10 else -10).coerceIn(50, 600))
        is FixedResistance -> copy(level = (level + if (up) 2 else -2).coerceIn(0, 100))
        is HeartRateZone -> {
            val d = if (up) 5 else -5
            copy(low = (low + d).coerceIn(80, 190), high = (high + d).coerceIn(90, 200))
        }
    }

    companion object {
        /** Modes offered when cycling through with the mode button. */
        fun cycle(current: ControlMode, ftp: Int, hasHeartRate: Boolean, currentResistance: Int): ControlMode = when (current) {
            Off -> TargetPower((ftp * 0.65f).roundToInt().coerceIn(60, 400))
            is TargetPower -> FixedResistance(currentResistance.coerceIn(10, 80))
            is FixedResistance -> if (hasHeartRate) HeartRateZone(125, 145) else Off
            is HeartRateZone -> Off
        }
    }
}

data class ControlOutput(val resistance: Float, val reason: String)

/**
 * Turns a [ControlMode] plus live readings into a resistance target.
 *  - TargetPower: feed-forward from the learned resistance curve plus a slow integral correction so the
 *    bike converges on the requested watts even if the curve is a little off.
 *  - HeartRateZone: every [hrStepSec] seconds, step resistance up if HR is below the zone, down if above.
 */
class AutoPilot(
    private val model: ResistanceModel,
    private val hrStepSec: Double = 15.0,
    private val hrStepPoints: Float = 3f,
) {
    private var integral = 0f
    private var hrResistance: Float? = null
    private var hrTimer = 0.0
    private var lastMode: ControlMode = ControlMode.Off

    fun reset() {
        integral = 0f; hrResistance = null; hrTimer = 0.0
    }

    fun update(mode: ControlMode, power: Float, cadence: Float, resistance: Float, heartRate: Int?, dtSec: Double): ControlOutput? {
        if (mode::class != lastMode::class) reset()
        lastMode = mode
        return when (mode) {
            ControlMode.Off -> null
            is ControlMode.FixedResistance -> ControlOutput(mode.level.toFloat(), "Hold resistance ${mode.level}")
            is ControlMode.TargetPower -> {
                val ff = model.resistanceFor(mode.watts.toFloat(), cadence)
                if (cadence >= 40f) {
                    // ~1 resistance point per 25 W of error, accumulated slowly; bounded so it can't run away.
                    integral = (integral + (mode.watts - power) / 25f * (dtSec / 10.0).toFloat()).coerceIn(-15f, 15f)
                }
                ControlOutput((ff + integral).coerceIn(0f, 100f), "Target ${mode.watts} W")
            }
            is ControlMode.HeartRateZone -> {
                val hr = heartRate ?: return ControlOutput(resistance, "Waiting for heart rate")
                val current = hrResistance ?: resistance
                hrTimer += dtSec
                var next = current
                if (hrTimer >= hrStepSec) {
                    hrTimer = 0.0
                    next = when {
                        hr < mode.low -> current + hrStepPoints
                        hr > mode.high -> current - hrStepPoints
                        else -> current
                    }.coerceIn(5f, 80f)
                }
                hrResistance = next
                ControlOutput(next, "HR zone ${mode.low}-${mode.high} (now $hr)")
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Per-app ride profiles
// ------------------------------------------------------------------------------------------

@Serializable
enum class OverlayStyle { FULL, COMPACT, HIDDEN }

@Serializable
sealed class ProfileProgram {
    @Serializable object None : ProfileProgram()
    @Serializable object JustRide : ProfileProgram()
    @Serializable data class Control(val mode: ControlMode) : ProfileProgram()
    @Serializable data class Workout(val workoutId: String) : ProfileProgram()
}

/** What happens to the bike when a given app is opened from BikeOS. */
@Serializable
data class AppProfile(
    val appKey: String, // catalog id or package name
    val overlay: OverlayStyle = OverlayStyle.FULL,
    val program: ProfileProgram = ProfileProgram.None,
    /** Follow resistance requests from Bluetooth trainer apps (Zwift etc.). */
    val followTrainer: Boolean = true,
)

object AppProfiles {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Sensible starting points: trainer apps drive the bike; video apps ride steady in the background. */
    val defaults: Map<String, AppProfile> = listOf(
        AppProfile("zwift", OverlayStyle.COMPACT, ProfileProgram.JustRide, followTrainer = true),
        AppProfile("mywhoosh", OverlayStyle.COMPACT, ProfileProgram.JustRide, followTrainer = true),
        AppProfile("rouvy", OverlayStyle.COMPACT, ProfileProgram.JustRide, followTrainer = true),
        AppProfile("trainerroad", OverlayStyle.COMPACT, ProfileProgram.JustRide, followTrainer = true),
        AppProfile("netflix", OverlayStyle.COMPACT, ProfileProgram.JustRide),
        AppProfile("youtube", OverlayStyle.FULL, ProfileProgram.JustRide),
        AppProfile("peloton", OverlayStyle.HIDDEN, ProfileProgram.None, followTrainer = false),
    ).associateBy { it.appKey }

    fun encode(map: Map<String, AppProfile>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AppProfile.serializer()), map.values.toList())

    fun decode(text: String?): Map<String, AppProfile> = if (text.isNullOrBlank()) defaults else
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AppProfile.serializer()), text).associateBy { it.appKey } }
            .getOrDefault(defaults)
}

// ------------------------------------------------------------------------------------------
// BikeOS Bridge: the on-tablet API for other Android apps
// ------------------------------------------------------------------------------------------

/**
 * Commands other apps on the bike can send (Android broadcast `com.bikeos.bridge.CONTROL`, protected by
 * the `com.bikeos.permission.CONTROL_BIKE` runtime permission). See docs/BRIDGE.md.
 */
sealed class BridgeCommand {
    data class SetPower(val watts: Int) : BridgeCommand()
    data class SetResistance(val level: Int) : BridgeCommand()
    data class SetGrade(val percent: Float) : BridgeCommand()
    object Clear : BridgeCommand()
    object StartRide : BridgeCommand()
    object PauseRide : BridgeCommand()
    object ResumeRide : BridgeCommand()
    object EndRide : BridgeCommand()

    companion object {
        const val ACTION_CONTROL = "com.bikeos.bridge.CONTROL"
        const val ACTION_METRICS = "com.bikeos.bridge.METRICS"
        const val PERMISSION_CONTROL = "com.bikeos.permission.CONTROL_BIKE"
        const val PERMISSION_READ = "com.bikeos.permission.READ_METRICS"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_VALUE = "value"
        const val EXTRA_APP = "app"

        /** Parses and validates a command from intent extras. Out-of-range values are rejected, not clamped. */
        fun parse(command: String?, value: Double?): BridgeCommand? = when (command?.lowercase()) {
            "power" -> value?.takeIf { it in 0.0..2000.0 }?.let { SetPower(it.roundToInt()) }
            "resistance" -> value?.takeIf { it in 0.0..100.0 }?.let { SetResistance(it.roundToInt()) }
            "grade" -> value?.takeIf { it in -25.0..25.0 }?.let { SetGrade(it.toFloat()) }
            "clear" -> Clear
            "start" -> StartRide
            "pause" -> PauseRide
            "resume" -> ResumeRide
            "end" -> EndRide
            else -> null
        }
    }
}
