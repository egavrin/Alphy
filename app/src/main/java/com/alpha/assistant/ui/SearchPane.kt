package com.alpha.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import java.text.DateFormat
import java.util.Date

/** «Поиск». The top bar shows the title and «Новый чат», so the page has neither. */
@Composable internal fun SearchPane(conversations: List<Conversation>, search: String, edit: (String) -> Unit,
    select: (String) -> Unit, rename: (String, String) -> Unit, delete: (String) -> Unit, clear: () -> Unit) {
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // AlphyActivity keeps the default locale, so the Russian locale comes from the configuration.
    val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, LocalConfiguration.current.locales[0])
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val searchLabel = stringResource(R.string.search_field)
        OutlinedTextField(value = search, onValueChange = edit, placeholder = { Text(stringResource(R.string.search_placeholder)) }, leadingIcon = { AlphaIcon(Glyph.Search) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp).semantics { contentDescription = searchLabel }, singleLine = true, shape = RoundedCornerShape(18.dp))
        val results = conversations.filter { c -> search.isBlank() || c.title.contains(search, true) || c.messages.any { it.text.contains(search, true) } || c.draft.text.contains(search, true) }
        LazyColumn(Modifier.weight(1f)) {
            if (results.isEmpty()) item { Text(stringResource(if (search.isBlank()) R.string.search_empty else R.string.search_none), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { select(c.id) }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(c.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val draft = c.draft.text.isNotEmpty() || c.draft.attachments.isNotEmpty()
                        Text(stringResource(if (draft) R.string.search_row_draft else R.string.search_row_saved, dates.format(Date(c.updatedAt))),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        val actions = stringResource(R.string.conversation_actions, c.title)
                        IconButton(onClick = { menuId = c.id }, modifier = Modifier.size(48.dp).semantics { contentDescription = actions }) { AlphaIcon(Glyph.More) }
                        DropdownMenu(expanded = menuId == c.id, onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_rename)) }, onClick = { renameId = c.id; renameText = c.title; menuId = null })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_delete), color = MaterialTheme.colorScheme.error) }, onClick = { deleteId = c.id; menuId = null })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        TextButton(onClick = { confirmClear = true }, enabled = conversations.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.clear_history), color = if (conversations.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text(stringResource(R.string.rename_title)) }, text = {
        val renameLabel = stringResource(R.string.rename_label)
        OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text(renameLabel) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = renameLabel }, isError = renameText.isBlank(),
            supportingText = { if (renameText.isBlank()) Text(stringResource(R.string.rename_placeholder)) })
    }, confirmButton = { TextButton(onClick = { renameId?.let { rename(it, renameText) }; renameId = null }, enabled = renameText.isNotBlank()) { Text(stringResource(R.string.rename_save)) } },
        dismissButton = { TextButton(onClick = { renameId = null }) { Text(stringResource(R.string.cancel)) } })
    val deleting = conversations.find { it.id == deleteId }
    if (deleting != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.conversation_delete_title)) }, text = {
        Text(stringResource(R.string.conversation_delete_text, deleting.title))
    }, confirmButton = { TextButton(onClick = { delete(deleting.id); deleteId = null }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.clear_history_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.clear_history_text))
            Text(stringResource(R.string.clear_history_kept))
        }
    }, confirmButton = { TextButton(onClick = { clear(); confirmClear = false }) { Text(stringResource(R.string.clear_history_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
}
