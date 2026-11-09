package com.alpha.assistant.capabilities

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.os.Bundle
import android.os.OutcomeReceiver
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphyActivity
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.SettingsPanel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val READ_STEPS = "android.permission.health.READ_STEPS"
private const val READ_SLEEP = "android.permission.health.READ_SLEEP"
private const val READ_EXERCISE = "android.permission.health.READ_EXERCISE"
private const val WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE"
private val readPermissions = listOf(READ_STEPS, READ_SLEEP, READ_EXERCISE)
private val workoutVersionLock = Any()

private fun nextWorkoutVersion(context: Context): Long = synchronized(workoutVersionLock) {
    // Health Connect requires a strictly newer version for an update, even after a clock change.
    val preferences = context.getSharedPreferences("health-workout", Context.MODE_PRIVATE)
    val version = Math.incrementExact(preferences.getLong("version", 0))
    check(preferences.edit().putLong("version", version).commit())
    version
}

private fun Context.healthPermission(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private enum class HealthMetric(val permission: String, val label: Int, val metric: android.health.connect.datatypes.AggregationType<Long>) {
    Steps(READ_STEPS, R.string.health_steps, StepsRecord.STEPS_COUNT_TOTAL),
    Sleep(READ_SLEEP, R.string.health_sleep, SleepSessionRecord.SLEEP_DURATION_TOTAL),
    Exercise(READ_EXERCISE, R.string.health_exercise, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL),
}

private enum class WorkoutType(val code: Int, val label: Int) {
    Walk(ExerciseSessionType.EXERCISE_SESSION_TYPE_WALKING, R.string.health_walk),
    Run(ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING, R.string.health_run),
    Other(ExerciseSessionType.EXERCISE_SESSION_TYPE_OTHER_WORKOUT, R.string.health_other),
}

private data class Workout(val type: WorkoutType, val start: Instant, val end: Instant, val zone: ZoneId, val title: String) {
    fun record(version: Long): ExerciseSessionRecord {
        // A later confirmation updates the same interval, including its title.
        val metadata = Metadata.Builder()
            .setRecordingMethod(Metadata.RECORDING_METHOD_MANUAL_ENTRY)
            .setClientRecordId("manual-${type.code}-${start.epochSecond}-${end.epochSecond}")
            .setClientRecordVersion(version)
            .build()
        return ExerciseSessionRecord.Builder(metadata, start, end, type.code)
            .setStartZoneOffset(zone.rules.getOffset(start))
            .setEndZoneOffset(zone.rules.getOffset(end))
            .apply { if (title.isNotBlank()) setTitle(title.trim()) }
            .build()
    }
}

private suspend fun <T> awaitHealth(operation: (OutcomeReceiver<T, HealthConnectException>) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        operation(object : OutcomeReceiver<T, HealthConnectException> {
            override fun onResult(result: T) = continuation.resume(result)
            override fun onError(error: HealthConnectException) = continuation.resumeWithException(error)
        })
    }

@Composable internal fun HealthSettings() {
    val context = LocalContext.current
    val service = remember(context) { context.getSystemService(HealthConnectManager::class.java) }
    if (service == null) {
        Text(stringResource(R.string.health_unavailable))
        return
    }
    val revision = platformAccessRevision()
    var refresh by remember { mutableIntStateOf(0) }
    val granted = remember(revision, refresh) { readPermissions.filter(context::healthPermission) }
    val canWrite = remember(revision, refresh) { context.healthPermission(WRITE_EXERCISE) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val firstDay = today.minusDays(6)
    var totals by remember(granted, refresh, revision, firstDay) { mutableStateOf<Map<HealthMetric, Long?>?>(null) }
    var readFailed by remember(granted, refresh, revision, firstDay) { mutableStateOf(false) }
    LaunchedEffect(granted, refresh, revision, firstDay) {
        val readable = HealthMetric.entries.filter { context.healthPermission(it.permission) }
        if (readable.isEmpty()) return@LaunchedEffect
        val range = TimeInstantRangeFilter.Builder().setStartTime(firstDay.atStartOfDay(zone).toInstant())
            .setEndTime(Instant.now()).build()
        val request = AggregateRecordsRequest.Builder<Long>(range).apply {
            readable.forEach { addAggregationType(it.metric) }
        }.build()
        try {
            val response = awaitHealth { service.aggregate(request, context.mainExecutor, it) }
            totals = readable.associateWith { response.get(it.metric) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            readFailed = true
        }
    }
    SettingsPanel {
        Text(stringResource(R.string.health_privacy))
        AndroidPermissionControl(stringResource(R.string.health_read_title), readPermissions,
            partial = R.string.health_partial_access, onChanged = { refresh++ }, divider = false)
        Text(stringResource(R.string.health_period, firstDay.toString(), today.toString()), style = MaterialTheme.typography.titleMedium)
        for (metric in HealthMetric.entries) {
            val value = when {
                metric.permission !in granted -> stringResource(R.string.health_no_access)
                readFailed -> stringResource(R.string.health_read_error)
                totals == null -> stringResource(R.string.health_loading)
                totals?.get(metric) == null -> stringResource(R.string.health_no_data)
                metric == HealthMetric.Steps -> totals?.get(metric).toString()
                else -> stringResource(R.string.health_minutes, Duration.ofMillis(checkNotNull(totals?.get(metric))).toMinutes())
            }
            Text(stringResource(R.string.health_metric, stringResource(metric.label), value))
        }
        if (granted.isNotEmpty()) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.health_refresh)) }
    }
    SettingsPanel {
        WorkoutForm(service, canWrite, onChanged = { refresh++ })
    }
}

@Composable private fun WorkoutForm(service: HealthConnectManager, canWrite: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var formOpen by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(WorkoutType.Walk) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var time by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var review by remember { mutableStateOf<Workout?>(null) }
    var saving by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<Int?>(null) }
    Text(stringResource(R.string.health_write_title), style = MaterialTheme.typography.titleMedium)
    if (!formOpen) {
        Button(onClick = { formOpen = true; feedback = null }) { Text(stringResource(R.string.health_record)) }
    } else if (!canWrite) {
        AndroidPermissionControl(stringResource(R.string.health_write_access), listOf(WRITE_EXERCISE), onChanged = onChanged, divider = false)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in WorkoutType.entries) {
                FilterChip(type == choice, { type = choice }, { Text(stringResource(choice.label)) }, enabled = !saving)
            }
        }
        OutlinedTextField(date, { date = it.take(10) }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
            label = { Text(stringResource(R.string.health_date)) })
        OutlinedTextField(time, { time = it.take(5) }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
            label = { Text(stringResource(R.string.health_time)) })
        OutlinedTextField(minutes, { minutes = it.take(4) }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.health_duration)) })
        OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
            label = { Text(stringResource(R.string.health_workout_title)) })
        Button(enabled = !saving, onClick = {
            feedback = null
            review = runCatching {
                val zone = ZoneId.systemDefault()
                val localStart = LocalDateTime.parse("${date}T$time")
                val offset = zone.rules.getValidOffsets(localStart).single()
                val start = localStart.toInstant(offset)
                val duration = requireNotNull(minutes.toLongOrNull()).also { require(it in 1..1440) }
                val end = start.plus(Duration.ofMinutes(duration))
                require(!end.isAfter(Instant.now()))
                Workout(type, start, end, zone, title)
            }.getOrNull()
            if (review == null) feedback = R.string.health_invalid_workout
        }) { Text(stringResource(if (saving) R.string.health_saving else R.string.health_review)) }
    }
    feedback?.let { Text(stringResource(it)) }
    review?.let { workout ->
        val format = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(workout.zone)
        AlertDialog(onDismissRequest = { review = null }, title = { Text(stringResource(R.string.health_review)) },
            text = { Text(stringResource(R.string.health_workout_summary, stringResource(workout.type.label),
                format.format(workout.start), format.format(workout.end)) + workout.title.trim().let { if (it.isEmpty()) "" else "\n$it" }) },
            dismissButton = { TextButton(onClick = { review = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    review = null
                    saving = true
                    scope.launch {
                        try {
                            val version = withContext(Dispatchers.IO) { nextWorkoutVersion(context) }
                            check(context.healthPermission(WRITE_EXERCISE))
                            awaitHealth { service.insertRecords(listOf(workout.record(version)), context.mainExecutor, it) }
                            feedback = R.string.health_saved
                            formOpen = false
                            time = ""
                            minutes = ""
                            title = ""
                            onChanged()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            feedback = R.string.health_write_error
                            onChanged()
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(stringResource(R.string.health_save)) }
            })
    }
}

class HealthPrivacyActivity : AlphyActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlphaTheme {
                Surface {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.health_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.health_privacy))
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.understood)) }
                    }
                }
            }
        }
    }
}
