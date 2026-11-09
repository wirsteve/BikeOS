package com.bikeos.video

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.bikeos.core.video.VideoLinks
import com.bikeos.core.video.VideoRide
import com.bikeos.core.video.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class LocalVideo(val uri: String, val title: String, val durationMs: Long)

/** Recently played videos and the tablet's own video files. */
class VideoStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("bikeos_videos", Context.MODE_PRIVATE)
    private val _recents = MutableStateFlow(VideoLinks.decode(prefs.getString("recents", "[]")!!))
    val recents: StateFlow<List<VideoRide>> = _recents.asStateFlow()

    fun remember(ride: VideoRide) {
        val key = key(ride.source)
        val next = (listOf(ride) + _recents.value.filterNot { key(it.source) == key }).take(20)
        _recents.value = next
        prefs.edit().putString("recents", VideoLinks.encode(next)).apply()
    }

    fun rename(source: VideoSource, title: String) {
        if (title.isBlank()) return
        val key = key(source)
        val next = _recents.value.map { r ->
            if (key(r.source) != key) r else r.copy(
                source = when (val s = r.source) {
                    is VideoSource.YouTube -> s.copy(title = title)
                    is VideoSource.Media -> s.copy(title = title)
                },
            )
        }
        _recents.value = next
        prefs.edit().putString("recents", VideoLinks.encode(next)).apply()
    }

    private fun key(s: VideoSource) = when (s) {
        is VideoSource.YouTube -> "yt:" + s.videoId
        is VideoSource.Media -> "m:" + s.uri
    }

    val storagePermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun canReadVideos() = ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED

    /** Videos on the tablet (Movies, Download, DCIM, …), newest first. */
    suspend fun localVideos(): List<LocalVideo> = withContext(Dispatchers.IO) {
        if (!canReadVideos()) return@withContext emptyList()
        val out = mutableListOf<LocalVideo>()
        val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION)
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (c.moveToNext() && out.size < 200) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id))
                    out += LocalVideo(uri.toString(), c.getString(name)?.substringBeforeLast('.') ?: "Video", c.getLong(dur))
                }
            }
        }
        out
    }
}
