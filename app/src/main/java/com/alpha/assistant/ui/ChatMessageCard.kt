package com.alpha.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.cloud.validSourceUrl
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.Source

@Composable internal fun ChatMessageCard(
    message: Message,
    fragment: String?,
    weatherLocationBusy: Boolean,
    retry: (() -> Unit)?,
    retryEnabled: Boolean,
    cancelQueued: (() -> Unit)?,
    chooseRefinement: (String) -> Unit,
    share: () -> Unit,
    settings: () -> Unit,
    linkError: () -> Unit,
) {
    val user = message.role == "user"
    val body = fragment ?: message.text
    val progress = when {
        user -> null
        message.status == "generating" -> null
        message.status !in listOf("pending", "streaming") -> null
        weatherLocationBusy -> R.string.chat_request_location
        message.searchStatus == "searching" -> R.string.chat_search_running
        body.isBlank() -> R.string.chat_request_responding
        else -> null
    }
    if (progress != null && body.isBlank() && message.sources.isEmpty() && message.error.isBlank()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 5.dp).then(PoliteLiveRegion),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(stringResource(progress), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val accepted = user || message.status == "complete"
    val hasTextActions = accepted && message.text.isNotBlank()
    val sourceProblem = message.errorCode in setOf("source_support", "source_attribution")
    val emptyReply = message.errorCode == "empty_response"
    val hasMenu = hasTextActions || (retry != null && !sourceProblem && !emptyReply)
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val actionLabel = stringResource(R.string.chat_message_actions)
    val actions = if (hasMenu) Modifier
        .pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) }
        .semantics { onLongClick(actionLabel) { menuOpen = true; true } }
    else Modifier
    Box {
        Surface(
            modifier = Modifier.fillMaxWidth().then(actions),
            shape = RoundedCornerShape(23.dp),
            color = if (user) colors.secondaryContainer else colors.surfaceContainerLow,
            contentColor = if (user) colors.onSecondaryContainer else colors.onSurface,
            border = if (user) null else BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (fragment != null || (!accepted && message.text.isNotBlank() && message.status in listOf("cancelled", "interrupted"))) {
                    Text(stringResource(R.string.chat_answer_incomplete), style = MaterialTheme.typography.labelSmall)
                }
                if (message.status == "generating") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (body.isNotEmpty()) {
                    val semantics = if (fragment != null) Modifier.semantics { hideFromAccessibility() } else Modifier
                    if (accepted) {
                        MarkdownText(body, semantics, announce = !user && fragment == null,
                            sources = if (user) emptyList() else message.sources.distinctBy { it.url }, linkError = linkError)
                    } else {
                        Text(body, semantics, fontSize = 15.sp, lineHeight = 23.sp)
                    }
                }
                message.attachments.forEach {
                    Text(stringResource(R.string.chat_attachment_history, it.name), style = MaterialTheme.typography.bodySmall)
                }
                progress?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall) }
                if (message.sources.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        message.sources.distinctBy { it.url }.forEachIndexed { index, source -> SourceChip(source, index + 1, linkError) }
                    }
                }
                if (message.error.isNotBlank()) {
                    val canRecover = sourceProblem || emptyReply
                    Surface(shape = RoundedCornerShape(12.dp), color = if (canRecover) colors.secondaryContainer else colors.errorContainer,
                        contentColor = if (canRecover) colors.onSecondaryContainer else colors.onErrorContainer) {
                        val error = when {
                            sourceProblem -> stringResource(R.string.chat_source_support)
                            emptyReply -> stringResource(R.string.chat_empty_answer)
                            else -> message.error
                        }
                        Text(error,
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (canRecover && retry != null) {
                        TextButton(onClick = retry, enabled = retryEnabled) { Text(stringResource(R.string.chat_message_retry)) }
                    }
                }
                if (!user && message.errorCode in KEY_ERRORS) {
                    TextButton(onClick = settings) { Text(stringResource(R.string.open_settings)) }
                }
                if (message.status == "queued") Text(stringResource(R.string.chat_request_queued), style = MaterialTheme.typography.labelSmall)
                if (cancelQueued != null) TextButton(onClick = cancelQueued, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.chat_cancel_queued))
                }
                message.refinementChoices.forEach { choice ->
                    TextButton(onClick = { chooseRefinement(choice.toolId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(choice.title)
                    }
                }
            }
        }
        if (hasMenu) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (hasTextActions) {
                    val appName = stringResource(R.string.app_name)
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_message_copy)) }, onClick = {
                        menuOpen = false
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText(appName, message.text))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_message_note)) }, onClick = { menuOpen = false; share() })
                }
                if (retry != null && !sourceProblem && !emptyReply) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_message_retry)) },
                        onClick = { menuOpen = false; retry() }, enabled = retryEnabled)
                }
            }
        }
    }
}

@Composable private fun SourceChip(source: Source, number: Int, linkError: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val validUrl = remember(source.url) { validSourceUrl(source.url) }
    val site = source.siteName?.takeIf { it.isNotBlank() } ?: if (validUrl) Uri.parse(source.url).host.orEmpty() else ""
    val title = source.title.ifBlank { site.ifBlank { source.url } }
    val label = listOf(site, title).filter { it.isNotBlank() }.distinct().joinToString(". ")
    val description = stringResource(R.string.chat_source_description, number, label)
    val icon = rememberSourceIcon(source.siteIcon)
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = {
            if (validUrl) runCatching { uriHandler.openUri(source.url) }.onFailure { linkError() }
            else linkError()
        },
        modifier = Modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(16.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon == null) AlphaIcon(Glyph.Link, Modifier.size(16.dp).testTag("source-fallback-$number"), color = colors.onSecondaryContainer)
            else Image(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.chat_source_number, number), Modifier.semantics { hideFromAccessibility() },
                style = MaterialTheme.typography.labelMedium)
            Text(site.ifBlank { title }, Modifier.weight(1f, fill = false).semantics { hideFromAccessibility() },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }
}
