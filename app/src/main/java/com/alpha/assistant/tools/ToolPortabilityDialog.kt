package com.alpha.assistant.tools

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ToolPortabilityDialog(controller: ToolController, tool: ToolRecord, export: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failure = stringResource(if (export) R.string.tool_export_failed else R.string.tool_manual_edit_failed)
    val title = stringResource(if (export) R.string.tool_export else R.string.tool_manual_edit)
    val expected = remember { tool }
    val stale = if (export) tool.revision != expected.revision || tool.createdAt != expected.createdAt else tool != expected
    val hasCollections = remember(expected.spec) { ToolSpec.availablePersisted(expected.spec)?.collections?.length() != 0 }
    var includeState by remember { mutableStateOf(false) }
    var definition by remember { mutableStateOf(expected.spec) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (export) {
                    Text(stringResource(R.string.tool_export_contents))
                    Row(Modifier.heightIn(min = 48.dp).toggleable(includeState, enabled = !busy && !hasCollections,
                        role = Role.Checkbox, onValueChange = { includeState = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeState, onCheckedChange = null, enabled = !busy && !hasCollections)
                        Text(stringResource(R.string.tool_export_state))
                    }
                    if (hasCollections) Text(stringResource(R.string.tool_export_collections))
                } else {
                    Text(stringResource(R.string.tool_manual_edit_notice))
                    OutlinedTextField(definition, { definition = it.take(65536) },
                        label = { Text(stringResource(R.string.tool_manual_edit_definition)) },
                        maxLines = 12, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (stale) Text(stringResource(R.string.tool_refinement_current_version), color = MaterialTheme.colorScheme.error)
                else if (failed) Text(failure, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !stale && (export || definition != expected.spec), onClick = {
                busy = true
                failed = false
                scope.launch {
                    try {
                        if (export) {
                            val uri = controller.exportPortable(expected, includeState)
                            val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            intent.clipData = ClipData.newUri(context.contentResolver, title, uri)
                            context.startActivity(Intent.createChooser(intent, title))
                        } else {
                            controller.saveManualDefinition(expected, definition)
                        }
                        dismiss()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }) {
                Text(stringResource(if (export) R.string.tool_export_share else R.string.tool_manual_edit_save))
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
