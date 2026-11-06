package com.alpha.assistant.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.MiniAppIcon

@Composable internal fun MiniAppLibrary(
    controller: ToolController,
    archived: Boolean,
    onArchiveChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    settings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val libraryLabel = stringResource(R.string.tool_library_content)
    val optionsLabel = stringResource(R.string.tool_catalog_options)
    val menuLabel = stringResource(R.string.menu)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onOpen()
            controller.importPortable(uri)
        }
    }
    val hasDetail = controller.selectedId != null || controller.selectedDraftId != null
    val tools = controller.tools.filter {
        it.saved && it.archived == archived && ToolSpec.storedTitle(it.spec).contains(query.trim(), ignoreCase = true)
    }

    Surface(Modifier.fillMaxSize().testTag("mini-app-library").semantics { contentDescription = libraryLabel }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onMenu, Modifier.semantics { contentDescription = menuLabel }) { AlphaIcon(Glyph.Menu) }
                Text(stringResource(R.string.mini_apps), Modifier.weight(1f).padding(horizontal = 4.dp)
                    .semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Box {
                    IconButton(onClick = { options = true }, Modifier.semantics { contentDescription = optionsLabel }) {
                        AlphaIcon(Glyph.More)
                    }
                    DropdownMenu(options, { options = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.tool_import)) }, onClick = {
                            options = false
                            importer.launch(arrayOf("application/zip", "application/octet-stream"))
                        }, enabled = !controller.busy)
                    }
                }
            }
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            ) {
                item {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        label = { Text(stringResource(R.string.tool_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { AlphaIcon(Glyph.Search) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                    PrimaryTabRow(selectedTabIndex = if (archived) 1 else 0) {
                        Tab(selected = !archived, onClick = {
                            focus.clearFocus()
                            onArchiveChange(false)
                        }, text = { Text(stringResource(R.string.tool_saved)) })
                        Tab(selected = archived, onClick = {
                            focus.clearFocus()
                            onArchiveChange(true)
                        }, text = { Text(stringResource(R.string.tool_archive)) })
                    }
                }
                if (!hasDetail && controller.busy) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = controller::cancel) { Text(stringResource(R.string.cancel)) }
                }
                controller.error?.takeUnless { hasDetail }?.let { message -> item {
                    Text(toolErrorMessage(controller.errorCode, message), color = MaterialTheme.colorScheme.error)
                    if (controller.errorCode in KEY_ERRORS) TextButton(onClick = settings) { Text(stringResource(R.string.open_settings)) }
                    TextButton(onClick = controller::clearError) { Text(stringResource(R.string.tool_dismiss)) }
                } }
                if (!archived && controller.drafts.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.tool_drafts), Modifier.padding(top = 14.dp, bottom = 4.dp)
                            .semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(controller.drafts, key = { "draft-" + it.id }) { draft ->
                        LibraryRow(draft.prompt.take(100), null, controller.selectedDraftId == draft.id, !controller.busy, Glyph.Compose) {
                            focus.clearFocus()
                            onOpen()
                            controller.selectDraft(draft.id)
                        }
                    }
                }
                if (tools.isEmpty()) item {
                    Text(stringResource(when {
                        query.isNotBlank() -> R.string.tool_no_matches
                        archived -> R.string.tool_archive_empty
                        else -> R.string.tool_catalog_empty
                    }), Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(tools, key = { "app-" + it.id }) { tool ->
                    val spec = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) }
                    val description = spec?.description ?: stringResource(R.string.tool_closed_format)
                    LibraryRow(ToolSpec.storedTitle(tool.spec), description,
                        controller.selectedDraftId == null && controller.selectedId == tool.id, enabled = true) {
                        focus.clearFocus()
                        onOpen()
                        controller.select(tool.id)
                    }
                }
            }
        }
    }
}

@Composable private fun LibraryRow(
    title: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    icon: Glyph? = null,
    open: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .selectable(selected, enabled = enabled, role = Role.Button, onClick = open)
                .heightIn(min = 68.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon == null) MiniAppIcon(title)
            else Surface(Modifier.size(44.dp), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer) {
                Box(contentAlignment = Alignment.Center) { AlphaIcon(icon, color = MaterialTheme.colorScheme.onTertiaryContainer) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            AlphaIcon(Glyph.Chevron)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}
