package com.bikeos.core.workout

import com.bikeos.core.workout.SegmentType.*
import kotlinx.serialization.json.Json

/**
 * Built-in structured rides. All original BikeOS programming.
 * Users can add their own as JSON files (see README: "Custom workouts").
 */
object WorkoutLibrary {
    private fun r(a: Int, b: Int) = Range(a, b)
    private fun min(m: Int) = m * 60
    private fun seg(
        name: String, sec: Int, type: SegmentType, cad: Range? = null, res: Range? = null,
        zone: Int? = null, cue: String? = null,
    ) = Segment(name, sec, type, cad, res, zone, cue)

    private fun warmup(totalSec: Int = min(5)) = listOf(
        seg("Easy spin", totalSec / 2, WARMUP, r(75, 85), r(20, 30), cue = "Loosen up, find your rhythm"),
        seg("Build", totalSec - totalSec / 2, WARMUP, r(85, 95), r(30, 40), cue = "Gradually add resistance"),
    )

    private fun cooldown(totalSec: Int = min(4)) = listOf(
        seg("Cool down", totalSec, COOLDOWN, r(70, 80), r(15, 25), cue = "Bring the heart rate down"),
    )

    val justRide = Workout(
        id = "just-ride",
        title = "Just Ride",
        description = "Free ride with live metrics. Watch anything you like.",
        category = WorkoutCategory.ENDURANCE,
        segments = listOf(seg("Free ride", min(600), FLAT)),
        difficulty = 1,
    )

    private val hiit20 = Workout(
        id = "hiit-20",
        title = "20 min HIIT Sprints",
        description = "Six all-out sprints with full recoveries. Short and brutal.",
        category = WorkoutCategory.INTERVALS,
        difficulty = 8,
        segments = warmup(min(4)) + (1..6).flatMap { i ->
            listOf(
                seg("Sprint $i", 30, SPRINT, r(100, 120), r(45, 55), cue = "All out!"),
                seg("Recover", 90, RECOVERY, r(75, 85), r(25, 30), cue = "Breathe, spin easy"),
            )
        } + seg("Steady push", min(1), FLAT, r(85, 95), r(40, 45)) + cooldown(min(3)),
    )

    private val climb30 = Workout(
        id = "climb-30",
        title = "30 min Mountain Climb",
        description = "Long seated and standing climbs with rising resistance.",
        category = WorkoutCategory.CLIMB,
        difficulty = 7,
        segments = warmup() + listOf(
            seg("Foothills", min(3), CLIMB, r(70, 80), r(40, 45), cue = "Settle into the saddle"),
            seg("Steeper", min(3), CLIMB, r(65, 75), r(45, 50)),
            seg("Stand & climb", min(2), CLIMB, r(60, 70), r(50, 55), cue = "Out of the saddle"),
            seg("Recover", min(2), RECOVERY, r(80, 90), r(30, 35)),
            seg("Second ascent", min(3), CLIMB, r(65, 75), r(48, 53)),
            seg("Summit push", min(2), CLIMB, r(60, 70), r(55, 62), cue = "Dig deep, almost there"),
            seg("Descent", min(2), RECOVERY, r(90, 100), r(25, 30), cue = "Fast legs, light load"),
            seg("Final climb", min(4), CLIMB, r(65, 75), r(50, 60)),
        ) + cooldown(),
    )

    private val endurance45 = Workout(
        id = "endurance-45",
        title = "45 min Endurance",
        description = "Steady aerobic riding with tempo surges.",
        category = WorkoutCategory.ENDURANCE,
        difficulty = 6,
        segments = warmup() + (1..4).flatMap { i ->
            listOf(
                seg("Steady $i", min(6), FLAT, r(80, 90), r(35, 42)),
                seg("Tempo surge $i", min(2), INTERVAL, r(90, 100), r(42, 48), cue = "Pick it up"),
            )
        } + seg("Finish strong", min(4), FLAT, r(85, 95), r(40, 45)) + cooldown(),
    )

    private val lowImpact20 = Workout(
        id = "low-impact-20",
        title = "20 min Low Impact",
        description = "Gentle seated ride. Great for recovery days or beginners.",
        category = WorkoutCategory.LOW_IMPACT,
        difficulty = 2,
        segments = listOf(
            seg("Warm up", min(4), WARMUP, r(70, 80), r(20, 25)),
            seg("Flat road", min(5), FLAT, r(75, 85), r(25, 30)),
            seg("Gentle hill", min(3), CLIMB, r(65, 75), r(30, 35)),
            seg("Flat road", min(5), FLAT, r(75, 85), r(25, 30)),
            seg("Cool down", min(3), COOLDOWN, r(65, 75), r(15, 20)),
        ),
    )

    private val tabata15 = Workout(
        id = "tabata-15",
        title = "15 min Tabata",
        description = "Two Tabata blocks: 20 s on, 10 s off, eight rounds each.",
        category = WorkoutCategory.INTERVALS,
        difficulty = 9,
        segments = warmup(min(4)) + (1..2).flatMap { block ->
            (1..8).flatMap { i ->
                listOf(
                    seg("Block $block · On $i", 20, SPRINT, r(100, 115), r(40, 50), cue = "Go!"),
                    seg("Off", 10, RECOVERY, r(60, 80), r(25, 30)),
                )
            } + seg("Block rest", min(1), RECOVERY, r(70, 80), r(20, 25))
        } + cooldown(min(3)),
    )

    private val pzEndurance30 = Workout(
        id = "pz-endurance-30",
        title = "30 min Power Zone Endurance",
        description = "Zone 2-3 aerobic work to build your base. Set your FTP in Settings.",
        category = WorkoutCategory.POWER_ZONE,
        difficulty = 5,
        segments = listOf(
            seg("Zone 1 warm up", min(3), WARMUP, zone = 1),
            seg("Zone 2", min(2), WARMUP, zone = 2),
            seg("Zone 3 primer", 60, INTERVAL, zone = 3),
            seg("Zone 2", min(6), FLAT, zone = 2, cue = "Conversational pace"),
            seg("Zone 3", min(4), INTERVAL, zone = 3),
            seg("Zone 2", min(5), FLAT, zone = 2),
            seg("Zone 3", min(4), INTERVAL, zone = 3, cue = "Hold it steady"),
            seg("Zone 1 cool down", min(5), COOLDOWN, zone = 1),
        ),
    )

    private val pzMax45 = Workout(
        id = "pz-max-45",
        title = "45 min Power Zone Max",
        description = "Zone 5-7 efforts with full recovery. Hard!",
        category = WorkoutCategory.POWER_ZONE,
        difficulty = 9,
        segments = listOf(
            seg("Zone 1", min(3), WARMUP, zone = 1),
            seg("Zone 2", min(3), WARMUP, zone = 2),
            seg("Zone 4 opener", min(1), INTERVAL, zone = 4),
            seg("Zone 1", min(2), RECOVERY, zone = 1),
        ) + (1..5).flatMap { i ->
            listOf(
                seg("Zone 5 · effort $i", min(2), INTERVAL, zone = 5),
                seg("Zone 6 finish", 30, SPRINT, zone = 6),
                seg("Zone 1 recovery", 150, RECOVERY, zone = 1),
            )
        } + listOf(
            seg("Zone 7 sprint", 20, SPRINT, zone = 7, cue = "Everything you have"),
            seg("Zone 1", min(3), RECOVERY, zone = 1),
            seg("Zone 4 finisher", min(3), INTERVAL, zone = 4),
            seg("Zone 1 cool down", min(5) - 20, COOLDOWN, zone = 1),
        ),
    )

    val ftpTest = Workout(
        id = "ftp-test-20",
        title = "20 min FTP Test",
        description = "Warm up, then ride as hard as you can sustain for 20 minutes. " +
            "BikeOS sets your FTP to 95% of your 20 minute average power.",
        category = WorkoutCategory.TEST,
        difficulty = 10,
        segments = listOf(
            seg("Warm up", min(5), WARMUP, r(80, 90), r(25, 35)),
            seg("Primer", min(2), INTERVAL, r(90, 100), r(40, 50), cue = "Open the legs"),
            seg("Recover", min(3), RECOVERY, r(75, 85), r(25, 30)),
            seg("FTP TEST", min(20), TEST, cue = "Sustainable max effort. Pace yourself!"),
            seg("Cool down", min(5), COOLDOWN, r(70, 80), r(15, 25)),
        ),
    )

    val builtIns: List<Workout> = listOf(
        justRide, lowImpact20, hiit20, tabata15, climb30, endurance45, pzEndurance30, pzMax45, ftpTest,
    )

    val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }

    fun parse(text: String): List<Workout> {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[")) json.decodeFromString(trimmed) else listOf(json.decodeFromString<Workout>(trimmed))
    }

    fun toJson(w: Workout): String = json.encodeToString(Workout.serializer(), w)
}
