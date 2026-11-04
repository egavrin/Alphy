package com.alpha.assistant.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph

@Composable fun ToolCard(tool: ToolRecord, open: () -> Unit) {
    ArtifactCard(tool,open = open)
}

/** Chat cards contain metadata only. Native controls and resource loaders exist only in the focused pane. */
@Composable fun ArtifactCard(tool: ToolRecord, revision: Int = tool.revision, historical: Boolean = false,
    open: () -> Unit, refine: (() -> Unit)? = null) {
    val spec=remember(tool.spec) {ToolSpec.available(tool.spec)}
    Card(onClick=open,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(spec?.title ?: ToolSpec.storedTitle(tool.spec),style=MaterialTheme.typography.titleMedium)
            Text(spec?.description ?: "Unavailable artifact protocol · saved data is preserved",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                historical -> "Historical result · revision $revision · opens the current mini-app"
                tool.archived -> "Archived · data preserved"
                tool.saved -> "Saved on this device"
                else -> "Available on this device"
            },style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=open,modifier=Modifier.heightIn(min=48.dp)) {Text("Open")}
                if(refine != null && spec!=null && !historical && !tool.archived) TextButton(onClick=refine,modifier=Modifier.heightIn(min=48.dp)) {Text("Refine")}
            }
        }
    }
}

@Composable fun ToolsPane(controller: ToolController, conversationId: String?, chatText: String,
    images: List<Attachment>, immersive: Boolean=false, exitImmersive: () -> Unit = {}, settings: () -> Unit, close: () -> Unit) {
    val draft=controller.drafts.find {it.id == controller.selectedDraftId}
    val tool=controller.tools.find {it.id == (controller.selectedId ?: draft?.toolId)}
    var requests by rememberSaveable(controller.requestPrompt) {mutableStateOf(mapOf("new" to controller.requestPrompt))}
    val contextId=tool?.id ?: draft?.toolId ?: "new"
    val prompt=requests[contextId] ?: draft?.prompt.orEmpty()
    val edit: (String) -> Unit = {requests=requests + (contextId to it)}
    LaunchedEffect(contextId,draft?.id) {
        if(draft != null && requests[contextId].isNullOrEmpty()) requests=requests + (contextId to draft.prompt)
    }
    var sendImages by remember {mutableStateOf(false)}
    var query by rememberSaveable {mutableStateOf("")}
    var showArchive by rememberSaveable {mutableStateOf(false)}
    var archiveConfirm by remember(controller.selectedId) {mutableStateOf(false)}
    val focus=LocalFocusManager.current
    val submit={
        focus.clearFocus()
        controller.generate(prompt,tool?.id,conversationId,if(tool == null && sendImages) images else emptyList())
        sendImages=false
    }
    if(tool != null || draft != null) {
        FocusedToolPane(controller,tool,draft,prompt,edit,submit,{archiveConfirm=true},immersive,exitImmersive,settings,close)
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).semantics {contentDescription="Mini-app library content"}.padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Create a mini-app",style=MaterialTheme.typography.titleLarge)
        Text("Describe what you need. Preview the result before you apply it.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(prompt,edit,label={Text("Request for a mini-app")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
        if(chatText.isNotBlank()) TextButton(onClick={edit(chatText)}) {Text("Use chat draft")}
        if(images.isNotEmpty()) Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(sendImages,{sendImages=it}); Text("Include selected images: ${images.size}",Modifier.weight(1f))
        }
        Text("DeepSeek receives the request and selected images. Device actions need separate access and confirmation.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick=submit,enabled=prompt.isNotBlank() && prompt.length <= 16000 && !controller.busy,modifier=Modifier.heightIn(min=48.dp)) {Text("Create preview")}
        if(controller.busy) {LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick=controller::cancel) {Text("Cancel")}}
        controller.error?.let {
            Text(it,color=MaterialTheme.colorScheme.error)
            if(controller.errorCode in KEY_ERRORS) TextButton(onClick=settings) {Text(stringResource(R.string.open_settings))}
            TextButton(onClick=controller::clearError) {Text("Dismiss")}
        }
        if(controller.drafts.isNotEmpty()) Text("Drafts",style=MaterialTheme.typography.titleMedium)
        controller.drafts.forEach {pending -> TextButton(onClick={controller.selectDraft(pending.id)},enabled=!controller.busy) {Text(pending.prompt.take(100))}}
        Text("Library",style=MaterialTheme.typography.titleLarge)
        OutlinedTextField(query,{query=it},label={Text("Search mini-apps")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!showArchive,{showArchive=false},{Text("Saved")})
            FilterChip(showArchive,{showArchive=true},{Text("Archive")})
        }
        val visible=controller.tools.filter {it.saved && it.archived == showArchive && ToolSpec.storedTitle(it.spec).contains(query.trim(),ignoreCase=true)}
        if(visible.isEmpty()) Text(if(query.isNotBlank()) "No matches" else if(showArchive) "No archived mini-apps" else "Apply a preview to save your first mini-app.")
        visible.forEach {item -> ToolCard(item) {controller.select(item.id)}}
    }
    if(archiveConfirm && tool != null && !tool.archived) AlertDialog(onDismissRequest = { archiveConfirm = false },
        title = { Text("Архивировать «${ToolSpec.storedTitle(tool.spec)}»?") },text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Инструмент исчезнет из активной библиотеки. Данные, версии и черновики сохранятся. Его можно восстановить из архива.")
                Text("Все зависимые сценарии будут остановлены. Сохранение фоновой работы в архиве пока не поддерживается. После восстановления включи сценарии вручную.")
                if(controller.dependentRules.isEmpty()) Text("Зависимых сценариев нет.")
                else { Text("Зависимые сценарии:"); controller.dependentRules.forEach { Text(it) } }
                Text("Ожидающие системные действия отменятся. Уже начатое действие может завершиться до архивации. Архивация не отменяет ранее выполненные действия Android.")
                Text("Привязки виджетов сохранятся, но управление отключится. Ярлыки будут недоступны. Alpha не удаляет элементы с рабочего стола автоматически.")
            }
        },confirmButton = { TextButton(onClick = { archiveConfirm = false; controller.archive(tool) },enabled = !controller.busy) { Text("Архивировать") } },
        dismissButton = { TextButton(onClick = { archiveConfirm = false }) { Text("Отмена") } })

}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun FocusedToolPane(controller: ToolController, tool: ToolRecord?, draft: ToolDraft?,
    prompt: String, edit: (String) -> Unit, submit: () -> Unit, archive: () -> Unit, immersive: Boolean, exitImmersive: () -> Unit, settings: () -> Unit, close: () -> Unit) {
    val navigationContext=androidx.compose.ui.platform.LocalContext.current
    val navigationLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val navigationForeground = { navigationLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !com.alpha.assistant.surfaces.ToolWidgets.isLocked(navigationContext) }
    val spec=remember(tool?.spec) {tool?.let {ToolSpec.available(it.spec)}}
    var artifactSnapshot by remember(tool?.id,tool?.revision) {mutableStateOf<ArtifactSnapshot?>(null)}
    val displayed=artifactSnapshot?.tool?.takeIf {it.id == tool?.id && it.revision == tool.revision && it.stateVersion >= tool.stateVersion} ?: tool
    val candidate=remember(draft?.candidate) {draft?.candidate?.let(ToolSpec::available)}
    val preview=candidate != null && draft?.previewUi != null && draft.previewState != null
    var menu by remember {mutableStateOf(false)}
    var details by rememberSaveable(tool?.id) {mutableStateOf<String?>(null)}
    var appControls by remember(tool?.id,tool?.revision) {mutableStateOf(false)}
    var controlsReady by remember(tool?.id,tool?.revision) {mutableStateOf(false)}
    if(immersive && tool!=null && spec!=null && !preview) {
        BackHandler {if(appControls)appControls=false else exitImmersive()}
        NativeToolSurface(tool,spec,!tool.archived,0.dp,controller.artifactHost(tool),immersive=true,covered=appControls,onCoverReady={controlsReady=it},topControls={
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=exitImmersive,modifier=Modifier.heightIn(min=48.dp)) {Text("Exit full screen")}
                TextButton(onClick={controlsReady=false;appControls=true},modifier=Modifier.heightIn(min=48.dp)) {Text("App controls")}
            }
        }) {artifactSnapshot=it}
        if(appControls) ModalBottomSheet(onDismissRequest={appControls=false}) {
            Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                MiniAppContent(controller,tool,displayed,spec.title,navigationContext,navigationForeground,enabled=controlsReady && !controller.busy)
            }
        }
        return
    }
    val scroll=rememberScrollState()
    LaunchedEffect(details,preview) {scroll.scrollTo(0)}
    BackHandler {if(details != null) details=null else controller.select(null)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val refinementHeight=minOf(240.dp,maxHeight*0.45f)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={controller.select(null)},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics {contentDescription="Mini-app library"}) {AlphaIcon(Glyph.Back)}
            Text("Alphy · Mini-app",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick={menu=true},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics {contentDescription="Mini-app options"}) {Text("⋯",style=MaterialTheme.typography.headlineMedium)}
                DropdownMenu(menu,{menu=false}) {
                    if(tool != null) {
                        DropdownMenuItem(text={Text("Device access and history")},onClick={details="access";menu=false})
                        DropdownMenuItem(text={Text("Versions")},onClick={details="versions";menu=false})
                        DropdownMenuItem(text={Text("Details and home screen")},onClick={details="details";menu=false})
                        if(tool.saved && !tool.archived) DropdownMenuItem(text={Text("Archive")},onClick={archive();menu=false},enabled=!controller.busy && draft == null)
                    }
                    DropdownMenuItem(text={Text("Chat")},onClick={menu=false;close()})
                }
            }
        }
        if(controller.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(draft != null && !preview) "Creating preview… Your saved mini-app stays available." else "Working…",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=controller::cancel) {Text("Cancel")}
            }
        }
        controller.error?.let {message ->
            Surface(color=MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick=controller::clearError) {Text("Dismiss")}
                    }
                    // A row with both buttons leaves too little width for the message on a phone.
                    if(controller.errorCode in KEY_ERRORS) TextButton(onClick=settings) {Text(stringResource(R.string.open_settings))}
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        // Reserve the metadata, status, two native control rows, saved summary, and pane spacing.
        val canvasHeight=(maxHeight-330.dp).coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).semantics {contentDescription="Mini-app content"}.padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(preview) Text("Preview · controls are disabled",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            Text((if(preview) candidate.title else spec?.title) ?: tool?.let {ToolSpec.storedTitle(it.spec)} ?: "New mini-app",Modifier.semantics {heading()},style=MaterialTheme.typography.headlineLarge)
            ((if(preview) candidate.description else spec?.description))?.let {Text(it,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(preview && spec != null) Text("Changes to ${spec.title} · saved version stays unchanged until Apply",style=MaterialTheme.typography.bodySmall)
            if(details != null) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(when(details) {"access" -> "Device access and history"; "versions" -> "Versions"; else -> "Mini-app details"},Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={details=null}) {Text("Close details")}
                }
                if(tool != null && details == "versions") controller.revisions.forEach {revision ->
                    TextButton(onClick={controller.rollback(tool,revision.revision)},enabled=!controller.busy && draft == null && !tool.archived && revision.revision != tool.revision) {Text("Restore revision ${revision.revision} · keep current data")}
                }
                if(tool != null && details == "details") {
                    Text("Revision ${tool.revision} · data version ${tool.stateVersion}",style=MaterialTheme.typography.bodySmall)
                    Text("Changes send this mini-app’s code and current data to DeepSeek. Previews perform no external actions.",style=MaterialTheme.typography.bodySmall)
                    if(tool.saved && !tool.archived && spec!=null) com.alpha.assistant.surfaces.PinToolButton(tool)
                    if(!tool.saved && !tool.archived) Button(onClick={controller.save(tool.id)},enabled=!controller.busy) {Text("Save to library")}
                }
            }
            if(tool != null && spec != null) {
                controller.effects.lastOrNull()?.let {effect ->
                    Text(effect.result.message,style=MaterialTheme.typography.bodySmall,color=if(effect.result.status in setOf("failed","denied","revoked","unavailable")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                com.alpha.assistant.capabilities.ToolCapabilityPanel(controller,tool,showDetails=details == "access")
            }
            if(preview) ToolContent(checkNotNull(draft.previewUi),checkNotNull(draft.previewState),false,heading=candidate.title) {_,_ ->}
            else if(tool != null) {
                if(tool.archived) {
                    Text("Archived · data and versions are preserved. Restore this mini-app to use its controls.")
                    Button(onClick={controller.recover(tool)},enabled=!controller.busy) {Text("Restore from archive")}
                }
                if(spec != null) NativeToolSurface(tool,spec,draft == null && !tool.archived && details == null,canvasHeight,controller.artifactHost(tool)) {artifactSnapshot=it}
                if(spec == null) Text("This artifact uses an unavailable protocol. Its saved data, versions, records, and access grants remain intact.")
                if(spec != null) MiniAppContent(controller,tool,displayed,spec.title,navigationContext,navigationForeground,enabled=!controller.busy && draft == null && !tool.archived)
            } else Text(draft?.prompt.orEmpty(),style=MaterialTheme.typography.bodyLarge)
        }
        }
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp,shadowElevation=4.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max=refinementHeight).verticalScroll(rememberScrollState())
                .semantics {contentDescription="Mini-app refinement"}.padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(preview) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={controller.reject(draft.id)},enabled=!controller.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text("Discard preview")}
                        Button(onClick={controller.accept(draft.id)},enabled=!controller.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text("Apply changes")}
                    }
                } else if(tool?.archived != true && (tool == null || spec != null)) {
                    OutlinedTextField(prompt,edit,enabled=!controller.busy,label={Text(if(tool == null) "Request for a mini-app" else "Refine this mini-app")},
                        modifier=Modifier.fillMaxWidth().heightIn(max=140.dp),maxLines=4,shape=RoundedCornerShape(20.dp))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Changes send app code and data to DeepSeek.",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(draft != null && !controller.busy) TextButton(onClick={controller.reject(draft.id)}) {Text("Discard draft")}
                        val retry=draft != null && prompt == draft.prompt
                        Button(onClick=if(retry) {{controller.retry(checkNotNull(draft).id)}} else submit,
                            enabled=!controller.busy && prompt.isNotBlank() && prompt.length <= 16000,modifier=Modifier.heightIn(min=48.dp)) {Text(if(retry) "Retry preview" else "Preview changes")}
                    }
                }
            }
        }
    }
    }
}

/** The immersive sheet and the inline pane share this wiring and differ only in `enabled`. ToolContent has default parameters, so a missed hook still compiles. */
@Composable private fun MiniAppContent(controller: ToolController, tool: ToolRecord, displayed: ToolRecord?, heading: String,
    navigationContext: android.content.Context, navigationForeground: () -> Boolean, enabled: Boolean) {
    val current=checkNotNull(displayed)
    val calendarHost=remember(current.id,current.revision,current.stateVersion,controller.grants,controller.effects) {
        controller.calendarUiHost(current,navigationForeground) {intent ->check(navigationForeground());navigationContext.startActivity(intent)}
    }
    ToolContent(current.ui,current.state,enabled,heading=heading,calendarHost=calendarHost,
        imageVersion=(tool.id to controller.grants),imageLoader={controller.nativeImage(tool.id,it)},imageAvailable={controller.nativeImageAvailable(tool.id,it)}) {id,value ->
        controller.interact(current,id,value,navigationForeground) {intent ->check(navigationForeground());navigationContext.startActivity(intent)}
    }
}

@Composable internal fun ImmersiveSystemBars(enabled: Boolean) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val view=androidx.compose.ui.platform.LocalView.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val activity=generateSequence(context) {(it as? android.content.ContextWrapper)?.baseContext}.filterIsInstance<android.app.Activity>().firstOrNull()
    DisposableEffect(activity,view,lifecycle,enabled) {
        val window=activity?.window
        val controller=window?.let {androidx.core.view.WindowCompat.getInsetsController(it,view)}
        val insets=androidx.core.view.ViewCompat.getRootWindowInsets(view)
        val bars=androidx.core.view.WindowInsetsCompat.Type.systemBars()
        val status=androidx.core.view.WindowInsetsCompat.Type.statusBars()
        val navigation=androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        val behavior=controller?.systemBarsBehavior
        fun restore() {
            if(enabled && controller!=null) {
                controller.systemBarsBehavior=checkNotNull(behavior)
                for(type in listOf(status,navigation)) {
                    if(insets?.isVisible(type)!=false)controller.show(type) else controller.hide(type)
                }
            }
        }
        fun hide() {
            if(enabled && controller!=null) {
                controller.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(bars)
            }
        }
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)hide()
            if(event==androidx.lifecycle.Lifecycle.Event.ON_PAUSE)restore()
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))hide()
        onDispose {lifecycle.removeObserver(observer);restore()}
    }
}
