package com.bikeos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeos.core.workout.Workout
import com.bikeos.core.workout.WorkoutCategory
import com.bikeos.core.workout.WorkoutLibrary
import com.bikeos.graph
import com.bikeos.ui.Format
import com.bikeos.ui.Navigator
import com.bikeos.ui.Screen
import com.bikeos.ui.components.Badge
import com.bikeos.ui.components.ClassArt
import com.bikeos.ui.components.ClassCard
import com.bikeos.ui.components.DifficultyDots
import com.bikeos.ui.components.IconCircleButton
import com.bikeos.ui.components.PrimaryButton
import com.bikeos.ui.components.SectionTitle
import com.bikeos.ui.components.categoryColor
import com.bikeos.ui.components.categoryLabel
import com.bikeos.ui.components.difficultyLabel
import com.bikeos.ui.theme.BikeColors
import com.bikeos.ui.theme.BikeFonts

/** Standalone screen (e.g. from deep links); the home screen's Classes tab shows the same library. */
@Composable
fun WorkoutsScreen(nav: Navigator) {
    ScreenScaffold("Classes", nav) { ClassLibrary(nav) }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.Black else BikeColors.Text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else BikeColors.SurfaceHigh)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** Class library: category filters over a grid of class cards. */
@Composable
fun ClassLibrary(nav: Navigator) {
    val g = LocalContext.current.graph
    val all by g.workouts.all.collectAsStateWithLifecycle()
    val errors by g.workouts.errors.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<WorkoutCategory?>(null) }
    var maxMin by remember { mutableStateOf<Int?>(null) }
    val classes = all.filter { it.id != WorkoutLibrary.justRide.id }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill("All", filter == null) { filter = null }
            WorkoutCategory.entries.filter { c -> classes.any { it.category == c } }.forEach { c ->
                FilterPill(categoryLabel(c), filter == c) { filter = if (filter == c) null else c }
            }
            Spacer(Modifier.width(20.dp))
            listOf(20, 30, 45).forEach { m -> FilterPill("≤ $m min", maxMin == m) { maxMin = if (maxMin == m) null else m } }
        }
        if (errors.isNotEmpty()) Text("Couldn't read: " + errors.joinToString(), color = BikeColors.Bad, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(300.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(classes.filter { (filter == null || it.category == filter) && (maxMin == null || it.durationMin <= maxMin!!) }) { w ->
                ClassCard(w, artHeight = 170.dp) { nav.go(Screen.WorkoutDetail(w.id)) }
            }
        }
    }
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
    Row(Modifier.fillMaxSize().background(BikeColors.Background)) {
        // Hero
        ClassArt(w, Modifier.weight(0.58f).fillMaxHeight().padding(24.dp)) {
            IconCircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.align(Alignment.TopStart).padding(20.dp), background = Color.Black.copy(alpha = 0.35f)) { nav.back() }
            Column(Modifier.align(Alignment.BottomStart).padding(40.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(categoryLabel(w.category), color = categoryColor(w.category), textColor = Color.Black)
                    Badge("${w.durationMin} min")
                }
                Spacer(Modifier.height(14.dp))
                Text(w.title, fontFamily = BikeFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, color = Color.White, lineHeight = 64.sp)
                Spacer(Modifier.height(8.dp))
                Text(w.description, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(difficultyLabel(w.difficulty), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    DifficultyDots(w.difficulty, Color.White)
                }
                if (w.usesPowerZones) {
                    Spacer(Modifier.height(8.dp))
                    Text("Power zones use your FTP of ${settings.ftp} W", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.height(28.dp))
                PrimaryButton("Start", Modifier.width(260.dp), icon = Icons.Default.PlayArrow, height = 64.dp) { g.ride.startWorkout(w) }
            }
        }
        // Class plan
        Column(Modifier.weight(0.42f).fillMaxHeight().padding(top = 40.dp, end = 40.dp, bottom = 24.dp)) {
            SectionTitle("Segments")
            Text("${w.segments.size} segments · ${Format.clock(w.durationSec)}", style = MaterialTheme.typography.bodyMedium, color = BikeColors.TextDim)
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(w.segments) { i, s ->
                    val intensity = s.powerZone?.let { (it - 1) / 6f } ?: s.resistance?.let { ((it.mid - 15f) / 50f).coerceIn(0f, 1f) } ?: 0.2f
                    val c = BikeColors.zoneColors[(intensity * 6).toInt().coerceIn(0, 6)]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (i % 2 == 0) BikeColors.Surface else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 4.dp, height = 32.dp).clip(RoundedCornerShape(50)).background(c))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall, color = BikeColors.Text)
                            Text(describe(s, settings.ftp).substringBeforeLast(" · "), style = MaterialTheme.typography.bodySmall, color = BikeColors.TextDim)
                        }
                        Text(Format.clock(s.durationSec), fontFamily = BikeFonts.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = BikeColors.Text)
                    }
                }
            }
        }
    }
}

/** Intensity profile of a workout (kept for other screens). */
@Composable
fun WorkoutProfile(w: Workout, modifier: Modifier) {
    ClassArt(w, modifier)
}
