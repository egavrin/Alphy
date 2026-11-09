package com.alpha.assistant.courses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ReadingSpeechControls(voice: ReadingVoice, plan: CourseNarration, correct: Boolean?, revision: Long,
    uncovered: Boolean) {
    val scope = rememberCoroutineScope()
    val session = remember(voice, plan) { CourseSpeechSession(scope, voice::synthesize, voice::play) }
    val state by session.state.collectAsState()
    var configured by remember(voice) { mutableStateOf(voice.hasCredential()) }
    var configuring by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(false) }
    var firstEntry by remember(session) { mutableStateOf(true) }
    var observedRevision by remember(session) { mutableStateOf(revision) }
    DisposableEffect(voice, session) {
        val observer = voice.observeCredential {
            configured = voice.hasCredential()
            session.stop()
        }
        onDispose {
            observer.close()
            session.show(null, false)
        }
    }
    LifecycleResumeEffect(session) {
        foreground = true
        onPauseOrDispose {
            foreground = false
            session.stop()
        }
    }
    val active = foreground && uncovered && !configuring
    LaunchedEffect(session, active, configured) {
        if (!active || !configured) session.stop()
        if (active && configured && firstEntry) {
            firstEntry = false
            session.show(plan, true)
        }
    }
    LaunchedEffect(revision, session) {
        if (observedRevision != revision) {
            observedRevision = revision
            if (correct != null && active && configured) session.feedback(correct, revision)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!configured) {
            TextButton(onClick = { configuring = true }) { Text("Настроить голос Evgeny v2") }
        } else {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.active?.label?.let { "Звучит: $it" } ?: "Озвучиваем урок…",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.failed) {
                Text("Озвучка недоступна. Урок можно продолжить.",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = active, onClick = session::replay) { Text("Повторить") }
                if (state.busy) TextButton(onClick = session::stop) { Text("Остановить") }
                if (state.failed) TextButton(enabled = active, onClick = session::retry) { Text("Повторить загрузку") }
                if (state.failed) TextButton(onClick = { configuring = true }) { Text("Настроить голос") }
                for (cue in plan.examples) {
                    val label = cue.label ?: continue
                    if (state.active == cue) FilledTonalButton(onClick = { session.cue(cue) }, enabled = active) {
                        Text(label)
                    } else TextButton(onClick = { session.cue(cue) }, enabled = active) { Text(label) }
                }
                plan.answer?.let { answer ->
                    TextButton(enabled = active, onClick = { session.cue(answer) }) {
                        Text(if (correct == true) "Послушать ответ" else "Послушать подсказку")
                    }
                }
            }
        }
    }
    if (configuring) {
        var key by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!saving) configuring = false },
            title = { Text("Голос Evgeny v2") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Текст урока отправляется в ElevenLabs для озвучивания.")
                    OutlinedTextField(value = key, onValueChange = { key = it }, enabled = !saving, singleLine = true,
                        label = { Text("Ключ ElevenLabs") }, visualTransformation = PasswordVisualTransformation())
                    if (failed) Text("Проверь ключ и доступ к голосу.", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { configuring = false }) { Text("Отмена") } },
            confirmButton = {
                TextButton(enabled = key.isNotBlank() && !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            voice.saveCredential(key)
                            key = ""
                            configured = true
                            configuring = false
                            if (foreground && uncovered && !firstEntry) session.replay()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            saving = false
                        }
                    }
                }) { Text("Согласиться и сохранить") }
            })
    }
}
