package com.bikeos.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.graph
import com.bikeos.ui.Navigator
import com.bikeos.ui.theme.BikeColors

@Composable
fun AllAppsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val apps = remember { g.catalog.installedApps() }
    ScreenScaffold("All apps", nav) {
        Column {
            Text("Tap to open. Long-press to pin or unpin on the home screen.", color = BikeColors.TextDim)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(GridCells.Adaptive(136.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    val pinned = app.packageName in settings.pinnedApps
                    AppTile(
                        app.label, BikeColors.SurfaceHigh, app.icon, if (pinned) "PIN" else null,
                        onClick = {
                            g.ride.setCompanionApp(app.label)
                            g.applyProfile(app.packageName, app.label)
                            g.catalog.launchPackage(app.packageName)
                        },
                        onLongClick = {
                            g.settings.update {
                                it.copy(pinnedApps = if (pinned) it.pinnedApps - app.packageName else it.pinnedApps + app.packageName)
                            }
                            Toast.makeText(ctx, if (pinned) "Unpinned ${app.label}" else "Pinned ${app.label} to home", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}
