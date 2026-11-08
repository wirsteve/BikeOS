package com.bikeos.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bikeos.Permissions
import com.bikeos.R
import com.bikeos.graph
import com.bikeos.overlay.OverlayHud
import com.bikeos.ui.HomeActivity
import com.bikeos.ui.theme.BikeOSTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Tracks whether a BikeOS screen is in front, so the overlay hides while you're "home". */
object Foreground {
    val bikeOsVisible = MutableStateFlow(false)
}

/**
 * Keeps BikeOS alive while you're in other apps, and hosts the floating ride overlay that sits on
 * top of YouTube, Netflix, Twitch, Zwift, etc.
 */
class RideService : LifecycleService(), SavedStateRegistryOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var overlay: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("Ready. Start pedaling to begin a ride."))

        val g = graph
        lifecycleScope.launch {
            combine(g.ride.state, g.settings.state, Foreground.bikeOsVisible) { ride, s, home ->
                s.overlayEnabled && !home && (ride.active || s.overlayWhenIdle)
            }.distinctUntilChanged().collect { show -> if (show) showOverlay() else hideOverlay() }
        }
        lifecycleScope.launch {
            g.ride.state.collect { st ->
                val text = when (st.phase) {
                    RidePhase.IDLE -> "Ready. Start pedaling to begin a ride."
                    RidePhase.PAUSED -> "Paused · ${st.workout?.title ?: "Ride"}"
                    RidePhase.RIDING -> "${st.workout?.title ?: "Ride"} · ${st.stats.elapsedSec / 60} min · ${st.stats.workKj.toInt()} kJ"
                }
                if (text != lastNotificationText) {
                    lastNotificationText = text
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
    }

    private var lastNotificationText: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlay != null || !Permissions.canDrawOverlays(this)) return
        val s = graph.settings.current
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = s.overlayX
            y = s.overlayY
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@RideService)
            setViewTreeSavedStateRegistryOwner(this@RideService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                BikeOSTheme {
                    OverlayHud(
                        onDrag = { dx, dy -> moveOverlay(dx, dy) },
                        onDragEnd = { persistOverlayPosition() },
                        onOpenBikeOS = {
                            startActivity(Intent(this@RideService, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        },
                    )
                }
            }
        }
        runCatching { windowManager.addView(view, params) }.onSuccess {
            overlay = view
            overlayParams = params
        }
    }

    private fun moveOverlay(dx: Float, dy: Float) {
        val v = overlay ?: return
        val p = overlayParams ?: return
        p.x = (p.x + dx.toInt()).coerceAtLeast(0)
        p.y = (p.y + dy.toInt()).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    private fun persistOverlayPosition() {
        val p = overlayParams ?: return
        graph.settings.update { it.copy(overlayX = p.x, overlayY = p.y) }
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        overlayParams = null
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ride tracking", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("BikeOS")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_bike)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val NOTIFICATION_ID = 7
    }
}

/** Starts BikeOS after the tablet boots, even before the home screen is shown. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.graph.ensureServiceRunning()
        }
    }
}
