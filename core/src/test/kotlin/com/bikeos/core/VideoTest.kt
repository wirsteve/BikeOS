package com.bikeos.core

import com.bikeos.core.video.ScenicPacer
import com.bikeos.core.video.VideoLinks
import com.bikeos.core.video.VideoRide
import com.bikeos.core.video.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun parsesEveryYouTubeUrlShape() {
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&t=42s",
            "https://youtube.com/watch?feature=share&v=$id",
            "https://youtu.be/$id?si=abc",
            "youtu.be/$id",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id?rel=0",
            "https://www.youtube.com/live/$id",
            "https://music.youtube.com/watch?v=$id",
            "https://www.youtube-nocookie.com/embed/$id",
            id,
        ).forEach { assertEquals(it, id, VideoLinks.youTubeId(it)) }
    }

    @Test fun rejectsNonVideos() {
        assertNull(VideoLinks.youTubeId("https://www.youtube.com/results?search_query=cycling"))
        assertNull(VideoLinks.youTubeId("https://www.youtube.com/watch?v=short"))
        assertNull(VideoLinks.youTubeId("https://example.com/watch?v=$id"))
        assertNull(VideoLinks.parse("hello world"))
    }

    @Test fun directMedia() {
        assertTrue(VideoLinks.isDirectMedia("https://cdn.example.com/rides/alps.mp4?token=1"))
        assertTrue(VideoLinks.isDirectMedia("https://cdn.example.com/live/master.m3u8"))
        assertTrue(VideoLinks.isDirectMedia("content://media/external/video/media/12"))
        assertFalse(VideoLinks.isDirectMedia("https://example.com/page.html"))
        val m = VideoLinks.parse("https://cdn.example.com/rides/alps.mp4") as VideoSource.Media
        assertEquals("alps.mp4", m.title)
    }

    @Test fun recentsRoundTrip() {
        val list = listOf(VideoRide(VideoSource.YouTube(id, "Ride"), scenic = true, workoutId = "climb-30"), VideoRide(VideoSource.Media("file:///a.mp4", "a")))
        assertEquals(list, VideoLinks.decode(VideoLinks.encode(list)))
        assertEquals(emptyList<VideoRide>(), VideoLinks.decode("garbage"))
    }
}

class ScenicPacerTest {
    @Test fun followsSpeedSmoothlyAndPausesWhenStopped() {
        val p = ScenicPacer(filmedAtMph = 18f)
        assertEquals(1f, p.update(18f, true), 0.01f) // first update jumps straight to target
        var r = 1f
        repeat(60) { r = p.update(27f, true) }
        assertEquals(1.5f, r, 0.02f)
        assertEquals(0f, p.update(27f, false), 0f)
        assertEquals(2f, ScenicPacer().update(80f, true), 0f) // clamped
        assertEquals(0.25f, ScenicPacer().update(2f, true), 0f)
        assertEquals(1.5f, p.nearestYouTubeRate(1.43f), 0f)
    }
}
