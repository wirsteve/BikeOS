package com.bikeos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.bikeos.core.export.RideRecord
import com.bikeos.core.export.TcxWriter
import com.bikeos.core.workout.Workout
import com.bikeos.core.workout.WorkoutLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rides are stored as one JSON file each under the app's private storage. */
class RideRepository(private val context: Context) {
    private val dir = File(context.filesDir, "rides").apply { mkdirs() }
    private val _summaries = MutableStateFlow<List<RideRecord>>(emptyList())
    /** Newest first, without traces. */
    val summaries: StateFlow<List<RideRecord>> = _summaries.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _summaries.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { RideRecord.decode(f.readText()).summary() }.onFailure { Log.w("BikeOS", "bad ride ${f.name}", it) }.getOrNull() }
            .sortedByDescending { it.startedAtMs }
    }

    suspend fun save(record: RideRecord) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${record.id}.json.tmp")
        tmp.writeText(RideRecord.encode(record))
        tmp.renameTo(File(dir, "${record.id}.json"))
        refresh()
    }

    suspend fun load(id: String): RideRecord? = withContext(Dispatchers.IO) {
        File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { RideRecord.decode(it.readText()) }.getOrNull() }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        refresh()
    }

    /** Writes a TCX file and returns a share intent (Strava, Garmin Connect, Drive, email…). */
    suspend fun shareTcx(id: String): Intent? = withContext(Dispatchers.IO) {
        val ride = load(id) ?: return@withContext null
        val exports = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(ride.startedAtMs))
        val file = File(exports, "BikeOS_$stamp.tcx")
        file.writeText(TcxWriter.write(ride))
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.garmin.tcx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ride.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Also drop a copy where `adb pull` can reach it without root. */
    suspend fun exportTcxToSharedFolder(id: String): File? = withContext(Dispatchers.IO) {
        val ride = load(id) ?: return@withContext null
        val dir = context.getExternalFilesDir("exports") ?: return@withContext null
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(ride.startedAtMs))
        File(dir, "BikeOS_$stamp.tcx").apply { writeText(TcxWriter.write(ride)) }
    }
}

/**
 * Built-in workouts plus any *.json the user drops into
 * /sdcard/Android/data/com.bikeos/files/workouts (e.g. with `adb push`), plus ones built in-app.
 */
class WorkoutRepository(private val context: Context) {
    private val userDir: File? get() = context.getExternalFilesDir("workouts")
    private val internalDir = File(context.filesDir, "workouts").apply { mkdirs() }
    private val _all = MutableStateFlow(WorkoutLibrary.builtIns)
    val all: StateFlow<List<Workout>> = _all.asStateFlow()
    private val _errors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = _errors.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val errs = mutableListOf<String>()
        val custom = listOfNotNull(userDir, internalDir).flatMap { d ->
            d.listFiles { f -> f.extension == "json" }.orEmpty().flatMap { f ->
                runCatching { WorkoutLibrary.parse(f.readText()) }
                    .onFailure { errs += "${f.name}: ${it.message}" }
                    .getOrDefault(emptyList())
            }
        }
        _errors.value = errs
        _all.value = WorkoutLibrary.builtIns + custom.distinctBy { it.id }.filter { c -> WorkoutLibrary.builtIns.none { it.id == c.id } }
    }

    suspend fun saveCustom(w: Workout) = withContext(Dispatchers.IO) {
        File(internalDir, "${w.id}.json").writeText(WorkoutLibrary.toJson(w))
        refresh()
    }

    suspend fun deleteCustom(id: String) = withContext(Dispatchers.IO) {
        File(internalDir, "$id.json").delete()
        refresh()
    }

    fun find(id: String): Workout? = _all.value.firstOrNull { it.id == id }
}
