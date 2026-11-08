package com.bikeos.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.bikeos.core.peloton.PelotonService
import com.bikeos.ui.WebActivity

enum class AppSection(val title: String) { FITNESS("Train"), WATCH("Watch"), LISTEN("Listen"), HEALTH("Health & Nutrition"), TOOLS("Tools") }

/**
 * A curated home-screen tile. [packages] lists every known Android package for the service
 * (phone, tablet and TV builds differ); the first installed one is launched. If none are
 * installed and [webUrl] is set, BikeOS opens the web version in its built-in browser.
 */
data class CatalogApp(
    val id: String,
    val label: String,
    val section: AppSection,
    val packages: List<String>,
    val color: Color,
    val webUrl: String? = null,
    val desktopSite: Boolean = false,
    val note: String? = null,
    val special: Special? = null,
) {
    enum class Special { APPLE_FITNESS }
}

data class InstalledApp(val packageName: String, val label: String, val icon: Drawable?)

class AppCatalog(private val context: Context) {
    val entries: List<CatalogApp> = listOf(
        // ---- Train ----
        CatalogApp(
            "apple-fitness", "Apple Fitness+", AppSection.FITNESS, AIRPLAY_RECEIVERS, Color(0xFFB4FF39),
            note = "Apple only ships Fitness+ for iPhone, iPad and Apple TV. Play it on this screen with AirPlay " +
                "and let your Apple Watch record the bike's power and cadence.",
            special = CatalogApp.Special.APPLE_FITNESS,
        ),
        CatalogApp("zwift", "Zwift", AppSection.FITNESS, listOf("com.zwift.zwiftgame"), Color(0xFFFC6719),
            note = "Pair \"BikeOS Bike\" as your Power Source, Cadence and Controllable trainer."),
        CatalogApp("peloton", "Peloton", AppSection.FITNESS, listOf(PelotonService.PELOTON_APP_PACKAGE), Color(0xFFDF1C2F),
            note = "The original Peloton app, if you keep a membership."),
        CatalogApp("peloton-web", "Peloton App (web)", AppSection.FITNESS, emptyList(), Color(0xFFDF1C2F),
            webUrl = "https://members.onepeloton.com", desktopSite = true,
            note = "The Peloton App membership in the browser, with BikeOS tracking your real bike data."),
        CatalogApp("trainerroad", "TrainerRoad", AppSection.FITNESS, listOf("com.trainerroad.android"), Color(0xFFE5232B),
            webUrl = "https://www.trainerroad.com", note = "Pair \"BikeOS Bike\" as a smart trainer for ERG workouts."),
        CatalogApp("wahoo-systm", "Wahoo SYSTM", AppSection.FITNESS, listOf("com.wahoofitness.systm", "com.wahoofitness.sufferfest"), Color(0xFF0091FF),
            note = "Pair \"BikeOS Bike\" as a smart trainer."),
        CatalogApp("lesmills", "Les Mills+", AppSection.FITNESS, listOf("com.lesmills.ondemand", "com.lesmills.plus"), Color(0xFFB0B0B0),
            webUrl = "https://www.lesmills.com"),
        CatalogApp("ntc", "Nike Training Club", AppSection.FITNESS, listOf("com.nike.ntc"), Color(0xFFFFFFFF)),
        CatalogApp("strava", "Strava", AppSection.FITNESS, listOf("com.strava"), Color(0xFFFC4C02), webUrl = "https://www.strava.com/dashboard"),
        CatalogApp("mywhoosh", "MyWhoosh", AppSection.FITNESS, listOf("com.mywhoosh.whooshgame"), Color(0xFF00B2FF)),
        CatalogApp("rouvy", "ROUVY", AppSection.FITNESS, listOf("com.virtualtraining.rouvy.android", "cz.virtualtraining.rouvy"), Color(0xFFFFCC00)),
        CatalogApp("kinomap", "Kinomap", AppSection.FITNESS, listOf("com.kinomap.training", "com.kinomap.kinomapcommon"), Color(0xFF00C4B3)),
        CatalogApp("youtube-fitness", "YouTube Workouts", AppSection.FITNESS, listOf(YOUTUBE), Color(0xFFFF0000),
            webUrl = "https://m.youtube.com/results?search_query=indoor+cycling+class"),

        // ---- Watch ----
        CatalogApp("youtube", "YouTube", AppSection.WATCH, listOf(YOUTUBE, "com.google.android.youtube.tv"), Color(0xFFFF0000), webUrl = "https://m.youtube.com"),
        CatalogApp("youtube-tv", "YouTube TV", AppSection.WATCH, listOf("com.google.android.apps.youtube.unplugged"), Color(0xFFFF3D00),
            webUrl = "https://tv.youtube.com", desktopSite = true),
        CatalogApp("twitch", "Twitch", AppSection.WATCH, listOf("tv.twitch.android.app", "tv.twitch.android.viewer"), Color(0xFF9146FF), webUrl = "https://m.twitch.tv"),
        CatalogApp("netflix", "Netflix", AppSection.WATCH, listOf("com.netflix.mediaclient", "com.netflix.ninja"), Color(0xFFE50914)),
        CatalogApp("prime", "Prime Video", AppSection.WATCH, listOf("com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom"), Color(0xFF1399FF)),
        CatalogApp("disney", "Disney+", AppSection.WATCH, listOf("com.disney.disneyplus"), Color(0xFF113CCF)),
        CatalogApp("max", "Max", AppSection.WATCH, listOf("com.wbd.stream", "com.hbo.hbonow"), Color(0xFF002BE7)),
        CatalogApp("hulu", "Hulu", AppSection.WATCH, listOf("com.hulu.plus", "com.hulu.livingroomplus"), Color(0xFF1CE783)),
        CatalogApp("appletv", "Apple TV", AppSection.WATCH, listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.android.appletv"), Color(0xFFDDDDDD),
            webUrl = "https://tv.apple.com", desktopSite = true),
        CatalogApp("plex", "Plex", AppSection.WATCH, listOf("com.plexapp.android"), Color(0xFFE5A00D), webUrl = "https://app.plex.tv"),

        // ---- Listen ----
        CatalogApp("spotify", "Spotify", AppSection.LISTEN, listOf("com.spotify.music", "com.spotify.tv.android"), Color(0xFF1DB954),
            webUrl = "https://open.spotify.com", desktopSite = true),
        CatalogApp("applemusic", "Apple Music", AppSection.LISTEN, listOf("com.apple.android.music"), Color(0xFFFA2D48), webUrl = "https://music.apple.com", desktopSite = true),
        CatalogApp("ytmusic", "YouTube Music", AppSection.LISTEN, listOf("com.google.android.apps.youtube.music"), Color(0xFFFF0033), webUrl = "https://music.youtube.com"),
        CatalogApp("pandora", "Pandora", AppSection.LISTEN, listOf("com.pandora.android"), Color(0xFF3668FF), webUrl = "https://www.pandora.com"),
        CatalogApp("audible", "Audible", AppSection.LISTEN, listOf("com.audible.application"), Color(0xFFF7991C)),

        // ---- Health ----
        CatalogApp("mfp", "MyFitnessPal", AppSection.HEALTH, listOf("com.myfitnesspal.android"), Color(0xFF0066EE),
            webUrl = "https://www.myfitnesspal.com/food/diary",
            note = "BikeOS shows today's calories burned on the home screen so you can log them."),

        // ---- Tools ----
        CatalogApp("browser", "Browser", AppSection.TOOLS, emptyList(), Color(0xFF4FC3FF), webUrl = "https://www.google.com"),
        CatalogApp("store", "App Store (Aurora)", AppSection.TOOLS, listOf("com.aurora.store", "com.android.vending"), Color(0xFF34A853),
            webUrl = "https://auroraoss.com", note = "Install Android apps without Google Play using Aurora Store."),
        CatalogApp("airscreen", "AirPlay Receiver", AppSection.TOOLS, AIRPLAY_RECEIVERS, Color(0xFFBBBBBB),
            note = "Mirror an iPhone/iPad (AirPlay) or Android phone (Cast) onto the bike screen."),
    )

    private val pm: PackageManager get() = context.packageManager

    fun isInstalled(pkg: String): Boolean = pm.getLaunchIntentForPackage(pkg) != null

    fun installedPackage(app: CatalogApp): String? = app.packages.firstOrNull(::isInstalled)

    fun firstInstalled(packages: List<String>): String? = packages.firstOrNull(::isInstalled)

    /** Every launchable app on the tablet, for the "All apps" drawer. */
    fun installedApps(): List<InstalledApp> {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri -> InstalledApp(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), runCatching { ri.loadIcon(pm) }.getOrNull()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun icon(pkg: String): Drawable? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

    fun label(pkg: String): String = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun launchPackage(pkg: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openWeb(url: String, desktop: Boolean, title: String) {
        context.startActivity(
            Intent(context, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_URL, url)
                .putExtra(WebActivity.EXTRA_DESKTOP, desktop)
                .putExtra(WebActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    sealed class LaunchResult {
        object Launched : LaunchResult()
        object OpenedWeb : LaunchResult()
        data class NeedsInstall(val app: CatalogApp) : LaunchResult()
        data class ShowInfo(val app: CatalogApp) : LaunchResult()
    }

    fun launch(app: CatalogApp): LaunchResult {
        if (app.special == CatalogApp.Special.APPLE_FITNESS) return LaunchResult.ShowInfo(app)
        installedPackage(app)?.let { if (launchPackage(it)) return LaunchResult.Launched }
        app.webUrl?.let {
            openWeb(it, app.desktopSite, app.label)
            return LaunchResult.OpenedWeb
        }
        return LaunchResult.NeedsInstall(app)
    }

    /** Opens the best available store page for [pkg]: Aurora Store, Play Store, or the web. */
    fun openStorePage(pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(market) }.isSuccess
        if (!ok) openWeb("https://play.google.com/store/apps/details?id=$pkg", false, "Get app")
    }

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val AIRSCREEN = "com.ionitech.airscreen"
        /** AirPlay / screen-mirroring receivers, preferred first. */
        val AIRPLAY_RECEIVERS = listOf(AIRSCREEN, "com.softmedia.receiver.lite", "com.softmedia.receiver")
    }
}
