package com.bikeos.ui.video

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** What the ride screen needs from any player. */
interface VideoControl {
    /** 0 pauses; anything else plays at that speed. */
    fun setRate(rate: Float)
    fun play()
    fun pause()
}

/** Files on the tablet and direct MP4 / HLS / DASH links, via Media3 ExoPlayer. */
@OptIn(UnstableApi::class)
@Composable
fun MediaVideoPlayer(
    uri: String,
    modifier: Modifier = Modifier,
    onControl: (VideoControl) -> Unit,
    onError: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error.errorCodeName)
        }
        player.addListener(listener)
        onControl(object : VideoControl {
            override fun setRate(rate: Float) {
                if (rate <= 0f) player.pause() else {
                    player.setPlaybackSpeed(rate)
                    if (!player.isPlaying) player.play()
                }
            }
            override fun play() = player.play()
            override fun pause() = player.pause()
        })
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { c ->
            (android.view.LayoutInflater.from(c).inflate(com.bikeos.R.layout.bikeos_player_view, null) as PlayerView).apply {
                this.player = player
                keepScreenOn = true
            }
        },
        modifier = modifier,
    )
}

/**
 * YouTube via its official IFrame embed player (the supported way for third-party apps to play
 * YouTube). Speed control uses the player's own setPlaybackRate.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun YouTubeVideoPlayer(
    videoId: String,
    modifier: Modifier = Modifier,
    onControl: (VideoControl) -> Unit,
    onError: (String) -> Unit,
    onTitle: (String) -> Unit = {},
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                val view = this
                addJavascriptInterface(object {
                    @JavascriptInterface fun error(code: String) = main.post {
                        onError(
                            when (code) {
                                "101", "150" -> "The owner of this video doesn't allow it to play inside other apps."
                                "100" -> "This video was removed or is private."
                                else -> "YouTube error $code"
                            },
                        )
                    }
                    @JavascriptInterface fun title(t: String) = main.post { onTitle(t) }
                }, "BikeOS")
                onControl(object : VideoControl {
                    override fun setRate(rate: Float) = main.post { view.evaluateJavascript("bikeRate($rate)", null) }.let { }
                    override fun play() = main.post { view.evaluateJavascript("bikeRate(1)", null) }.let { }
                    override fun pause() = main.post { view.evaluateJavascript("bikeRate(0)", null) }.let { }
                })
                loadDataWithBaseURL("https://bikeos.app/", youTubeHtml(videoId), "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

private fun youTubeHtml(id: String) = """
<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script>
var player = null;
var tag = document.createElement('script'); tag.src = 'https://www.youtube.com/iframe_api';
document.head.appendChild(tag);
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: '$id',
    playerVars: { autoplay: 1, playsinline: 1, rel: 0, modestbranding: 1, controls: 1, fs: 0, origin: 'https://bikeos.app' },
    events: {
      onReady: function(e) { e.target.playVideo(); try { BikeOS.title(e.target.getVideoData().title || ''); } catch (x) {} },
      onError: function(e) { BikeOS.error(String(e.data)); }
    }
  });
}
function bikeRate(r) {
  if (!player || !player.getPlayerState) return;
  if (r <= 0) { player.pauseVideo(); return; }
  player.setPlaybackRate(r);
  if (player.getPlayerState() !== 1) player.playVideo();
}
</script></body></html>
""".trimIndent()
