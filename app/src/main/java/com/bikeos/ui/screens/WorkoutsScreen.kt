package com.bikeos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.core.workout.Workout
import com.bikeos.core.workout.WorkoutCategory
import com.bikeos.graph
import com.bikeos.ui.Format
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Card
import com.bikeos.ui.components.LineChart
import com.bikeos.ui.components.SectionTitle
import com.bikeos.ui.theme.BikeColors

private val categoryLabels = mapOf(
    WorkoutCategory.INTERVALS to "Intervals & HIIT",
    WorkoutCategory.ENDURANCE to "Endurance",
    WorkoutCategory.CLIMB to "Climb",
    WorkoutCategory.POWER_ZONE to "Power Zone",
    WorkoutCategory.LOW_IMPACT to "Low Impact",
    WorkoutCategory.TEST to "FTP Test",
    WorkoutCategory.CUSTOM to "My workouts",
)

@Composable
fun WorkoutsScreen(nav: Navigator) {
    val g = LocalContext.current.graph
    val all by g.workouts.all.collectAsStateWithLifecycle()
    val errors by g.workouts.errors.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<WorkoutCategory?>(null) }
    ScreenScaffold("Workouts", nav) {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                WorkoutCategory.entries.filter { c -> all.any { it.category == c } }.forEach { c ->
                    FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(categoryLabels[c] ?: c.name) })
                }
            }
            if (errors.isNotEmpty()) Text("Couldn't read: " + errors.joinToString(), color = BikeColors.Bad, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(GridCells.Adaptive(340.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(all.filter { filter == null || it.category == filter }) { w ->
                    WorkoutCard(w) { nav.go(Screen.WorkoutDetail(w.id)) }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(w: Workout, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().height(190.dp), onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${w.durationMin} min", color = BikeColors.Accent, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(categoryLabels[w.category] ?: "", color = BikeColors.TextDim, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Difficulty(w.difficulty)
            }
            Spacer(Modifier.height(6.dp))
            Text(w.title, style = MaterialTheme.typography.titleLarge, color = BikeColors.Text)
            Text(w.description, color = BikeColors.TextDim, maxLines = 2)
            Spacer(Modifier.weight(1f))
            WorkoutProfile(w, Modifier.fillMaxWidth().height(36.dp))
        }
    }
}

@Composable
private fun Difficulty(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(10) { i ->
            Box(Modifier.width(6.dp).height(14.dp).background(if (i < level) BikeColors.Accent2 else BikeColors.Outline))
        }
    }
}

/** Intensity profile of a workout, from resistance targets or power zones. */
@Composable
fun WorkoutProfile(w: Workout, modifier: Modifier) {
    val values = remember(w) {
        w.segments.flatMap { s ->
            val v = s.powerZone?.let { z -> (z * 14f) } ?: s.resistance?.mid ?: 20f
            List((s.durationSec / 15).coerceAtLeast(1)) { v }
        }
    }
    LineChart(values, BikeColors.Accent2, modifier, maxValue = 100f)
}

@Composable
fun WorkoutDetailScreen(nav: Navigator, id: String) {
    val g = LocalContext.current.graph
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val w = remember(id) { g.workouts.find(id) }
    if (w == null) {
        LaunchedEffect(id) { nav.back() }
        return
    }
    ScreenScaffold(w.title, nav) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(0.4f)) {
                Text("${w.durationMin} minutes · ${categoryLabels[w.category]}", color = BikeColors.Accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(w.description, color = BikeColors.Text, style = MaterialTheme.typography.bodyLarge)
                if (w.usesPowerZones) {
                    Spacer(Modifier.height(8.dp))
                    Text("Power zones use your FTP of ${settings.ftp} W. Change it in Settings or take the FTP test.", color = BikeColors.TextDim)
                }
                Spacer(Modifier.height(16.dp))
                WorkoutProfile(w, Modifier.fillMaxWidth().height(120.dp))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { g.ride.startWorkout(w) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("Start", fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tip: after starting, tap \"Watch something while riding\" — targets and countdowns stay on screen over YouTube, Netflix or Twitch.",
                    color = BikeColors.TextDim,
                )
            }
            Column(Modifier.weight(0.6f).fillMaxHeight()) {
                SectionTitle("Segments")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(w.segments) { s ->
                        Card(Modifier.fillMaxWidth(), padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, color = BikeColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(describe(s, settings.ftp), color = BikeColors.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
}
