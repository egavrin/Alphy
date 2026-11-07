package com.alpha.assistant.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

@Composable internal fun AgentTestDialog(
    definition: AutomationDefinition,
    run: suspend (AutomationDefinition, Long, CloudSession) -> JSONObject,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(definition) { CloudSession() }
    val target = remember(definition) {
        val now = System.currentTimeMillis()
        definition.nextAfter(now) ?: now
    }
    val time = remember(definition, target) { Instant.ofEpochMilli(target).atZone(definition.zone()).toString() }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    DisposableEffect(session) { onDispose { session.cancel() } }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(definition.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(checkNotNull(definition.agent).instruction)
                Text(time)
                Text(stringResource(R.string.automation_agent_test_scope))
                if (running) Text(stringResource(R.string.automation_test_running))
                result?.let { Text(it) }
            }
        },
        confirmButton = {
            if (result == null) Button(enabled = !running, onClick = {
                running = true
                scope.launch {
                    try { result = run(definition, target, session).getString("text") }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: CloudFailure) { result = failure.message }
                    catch (_: Exception) { result = "Не удалось проверить задачу Alphy." }
                    finally { running = false }
                }
            }) { Text(stringResource(R.string.automation_test_run)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}
