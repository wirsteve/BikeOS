package com.bikeos.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.Permissions
import com.bikeos.graph
import com.bikeos.ride.Foreground
import com.bikeos.ui.screens.AllAppsScreen
import com.bikeos.ui.screens.AppleFitnessScreen
import com.bikeos.ui.screens.SafeModeScreen
import com.bikeos.ui.screens.VideoRideScreen
import com.bikeos.ui.screens.VideoSearchScreen
import com.bikeos.ui.screens.HistoryScreen
import com.bikeos.ui.screens.HomeScreen
import com.bikeos.ui.screens.LiveRideScreen
import com.bikeos.ui.screens.RideDetailScreen
import com.bikeos.ui.screens.SettingsScreen
import com.bikeos.ui.screens.WorkoutDetailScreen
import com.bikeos.ui.screens.WorkoutsScreen
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeOSTheme

sealed class Screen {
    object Home : Screen()
    object Workouts : Screen()
    data class WorkoutDetail(val id: String) : Screen()
    object Live : Screen()
    object History : Screen()
    data class RideDetail(val id: String) : Screen()
    object Settings : Screen()
    object AllApps : Screen()
    object AppleFitness : Screen()
    data class VideoRide(val ride: com.bikeos.core.video.VideoRide) : Screen()
    data class VideoSearch(val query: String, val scenic: Boolean) : Screen()
}

/** Simple back-stack navigator; BikeOS has few enough screens not to need a nav library. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    /** Selected top tab on the home screen (Home, Classes, Apps); survives visits to other screens. */
    var homeTab by androidx.compose.runtime.mutableIntStateOf(0)
    val current: Screen get() = stack.last()
    fun go(s: Screen) {
        if (s == Screen.Home) {
            stack.clear(); stack.add(Screen.Home)
        } else if (current != s) stack.add(s)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replace(s: Screen) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        go(s)
    }
}

/**
 * The BikeOS launcher. Registered as a HOME activity, so it replaces the stock Peloton
 * home screen once selected as the default (see README).
 */
class HomeActivity : ComponentActivity() {
    /** Exposed for on-device tests. */
    val nav = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        graph.ensureServiceRunning()
        if (!graph.safeMode && !Permissions.hasBluetooth(this)) Permissions.requestBluetooth(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                nav.back() // at the root, a launcher simply stays put
            }
        })

        setContent {
            BikeOSTheme {
                Box(Modifier.fillMaxSize().background(BikeColors.Background)) {
                    if (graph.safeMode) SafeModeScreen() else Router(nav)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already home returns to the root screen, like any launcher —
        // except mid-ride, where it returns to the live ride.
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            nav.go(Screen.Home)
            if (graph.ride.state.value.active) nav.go(Screen.Live)
        }
    }

    override fun onResume() {
        super.onResume()
        Foreground.bikeOsVisible.value = true
    }

    override fun onPause() {
        super.onPause()
        Foreground.bikeOsVisible.value = false
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_CODE) graph.restartBluetoothBroadcast()
        // requestCode 77 (video storage access) needs nothing here: the Videos tab re-checks on its own.
    }
}

@Composable
private fun Router(nav: Navigator) {
    val g = androidx.compose.ui.platform.LocalContext.current.graph
    val ride by g.ride.state.collectAsStateWithLifecycle()
    // When a ride is started from inside BikeOS, show the live dashboard.
    LaunchedEffect(ride.startedAtMs) {
        // (Video rides keep their own full-screen player instead.)
        if (ride.active && !ride.autoStarted && nav.current != Screen.Live && nav.current !is Screen.VideoRide) nav.go(Screen.Live)
    }
    when (val s = nav.current) {
        Screen.Home -> HomeScreen(nav)
        Screen.Workouts -> WorkoutsScreen(nav)
        is Screen.WorkoutDetail -> WorkoutDetailScreen(nav, s.id)
        Screen.Live -> LiveRideScreen(nav)
        Screen.History -> HistoryScreen(nav)
        is Screen.RideDetail -> RideDetailScreen(nav, s.id)
        Screen.Settings -> SettingsScreen(nav)
        Screen.AllApps -> AllAppsScreen(nav)
        Screen.AppleFitness -> AppleFitnessScreen(nav)
        is Screen.VideoRide -> VideoRideScreen(nav, s.ride)
        is Screen.VideoSearch -> VideoSearchScreen(nav, s.query, s.scenic)
    }
}
