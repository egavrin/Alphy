package com.alpha.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import java.text.DateFormat
import java.util.Date

@Composable internal fun SearchPane(
    conversations: List<Conversation>,
    search: String,
    edit: (String) -> Unit,
    select: (HistoryResult) -> Unit,
    retrieve: suspend (String) -> List<HistoryResult>,
    rename: (String, String) -> Unit,
    delete: (String) -> Unit,
    clear: () -> Unit,
    back: () -> Unit,
    focusReady: Boolean,
) {
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyMenu by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusReady) {
        if (focusReady && renameId == null && deleteId == null && !confirmClear) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    // AlphyActivity keeps the default locale, so the Russian locale comes from the configuration.
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { DateFormat.getDateInstance(DateFormat.SHORT, locale) }
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val hasQuery = search.isNotBlank()
    var hits by remember(conversations, search) { mutableStateOf<Result<List<HistoryResult>>?>(null) }
    LaunchedEffect(conversations, search) {
        if (!hasQuery) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        hits = try {
            Result.success(retrieve(search))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val results = if (hasQuery) hits?.getOrNull().orEmpty().mapNotNull { hit ->
        conversations.firstOrNull { it.id == hit.conversationId }?.let { it to hit }
    } else conversations.map { it to HistoryResult(it.id) }
    // The shell consumes the IME inset except in tabletop mode.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.Back, stringResource(R.string.search_back), back)
            Text(stringResource(R.string.search), Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                style = type.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                GlyphButton(Glyph.MoreVertical, stringResource(R.string.search_history_actions)) { historyMenu = true }
                DropdownMenu(expanded = historyMenu, onDismissRequest = { historyMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_history)) },
                        enabled = conversations.isNotEmpty(),
                        onClick = { historyMenu = false; confirmClear = true },
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            item {
                Text(stringResource(if (hasQuery) R.string.search_results else R.string.search_recent),
                    Modifier.padding(top = 4.dp, bottom = 8.dp).semantics { heading() },
                    style = type.titleSmall, color = colors.onSurfaceVariant)
            }
            // A query can stay after «Очистить всё», so an empty history shows search_empty for each query.
            if (results.isEmpty()) item {
                Text(stringResource(when {
                    conversations.isEmpty() -> R.string.search_empty
                    hasQuery && hits == null -> R.string.semantic_searching
                    hits?.isFailure == true -> R.string.semantic_search_unavailable
                    else -> R.string.search_none
                }),
                    Modifier.padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant)
            }
            items(results, key = { (chat, hit) -> "${chat.id}:${hit.messageId ?: hit.draft}" }) { (c, hit) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { select(hit) }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(40.dp).background(colors.surfaceContainerHigh, MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center) {
                            AlphaIcon(Glyph.Chat, accent = true)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(c.title,
                                style = type.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (hit.preview != null) Text(hit.preview, style = type.bodyMedium, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val date = dates.format(Date(c.updatedAt))
                            val draft = c.draft.text.isNotEmpty() || c.draft.attachments.isNotEmpty()
                            Text(if (draft) stringResource(R.string.search_row_draft, date) else date,
                                style = type.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    Box {
                        val actions = stringResource(R.string.conversation_actions, c.title)
                        GlyphButton(Glyph.MoreVertical, actions) { menuId = c.id }
                        DropdownMenu(expanded = menuId == c.id, onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_rename)) }, onClick = { renameId = c.id; renameText = c.title; menuId = null })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_delete), color = MaterialTheme.colorScheme.error) }, onClick = { deleteId = c.id; menuId = null })
                        }
                    }
                }
            }
        }
        val searchLabel = stringResource(R.string.search_field)
        TextField(
            value = search,
            onValueChange = edit,
            placeholder = { Text(searchLabel) },
            leadingIcon = { AlphaIcon(Glyph.Search) },
            trailingIcon = if (search.isEmpty()) null else {
                {
                    val label = stringResource(R.string.search_clear_query)
                    IconButton(onClick = { edit(""); focusRequester.requestFocus(); keyboard?.show() },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
                        Box(Modifier.size(20.dp).background(colors.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                            AlphaIcon(Glyph.Close, Modifier.size(13.dp), color = colors.surfaceContainerHigh)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                .focusRequester(focusRequester).semantics { contentDescription = searchLabel },
            shape = CircleShape,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainerHigh,
                unfocusedContainerColor = colors.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
    val dismissRename = { renameId = null }
    if (renameId != null) AlertDialog(onDismissRequest = dismissRename, title = { Text(stringResource(R.string.rename_title)) }, text = {
        val renameLabel = stringResource(R.string.rename_label)
        OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text(renameLabel) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = renameLabel }, isError = renameText.isBlank(),
            supportingText = { if (renameText.isBlank()) Text(stringResource(R.string.rename_placeholder)) })
    }, confirmButton = {
        TextButton(onClick = {
            renameId?.let { rename(it, renameText) }
            dismissRename()
        }, enabled = renameText.isNotBlank()) { Text(stringResource(R.string.rename_save)) }
    },
        dismissButton = { TextButton(onClick = dismissRename) { Text(stringResource(R.string.cancel)) } })
    val deleting = conversations.find { it.id == deleteId }
    val dismissDelete = { deleteId = null }
    if (deleting != null) AlertDialog(onDismissRequest = dismissDelete, title = { Text(stringResource(R.string.conversation_delete_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.conversation_delete_text, deleting.title)) }
    }, confirmButton = {
        TextButton(onClick = {
            delete(deleting.id)
            dismissDelete()
        }) { Text(stringResource(R.string.delete)) }
    },
        dismissButton = { TextButton(onClick = dismissDelete) { Text(stringResource(R.string.cancel)) } })
    val dismissClear = { confirmClear = false }
    if (confirmClear) AlertDialog(onDismissRequest = dismissClear, title = { Text(stringResource(R.string.clear_history_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.clear_history_text))
            Text(stringResource(R.string.clear_history_kept))
        }
    }, confirmButton = {
        TextButton(onClick = {
            clear()
            dismissClear()
        }) { Text(stringResource(R.string.clear_history_confirm)) }
    },
        dismissButton = { TextButton(onClick = dismissClear) { Text(stringResource(R.string.cancel)) } })
}
