package com.bikeos.ui.screens

import android.app.Activity
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bikeos.core.video.VideoCollection
import com.bikeos.core.video.VideoCollections
import com.bikeos.core.video.VideoLinks
import com.bikeos.core.video.VideoRide
import com.bikeos.core.video.VideoSource
import com.bikeos.graph
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Badge
import com.bikeos.ui.components.GhostButton
import com.bikeos.ui.components.IconCircleButton
import com.bikeos.ui.components.PrimaryButton
import com.bikeos.ui.components.RowHeader
import com.bikeos.ui.components.SurfaceCard
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeFonts
import com.bikeos.video.LocalVideo

/** The Videos tab: ride-along classes, scenic routes, your own videos, any link. */
@Composable
fun VideosTab(nav: Navigator) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val recents by g.videos.recents.collectAsStateWithLifecycle()
    var paste by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<List<LocalVideo>?>(null) }
    val tick by g.clock.collectAsStateWithLifecycle()
    val canRead = remember(tick / 5_000) { g.videos.canReadVideos() }
    LaunchedEffect(canRead) { if (canRead) local = g.videos.localVideos() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 12.dp)) {
        // Hero
        Box(
            Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1B0B2E), Color(0xFF0B2233), Color(0xFF09090C)))),
        ) {
            HeroGlow()
            Column(Modifier.align(Alignment.CenterStart).padding(36.dp)) {
                Badge("Ride-along video", color = BikeColors.Ice, textColor = Color.Black)
                Spacer(Modifier.height(12.dp))
                Text("Any video. Your bike. Every metric.", fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, color = Color.White, lineHeight = 50.sp)
                Text(
                    "Play classes, scenic rides or your own videos full-screen with live stats, class targets and resistance coaching. Rides are recorded automatically.",
                    style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp, end = 280.dp),
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Search YouTube", icon = Icons.Default.Search) { nav.go(Screen.VideoSearch("indoor cycling class", false)) }
                    GhostButton("Paste a link", icon = Icons.Default.Link) { paste = true }
                }
            }
        }

        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            RowHeader("Continue riding")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                recents.forEach { r -> VideoThumbCard(r) { nav.go(Screen.VideoRide(r)) } }
            }
        }

        Spacer(Modifier.height(32.dp))
        RowHeader("Ride-along classes")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VideoCollections.classes.forEach { c -> CollectionCard(c, Icons.Default.SportsScore) { nav.go(Screen.VideoSearch(c.query, c.scenic)) } }
        }

        Spacer(Modifier.height(32.dp))
        RowHeader("Scenic rides")
        Text("Video speed follows your pedaling: ride faster and the road moves faster; stop and it pauses.", style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VideoCollections.scenic.forEach { c -> CollectionCard(c, Icons.Default.Landscape) { nav.go(Screen.VideoSearch(c.query, c.scenic)) } }
        }

        Spacer(Modifier.height(32.dp))
        RowHeader("On this bike")
        when {
            !canRead -> SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = BikeColors.TextDim)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "Play your own ride videos: copy MP4s to the tablet's Movies or Download folder (adb push ride.mp4 /sdcard/Movies/), then allow access.",
                        style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(14.dp))
                    GhostButton("Allow access", height = 44.dp) { (ctx as? Activity)?.requestPermissions(arrayOf(g.videos.storagePermission), 77) }
                }
            }
            local.isNullOrEmpty() -> Text("No videos found in Movies or Download yet.", style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim)
            else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                local!!.forEach { v ->
                    val r = VideoRide(VideoSource.Media(v.uri, v.title))
                    VideoThumbCard(r, subtitle = durationLabel(v.durationMs)) { nav.go(Screen.VideoRide(r)) }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        RowHeader("Subscriptions")
        Text(
            "Peloton and Apple Fitness+ classes are their own paid content and play in their own apps: open the Peloton app or the Peloton App website, or follow the Apple Fitness+ setup. BikeOS keeps recording and floats your stats on top.",
            style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim, modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("peloton", "peloton-web", "apple-fitness", "youtube", "zwift").mapNotNull { id -> g.catalog.entries.firstOrNull { it.id == id } }.forEach { app ->
                ServiceCard(app) {
                    when (val r = g.catalog.launch(app)) {
                        is com.bikeos.apps.AppCatalog.LaunchResult.ShowInfo -> nav.go(Screen.AppleFitness)
                        is com.bikeos.apps.AppCatalog.LaunchResult.NeedsInstall -> app.packages.firstOrNull()?.let(g.catalog::openStorePage)
                        else -> Unit
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (paste) PasteLinkDialog(onDismiss = { paste = false }) { ride -> paste = false; nav.go(Screen.VideoRide(ride)) }
}

private fun durationLabel(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

@Composable
private fun HeroGlow() {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Brush.radialGradient(listOf(Color(0x664FC3FF), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.3f), radius = size.height * 1.2f),
            radius = size.height * 1.2f, center = Offset(size.width * 0.85f, size.height * 0.3f))
        drawCircle(Brush.radialGradient(listOf(Color(0x55B66BFF), Color.Transparent), center = Offset(size.width * 0.6f, size.height * 1.1f), radius = size.height),
            radius = size.height, center = Offset(size.width * 0.6f, size.height * 1.1f))
    }
}

@Composable
private fun CollectionCard(c: VideoCollection, icon: ImageVector, onClick: () -> Unit) {
    val hue = (c.id.hashCode() and 0xFFFF) / 65535f
    val base = Color.hsv(if (c.scenic) 140f + hue * 80f else (340f + hue * 40f) % 360f, 0.65f, 0.55f)
    Column(Modifier.width(280.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().height(158.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(base, Color(0xFF0B0B10))))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp)),
        ) {
            Icon(icon, null, tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).size(84.dp))
            Badge(if (c.scenic) "Scenic" else "Class", Modifier.align(Alignment.TopStart).padding(12.dp))
            Box(Modifier.align(Alignment.Center).size(52.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(c.title, style = MaterialTheme.typography.titleMedium, color = BikeColors.Text)
        Text(c.subtitle, style = MaterialTheme.typography.bodySmall, color = BikeColors.TextDim)
    }
}

@Composable
private fun VideoThumbCard(r: VideoRide, subtitle: String? = null, onClick: () -> Unit) {
    Column(Modifier.width(280.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(158.dp).clip(RoundedCornerShape(20.dp)).background(BikeColors.SurfaceHigh)) {
            when (val s = r.source) {
                is VideoSource.YouTube -> AsyncImage(
                    model = VideoLinks.youTubeThumbnail(s.videoId), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                is VideoSource.Media -> Icon(Icons.Default.Folder, null, tint = BikeColors.TextFaint, modifier = Modifier.align(Alignment.Center).size(56.dp))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            if (r.scenic) Badge("Scenic", Modifier.align(Alignment.TopStart).padding(12.dp), color = BikeColors.Good, textColor = Color.Black)
            Box(Modifier.align(Alignment.Center).size(52.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(r.source.title, style = MaterialTheme.typography.titleMedium, color = BikeColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle ?: if (r.source is VideoSource.YouTube) "YouTube" else "On this bike", style = MaterialTheme.typography.bodySmall, color = BikeColors.TextDim)
    }
}

@Composable
private fun PasteLinkDialog(onDismiss: () -> Unit, onRide: (VideoRide) -> Unit) {
    var text by remember { mutableStateOf("") }
    var scenic by remember { mutableStateOf(false) }
    val parsed = remember(text) { VideoLinks.parse(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BikeColors.SurfaceHigh,
        title = { Text("Ride to a video link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YouTube link, or a direct video link (MP4, HLS .m3u8, DASH .mpd).", color = BikeColors.TextDim)
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("https://youtu.be/…") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scenic mode (speed follows you)", modifier = Modifier.weight(1f), color = BikeColors.Text)
                    Switch(scenic, { scenic = it })
                }
                if (text.isNotBlank() && parsed == null) Text("That doesn't look like a playable video link.", color = BikeColors.Warn)
            }
        },
        confirmButton = { Button(enabled = parsed != null, onClick = { parsed?.let { onRide(VideoRide(it, scenic)) } }) { Text("Ride") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * YouTube search inside BikeOS. Tapping a video opens it in the ride-along player instead of the
 * YouTube page. (YouTube's mobile site changes pages without full reloads, so URL changes are
 * caught through the WebView's history callback as well.)
 */
@Composable
fun VideoSearchScreen(nav: Navigator, query: String, scenic: Boolean) {
    var handled by remember { mutableStateOf(false) }
    fun maybeOpen(url: String?, title: String?): Boolean {
        val id = url?.let(VideoLinks::youTubeId) ?: return false
        if (handled) return true
        handled = true
        val t = title?.removeSuffix(" - YouTube")?.takeIf { it.isNotBlank() && !it.startsWith("http") && it != "YouTube" } ?: "YouTube video"
        nav.replace(Screen.VideoRide(VideoRide(VideoSource.YouTube(id, t), scenic)))
        return true
    }
    Column(Modifier.fillMaxSize().background(BikeColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { nav.back() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (scenic) "Pick a scenic ride" else "Pick a class", style = MaterialTheme.typography.headlineSmall, color = BikeColors.Text)
                Text("Tap any video to ride along with BikeOS metrics" + if (scenic) " · scenic mode on" else "", style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim)
            }
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            maybeOpen(request.url.toString(), null)

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            maybeOpen(url, view.title)
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            if (maybeOpen(url, null)) view.stopLoading()
                        }
                    }
                    loadUrl("https://m.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8"))
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
