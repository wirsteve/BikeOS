package com.bikeos.ui.screens

import android.app.Activity
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bikeos.Permissions
import com.bikeos.graph
import com.bikeos.ui.theme.BikeColors

/**
 * Shown instead of the normal home screen after repeated crashes. In safe mode BikeOS does not touch
 * the bike's sensors, Bluetooth or resistance motor at all, and offers a one-tap way back to Peloton.
 */
@Composable
fun SafeModeScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    fun restart() {
        (ctx as? Activity)?.finishAffinity()
        Process.killProcess(Process.myPid()) // Android relaunches the home screen immediately
    }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("BikeOS safe mode", style = MaterialTheme.typography.displayMedium, color = BikeColors.Warn)
        Text(
            "BikeOS stopped unexpectedly several times, so it has paused itself. Nothing on your bike was changed: " +
                "BikeOS doesn't modify Peloton's software, and in safe mode it isn't talking to the bike at all.",
            color = BikeColors.Text, modifier = Modifier.widthIn(max = 760.dp),
        )
        Button(onClick = { g.openPeloton() }, modifier = Modifier.widthIn(min = 360.dp)) { Text("Open Peloton") }
        OutlinedButton(onClick = { g.settings.clearCrashes(); restart() }, modifier = Modifier.widthIn(min = 360.dp)) { Text("Try BikeOS again") }
        OutlinedButton(onClick = { g.settings.resetAll(); g.settings.clearCrashes(); restart() }, modifier = Modifier.widthIn(min = 360.dp)) {
            Text("Reset BikeOS settings and try again")
        }
        TextButton(onClick = {}) {
            Text(
                "To go back to stock Peloton completely: ${Permissions.ADB_UNINSTALL}  " +
                    "(Peloton becomes the home screen again automatically; your Peloton account and data are untouched).",
                color = BikeColors.TextDim,
            )
        }
    }
}
