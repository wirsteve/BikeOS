package com.bikeos.core.video

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder

/** Something BikeOS can play in its ride-along video player. */
@Serializable
sealed class VideoSource {
    abstract val title: String

    /** Played through YouTube's official embedded player. */
    @Serializable
    data class YouTube(val videoId: String, override val title: String = "YouTube video") : VideoSource()

    /** A local file (content:// or file:// URI) or a direct http(s) MP4 / HLS / DASH link. */
    @Serializable
    data class Media(val uri: String, override val title: String) : VideoSource()
}

/** A video plus how to ride it. */
@Serializable
data class VideoRide(
    val source: VideoSource,
    /** Scenic mode: playback speed follows the rider's virtual speed. */
    val scenic: Boolean = false,
    /** Optional BikeOS structured class to run alongside (targets, coaching). */
    val workoutId: String? = null,
)

object VideoLinks {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val MEDIA_EXT = listOf(".mp4", ".m4v", ".webm", ".mkv", ".mov", ".m3u8", ".mpd", ".3gp")

    /** Extracts a YouTube video id from any common URL form, or returns null. */
    fun youTubeId(input: String): String? {
        val s = input.trim()
        if (ID.matches(s)) return s
        val uri = runCatching { URI(if (s.startsWith("http")) s else "https://$s") }.getOrNull() ?: return null
        val host = (uri.host ?: return null).lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val path = uri.rawPath ?: ""
        val candidate = when (host) {
            "youtu.be" -> path.trim('/').substringBefore('/')
            "youtube.com", "youtube-nocookie.com" -> when {
                path == "/watch" -> query(uri, "v")
                path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/") || path.startsWith("/v/") ->
                    path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    private fun query(uri: URI, key: String): String? = uri.rawQuery?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it[0] == key }
        ?.getOrNull(1)
        ?.let { URLDecoder.decode(it, "UTF-8") }

    /** True for links ExoPlayer can play directly. */
    fun isDirectMedia(input: String): Boolean {
        val s = input.trim().lowercase()
        if (s.startsWith("content://") || s.startsWith("file://")) return true
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false
        val path = s.substringBefore('?').substringBefore('#')
        return MEDIA_EXT.any { path.endsWith(it) }
    }

    /** Turns pasted text into a playable source, or null if it isn't one. */
    fun parse(input: String, title: String? = null): VideoSource? {
        youTubeId(input)?.let { return VideoSource.YouTube(it, title ?: "YouTube video") }
        if (isDirectMedia(input)) {
            val name = title ?: input.substringBefore('?').substringAfterLast('/').ifBlank { "Video" }
            return VideoSource.Media(input.trim(), name)
        }
        return null
    }

    fun youTubeThumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(list: List<VideoRide>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(VideoRide.serializer()), list)
    fun decode(text: String): List<VideoRide> =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(VideoRide.serializer()), text) }.getOrDefault(emptyList())
}

/**
 * Scenic mode: maps the rider's virtual speed to a video playback rate, the way virtual-ride apps
 * do. Smoothed so the picture doesn't stutter with every pedal stroke, paused when the rider stops.
 *
 * @param filmedAtMph the speed the footage was recorded at (roughly; ~18 mph for road cycling POV).
 */
class ScenicPacer(
    private val filmedAtMph: Float = 18f,
    private val minRate: Float = 0.25f,
    private val maxRate: Float = 2.0f,
    private val smoothing: Float = 0.15f,
) {
    /** 0 = paused (also the starting state, so the first reading applies immediately). */
    var rate: Float = 0f
        private set

    /** @return the playback rate to apply, or 0 to pause. */
    fun update(speedMph: Float, pedaling: Boolean): Float {
        if (!pedaling || speedMph < 1f) {
            rate = 0f
            return 0f
        }
        val target = (speedMph / filmedAtMph).coerceIn(minRate, maxRate)
        rate = if (rate == 0f) target else rate + (target - rate) * smoothing
        return rate
    }

    /** YouTube only accepts a fixed set of rates; pick the nearest. */
    fun nearestYouTubeRate(r: Float): Float = YT_RATES.minBy { kotlin.math.abs(it - r) }

    companion object {
        val YT_RATES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}

/** A curated starting point in the Videos tab: a YouTube search that opens inside BikeOS. */
data class VideoCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val query: String,
    val scenic: Boolean,
)

object VideoCollections {
    val classes = listOf(
        VideoCollection("cls-30", "30 min Indoor Cycling", "Coached rides from YouTube creators", "30 minute indoor cycling class", false),
        VideoCollection("cls-hiit", "HIIT Rides", "Short, sharp interval rides", "HIIT indoor cycling workout", false),
        VideoCollection("cls-rhythm", "Rhythm Rides", "Ride to the beat", "rhythm ride indoor cycling music", false),
        VideoCollection("cls-climb", "Climb Rides", "Heavy hills, low cadence", "indoor cycling climb ride", false),
        VideoCollection("cls-low", "Low Impact", "Easy recovery spins", "low impact indoor cycling recovery ride", false),
        VideoCollection("cls-pz", "Power Zone Training", "Zone-based endurance work", "power zone endurance ride indoor cycling", false),
        VideoCollection("cls-beginner", "Beginner Rides", "New to indoor cycling", "beginner indoor cycling class 20 minutes", false),
    )
    val scenic = listOf(
        VideoCollection("sc-alps", "Alpine Passes", "Switchbacks through the Alps", "virtual cycling Alps climb 4K POV", true),
        VideoCollection("sc-coast", "Coastal Roads", "Ocean views, rolling roads", "virtual cycling coast road 4K", true),
        VideoCollection("sc-forest", "Forest Trails", "Quiet woodland routes", "virtual cycling forest ride 4K", true),
        VideoCollection("sc-city", "City Rides", "Ride through world cities", "virtual cycling city ride 4K", true),
        VideoCollection("sc-tuscany", "Tuscany", "Cypress-lined gravel roads", "virtual cycling Tuscany 4K", true),
        VideoCollection("sc-norway", "Fjords", "Norwegian fjord roads", "virtual cycling Norway fjords", true),
    )
    val all = classes + scenic
}
