package com.bikeos.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.core.export.RideRecord
import com.bikeos.core.metrics.RideAccumulator
import com.bikeos.core.stats.Streaks
import com.bikeos.graph
import com.bikeos.ui.Format
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Card
import com.bikeos.ui.components.LineChart
import com.bikeos.ui.components.SectionTitle
import com.bikeos.ui.theme.BikeColors
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(nav: Navigator) {
    val g = LocalContext.current.graph
    val rides by g.rides.summaries.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val sum = remember(rides) { Streaks.summarize(rides) }
    ScreenScaffold("Ride history", nav) {
        Column {
            Card(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    StatBlock("Rides", sum.totalRides.toString())
                    StatBlock("Hours", "%.1f".format(sum.totalMinutes / 60f))
                    StatBlock("Total output", sum.totalKj.toString(), "kJ", BikeColors.Accent)
                    StatBlock("Distance", Format.distance(sum.totalMiles, settings.units), Format.distanceUnit(settings.units))
                    StatBlock("Best ride", sum.bestOutputKj.toString(), "kJ", BikeColors.Accent2)
                    StatBlock("Streak", sum.dayStreak.toString(), "days")
                }
            }
            Spacer(Modifier.height(16.dp))
            if (rides.isEmpty()) {
                Text("No rides yet. Start pedaling!", color = BikeColors.TextDim, style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rides, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth(), onClick = { nav.go(Screen.RideDetail(r.id)) }, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, color = BikeColors.Text, style = MaterialTheme.typography.titleMedium)
                                Text(Format.date(r.startedAtMs), color = BikeColors.TextDim)
                            }
                            StatBlock("Time", Format.clock(r.stats.elapsedSec), modifier = Modifier.padding(horizontal = 14.dp))
                            StatBlock("Output", Format.int(r.stats.workKj), "kJ", BikeColors.Accent, Modifier.padding(horizontal = 14.dp))
                            StatBlock("Avg W", Format.int(r.stats.avgPower), modifier = Modifier.padding(horizontal = 14.dp))
                            StatBlock("Score", r.targetScore?.let { "$it%" } ?: "–", modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RideDetailScreen(nav: Navigator, id: String) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var ride by remember { mutableStateOf<RideRecord?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { ride = g.rides.load(id) }
    val r = ride
    ScreenScaffold(r?.title ?: "Ride", nav, actions = {
        if (r != null) {
            Button(onClick = {
                scope.launch {
                    g.rides.shareTcx(id)?.let { ctx.startActivity(Intent.createChooser(it, "Export ride (TCX)").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }) { Text("Share TCX") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val f = g.rides.exportTcxToSharedFolder(id)
                    Toast.makeText(ctx, f?.let { "Saved ${it.absolutePath}" } ?: "Export failed", Toast.LENGTH_LONG).show()
                }
            }) { Text("Save file") }
            Spacer(Modifier.padding(4.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = BikeColors.Bad) }
        }
    }) {
        if (r == null) {
            Text("Loading…", color = BikeColors.TextDim)
            return@ScreenScaffold
        }
        val s = r.stats
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(Format.date(r.startedAtMs) + (r.companionApp?.let { " · watched $it" } ?: ""), color = BikeColors.TextDim)
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        StatBlock("Time", Format.clock(s.elapsedSec))
                        StatBlock("Output", Format.int(s.workKj), "kJ", BikeColors.Accent)
                        StatBlock("Calories", Format.int(s.calories), "kcal", BikeColors.Accent2)
                        StatBlock("Distance", Format.distance(s.distanceMiles, settings.units), Format.distanceUnit(settings.units))
                        StatBlock("Score", r.targetScore?.let { "$it%" } ?: "–", color = BikeColors.Good)
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        StatBlock("Avg / max power", "${Format.int(s.avgPower)} / ${Format.int(s.maxPower)}", "W", BikeColors.Power)
                        StatBlock("Normalized", Format.int(s.normalizedPower), "W")
                        StatBlock("Avg / max cadence", "${Format.int(s.avgCadence)} / ${Format.int(s.maxCadence)}", "rpm", BikeColors.Cadence)
                        StatBlock("Avg / max resistance", "${Format.int(s.avgResistance)} / ${Format.int(s.maxResistance)}", "%", BikeColors.Resistance)
                        StatBlock("Avg / max HR", "${s.avgHr ?: "–"} / ${s.maxHr ?: "–"}", "bpm", BikeColors.Heart)
                    }
                    val best20 = remember(r) { RideAccumulator.bestAverage(r.trace, 1200) }
                    val best5 = remember(r) { RideAccumulator.bestAverage(r.trace, 300) }
                    val best1 = remember(r) { RideAccumulator.bestAverage(r.trace, 60) }
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        StatBlock("Best 1 min", Format.int(best1), "W")
                        StatBlock("Best 5 min", Format.int(best5), "W")
                        StatBlock("Best 20 min", Format.int(best20), "W")
                        r.newFtp?.let { StatBlock("New FTP", it.toString(), "W", BikeColors.Accent) }
                    }
                }
            }
            Chart("Power", r.trace.map { it.power }, BikeColors.Power)
            Chart("Cadence", r.trace.map { it.cadence }, BikeColors.Cadence)
            Chart("Resistance", r.trace.map { it.resistance }, BikeColors.Resistance, max = 100f)
            if (r.trace.any { it.hr != null }) Chart("Heart rate", r.trace.map { (it.hr ?: 0).toFloat() }, BikeColors.Heart)
            Text("TCX files import into Strava, Garmin Connect, TrainingPeaks and intervals.icu with power, cadence and heart rate.", color = BikeColors.TextDim)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ride?") },
            confirmButton = { Button(onClick = { confirmDelete = false; scope.launch { g.rides.delete(id); nav.back() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Chart(title: String, values: List<Float>, color: Color, max: Float? = null) {
    // Down-sample long rides so the path stays cheap to draw.
    val pts = remember(values) {
        if (values.size <= 600) values else values.chunked(values.size / 600 + 1).map { c -> c.average().toFloat() }
    }
    Card(Modifier.fillMaxWidth().height(180.dp)) {
        Column {
            SectionTitle(title)
            LineChart(pts, color, Modifier.fillMaxSize(), maxValue = max)
        }
    }
}
