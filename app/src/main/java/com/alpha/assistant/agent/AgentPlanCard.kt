package com.alpha.assistant.agent

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.PHOTO_CAPTURE
import com.alpha.assistant.capabilities.PhotoCaptureDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AgentChatHost(agent: AgentConversation, chatId: String?) {
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(agent, activity) {
        activity?.let { agent.host(it, true) }
        onDispose { activity?.let { agent.host(it, false) } }
    }
    LaunchedEffect(agent, chatId) { agent.chatChanged(chatId) }
    agent.cameraFor(activity)?.let { PhotoCaptureDialog(it) }
}

@Composable
internal fun AgentPlanCard(agent: AgentConversation, plan: AgentPlan) {
    var edit by rememberSaveable(plan.id, plan.revision) { mutableStateOf(false) }
    var instruction by rememberSaveable(plan.id, plan.revision) { mutableStateOf("") }
    var permissionPlan by remember { mutableStateOf<AgentPlan?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPlan?.let(agent::refreshPermissions)
        permissionPlan = null
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.agent_plan_title), style = MaterialTheme.typography.titleMedium)
            if (agent.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (agent.error) Text(stringResource(R.string.agent_plan_error), color = MaterialTheme.colorScheme.error)
            AgentStatus(plan)
            if (plan.phase == AgentPhase.NEEDS_PERMISSION) {
                plan.permissions.map { permission -> when (permission) {
                    Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> R.string.agent_permission_calendar
                    Manifest.permission.POST_NOTIFICATIONS -> R.string.agent_permission_notification
                    else -> if (plan.steps.any { it.command.function == PHOTO_CAPTURE })
                        R.string.agent_permission_photo else R.string.agent_permission_flashlight
                } }.distinct().forEach { Text(stringResource(it)) }
                Button(onClick = { permissionPlan = plan; launcher.launch(plan.permissions.toTypedArray()) },
                    enabled = !agent.busy && permissionPlan == null) { Text(stringResource(R.string.agent_permission_allow)) }
            } else plan.steps.forEachIndexed { index, step ->
                AgentStepCard(agent, plan, index, step)
            }
            when {
                plan.phase == AgentPhase.RUNNING -> Unit
                plan.phase == AgentPhase.WAITING_FOR_CAMERA -> {
                    Button(onClick = { agent.execute(plan) }, enabled = !agent.busy && agent.canContinue(plan)) {
                        Text(stringResource(R.string.agent_continue_camera))
                    }
                    TextButton(onClick = { agent.stop(plan) }, enabled = !agent.busy) { Text(stringResource(R.string.cancel)) }
                }
                plan.phase in AgentRuntime.editable -> {
                    if (edit) {
                        OutlinedTextField(instruction, { if (it.length <= 2000) instruction = it },
                            label = { Text(stringResource(R.string.agent_edit_instruction)) }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { agent.revise(plan, instruction) }, enabled = instruction.isNotBlank() && !agent.busy) {
                            Text(stringResource(R.string.agent_revise))
                        }
                    } else {
                        if (plan.phase == AgentPhase.REVIEW) Button(onClick = { agent.execute(plan) }, enabled = !agent.busy) {
                            Text(stringResource(R.string.agent_execute))
                        }
                        TextButton(onClick = { edit = true }, enabled = !agent.busy) { Text(stringResource(R.string.agent_edit)) }
                    }
                    TextButton(onClick = { agent.dismiss(plan) }, enabled = !agent.busy) { Text(stringResource(R.string.cancel)) }
                }
                else -> TextButton(onClick = { agent.dismiss(plan) }) { Text(stringResource(R.string.agent_close)) }
            }
        }
    }
}

@Composable
internal fun AgentRunBar(agent: AgentConversation) {
    val run = agent.plan?.takeIf { it.phase == AgentPhase.RUNNING } ?: return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.agent_running, (run.results.size + 1).coerceAtMost(run.steps.size), run.steps.size),
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { agent.stop(run) }, enabled = !run.stopRequested) { Text(stringResource(R.string.agent_stop)) }
        }
    }
}

@Composable
private fun AgentStatus(plan: AgentPlan) {
    val status = when (plan.phase) {
        AgentPhase.NEEDS_PERMISSION -> stringResource(R.string.agent_permission_reason)
        AgentPhase.NEEDS_SELECTION -> stringResource(R.string.agent_select_target)
        AgentPhase.UNAVAILABLE -> stringResource(R.string.agent_unavailable)
        AgentPhase.RUNNING -> stringResource(R.string.agent_running, (plan.results.size + 1).coerceAtMost(plan.steps.size), plan.steps.size)
        AgentPhase.WAITING_FOR_CAMERA -> stringResource(R.string.agent_waiting_for_camera)
        AgentPhase.COMPLETED -> stringResource(R.string.agent_completed)
        AgentPhase.FAILED -> stringResource(R.string.agent_failed)
        AgentPhase.STOPPED -> stringResource(R.string.agent_stopped)
        AgentPhase.INTERRUPTED -> stringResource(R.string.agent_interrupted)
        AgentPhase.REVIEW -> return
    }
    Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun AgentPhotoCard(agent: AgentConversation, conversation: String, photo: AgentPhoto) {
    val image by produceState<android.graphics.Bitmap?>(null, photo) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(agent.photoFile(photo).path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    }
    val title = stringResource(R.string.agent_photo_title, stringResource(photo.lens.label))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            image?.let { Image(it.asImageBitmap(), stringResource(R.string.tool_camera_review_image), Modifier.fillMaxWidth().height(220.dp)) }
            TextButton(onClick = { agent.deletePhoto(conversation, photo) }) { Text(stringResource(R.string.agent_delete_photo)) }
        }
    }
}

@Composable
private fun AgentStepCard(agent: AgentConversation, plan: AgentPlan, index: Int, step: AgentStep) {
    val target = step.target
    if (target == null) {
        if (step.options.isEmpty()) Text(stringResource(R.string.agent_unavailable))
        else step.options.forEachIndexed { option, candidate ->
            Text(candidate.label)
            if (candidate.before.isNotEmpty()) Text(stringResource(R.string.agent_before, candidate.before))
            if (candidate.after.isNotEmpty()) Text(stringResource(R.string.agent_after, candidate.after))
            if (candidate.available) TextButton(onClick = { agent.select(plan, index, option) }, enabled = !agent.busy) {
                Text(stringResource(R.string.agent_select))
            }
        }
        return
    }
    Text("${index + 1}. ${target.label}", style = MaterialTheme.typography.titleSmall)
    if (target.before.isNotEmpty()) Text(stringResource(R.string.agent_before, target.before))
    if (target.after.isNotEmpty()) Text(stringResource(R.string.agent_after, target.after))
    if (step.command.function !in setOf("calendar.read", "calendar.readOwned", "calendar.select", "memory.read", PHOTO_CAPTURE))
        Text(stringResource(R.string.agent_non_reversible), style = MaterialTheme.typography.bodySmall)
    val result = plan.results.getOrNull(index)
    when {
        result != null -> Text(result)
        plan.phase == AgentPhase.INTERRUPTED && plan.claimed == index -> Text(stringResource(R.string.agent_step_unknown))
        plan.phase in setOf(AgentPhase.FAILED, AgentPhase.STOPPED, AgentPhase.INTERRUPTED) -> Text(stringResource(R.string.agent_skipped))
    }
}
