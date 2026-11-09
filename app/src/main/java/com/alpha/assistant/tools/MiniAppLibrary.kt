package com.alpha.assistant.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.RussianHyphenation

@Composable internal fun MiniAppLibrary(
    controller: ToolController,
    archived: Boolean,
    hasArchive: Boolean,
    onArchiveChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    settings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
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
    var matches by remember(query, archived, controller.tools) { mutableStateOf<Result<List<ToolRecord>>?>(null) }
    LaunchedEffect(query, archived, controller.tools) {
        if (query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        matches = try {
            Result.success(controller.searchLibrary(query, archived))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val tools = if (query.isBlank()) controller.tools.filter { it.saved && it.archived == archived }
        else matches?.getOrNull().orEmpty()


    Surface(Modifier.fillMaxSize().testTag("mini-app-library").semantics { contentDescription = libraryLabel }) {
        Column(Modifier.fillMaxSize().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                val titleStyle = MaterialTheme.typography.titleLarge.let {
                    if (maxWidth < 360.dp) it.copy(fontSize = 20.sp) else it
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onMenu, Modifier.semantics { contentDescription = menuLabel }) { AlphaIcon(Glyph.Menu) }
                    Text(stringResource(R.string.mini_apps), Modifier.weight(1f).padding(horizontal = 4.dp)
                        .semantics { heading() }, style = titleStyle.merge(RussianHyphenation))
                    Box {
                        IconButton(onClick = { options = true }, Modifier.semantics { contentDescription = optionsLabel }) {
                            AlphaIcon(Glyph.MoreVertical)
                        }
                        DropdownMenu(options, { options = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.tool_import)) }, onClick = {
                                options = false
                                importer.launch(arrayOf("application/zip", "application/octet-stream"))
                            }, enabled = !controller.busy)
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            ) {
                item {
                    LibrarySearch(query, { query = it })
                    Spacer(Modifier.height(8.dp))
                }
                if (hasArchive) item {
                    val tab = if (archived) 1 else 0
                    PrimaryTabRow(
                        selectedTabIndex = tab,
                        indicator = {
                            TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tab, matchContentSize = false),
                                height = 2.dp, color = colors.primary)
                        },
                        divider = { HorizontalDivider(color = colors.outlineVariant) },
                    ) {
                        Tab(selected = !archived, onClick = {
                            focus.clearFocus()
                            onArchiveChange(false)
                        }, text = { Text(stringResource(R.string.tool_saved)) },
                            selectedContentColor = colors.primary, unselectedContentColor = colors.onSurfaceVariant)
                        Tab(selected = archived, onClick = {
                            focus.clearFocus()
                            onArchiveChange(true)
                        }, text = { Text(stringResource(R.string.tool_archive)) },
                            selectedContentColor = colors.primary, unselectedContentColor = colors.onSurfaceVariant)
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
                        Text(stringResource(R.string.tool_drafts), Modifier.padding(top = 20.dp, bottom = 6.dp)
                            .semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(controller.drafts, key = { "draft-" + it.id }) { draft ->
                        val iconId = remember(draft.candidate) { draft.candidate?.let { ToolSpec.availablePersisted(it)?.iconId } }
                        LibraryRow(draft.prompt.take(100), null, controller.selectedDraftId == draft.id, !controller.busy,
                            iconId = iconId, draft = true) {
                            focus.clearFocus()
                            onOpen()
                            controller.selectDraft(draft.id)
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                } else if (tools.isNotEmpty()) item { Spacer(Modifier.height(10.dp)) }
                if (tools.isEmpty()) item {
                    Text(stringResource(when {
                        query.isNotBlank() && matches == null -> R.string.semantic_searching
                        matches?.isFailure == true -> R.string.semantic_search_unavailable
                        query.isNotBlank() -> R.string.tool_no_matches
                        archived -> R.string.tool_archive_empty
                        else -> R.string.tool_catalog_empty
                    }), Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(tools, key = { "app-" + it.id }) { tool ->
                    val spec = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) }
                    val description = spec?.description ?: stringResource(R.string.tool_closed_format)
                    LibraryRow(ToolSpec.storedTitle(tool.spec), description,
                        controller.selectedDraftId == null && controller.selectedId == tool.id, enabled = true, iconId = spec?.iconId) {
                        if (controller.tools.any { it.id == tool.id && it.revision == tool.revision && it.saved && it.archived == archived }) {
                            focus.clearFocus()
                            onOpen()
                            controller.select(tool.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LibrarySearch(query: String, change: (String) -> Unit) {
    val label = stringResource(R.string.tool_search)
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val style = MaterialTheme.typography.bodyMedium
    BasicTextField(
        value = query, onValueChange = change, singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        textStyle = style.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), interactionSource = interaction,
        decorationBox = { input ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(colors.surfaceContainerLow, shape)
                    .border(1.dp, if (focused) colors.primary else colors.surfaceVariant, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                AlphaIcon(Glyph.Search, Modifier.size(20.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(label, style = style, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
            }
        },
    )
}

@Composable private fun LibraryRow(
    title: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    iconId: String? = null,
    draft: Boolean = false,
    open: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .selectable(selected, enabled = enabled, role = Role.Button, onClick = open)
                .heightIn(min = if (draft) 64.dp else 76.dp).padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniAppBadge(iconId, draft = draft)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (draft) AlphaIcon(Glyph.Compose, Modifier.size(18.dp))
            AlphaIcon(Glyph.Chevron)
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
}
