package com.bikeos.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bikeos.ride.Foreground
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeOSTheme

/**
 * Built-in browser for services without an Android app on the bike (or before you've
 * installed one): YouTube TV, Spotify Web, Twitch, MyFitnessPal and so on. Supports DRM
 * video (Widevine), full-screen video and a desktop-site toggle.
 */
class WebActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_DESKTOP = "desktop"
        const val EXTRA_TITLE = "title"
        // A current desktop Chrome UA; some services (YouTube TV, Spotify) only serve their full player to desktops.
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    private lateinit var web: WebView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: FrameLayout
    private var mobileUa: String = ""

    private var urlText by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(0)
    private var desktop by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val start = intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com"
        desktop = intent.getBooleanExtra(EXTRA_DESKTOP, false)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Browser"
        urlText = start

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mobileUa = settings.userAgentString
            if (desktop) settings.userAgentString = DESKTOP_UA
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return false
                    if (scheme == "http" || scheme == "https") return false
                    // intent://, market:// etc: hand off to an installed app if there is one.
                    return runCatching { startActivity(android.content.Intent.parseUri(request.url.toString(), android.content.Intent.URI_INTENT_SCHEME)) }.isSuccess
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    urlText = url
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    // Allow DRM (protected media) so subscription video can play.
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenView = view
                    fullscreenCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }

                override fun onHideCustomView() = exitFullscreen()
            }
            loadUrl(start)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> exitFullscreen()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        setContent {
            BikeOSTheme {
                Column(Modifier.fillMaxSize().background(BikeColors.Background)) {
                    Row(
                        Modifier.fillMaxWidth().background(BikeColors.Surface).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Close") }
                        IconButton(onClick = { if (web.canGoBack()) web.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        IconButton(onClick = { web.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                        Text(title, color = BikeColors.TextDim, modifier = Modifier.padding(horizontal = 8.dp))
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { web.loadUrl(normalize(urlText)) }),
                        )
                        IconButton(onClick = {
                            desktop = !desktop
                            web.settings.userAgentString = if (desktop) DESKTOP_UA else mobileUa
                            web.reload()
                        }) { Icon(if (desktop) Icons.Default.Computer else Icons.Default.PhoneAndroid, "Desktop site") }
                    }
                    if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(factory = {
                            FrameLayout(it).also { fl ->
                                root = fl
                                (web.parent as? ViewGroup)?.removeView(web)
                                fl.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            }
                        }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
    }

    private fun exitFullscreen() {
        fullscreenView?.let { root.removeView(it) }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // The browser is "another app" from the rider's point of view: keep the overlay visible.
        Foreground.bikeOsVisible.value = false
    }

    override fun onPause() {
        // Keep audio/video playing (e.g. Spotify web) while the user is elsewhere.
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
