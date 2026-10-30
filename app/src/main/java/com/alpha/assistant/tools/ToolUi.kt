package com.alpha.assistant.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alpha.assistant.data.Attachment
import org.json.JSONObject

@Composable fun ToolCard(tool: ToolRecord, open: () -> Unit) {
    val spec = remember(tool.spec) { ToolSpec.parse(tool.spec) }
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(spec.title,style = MaterialTheme.typography.titleMedium)
            Text(spec.description,style = MaterialTheme.typography.bodySmall)
            Text("Версия ${tool.revision} · действия на устройстве",style = MaterialTheme.typography.labelSmall)
            Text("Открыть инструмент",color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable fun ToolsPane(controller: ToolController, conversationId: String?, chatText: String,
    images: List<Attachment>, close: () -> Unit) {
    var prompt by rememberSaveable(controller.requestPrompt) { mutableStateOf(controller.requestPrompt) }
    var confirm by remember { mutableStateOf(false) }
    var sendImages by remember { mutableStateOf(false) }
    var refine by remember { mutableStateOf(false) }
    val tool = controller.tools.find { it.id == controller.selectedId }
    val draft = controller.drafts.find { it.id == controller.selectedDraftId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Инструменты",Modifier.weight(1f),style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { controller.select(null); close() }) { Text("Чат") }
        }
        if(controller.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = controller::cancel) { Text("Отмена") } }
        controller.error?.let { Text(it,color = MaterialTheme.colorScheme.error); TextButton(onClick = controller::clearError) { Text("Закрыть") } }
        when {
            draft != null -> {
                Text("Отдельный черновик",style = MaterialTheme.typography.titleLarge)
                Text(draft.prompt)
                if(draft.candidate != null && draft.previewState != null && draft.previewUi != null) {
                    Text("Предпросмотр без действий · элементы управления отключены",style = MaterialTheme.typography.labelMedium)
                    ToolContent(draft.previewUi,draft.previewState,false) { _,_ -> }
                    Button(onClick = { controller.accept(draft.id) },enabled = !controller.busy) { Text("Применить версию") }
                } else TextButton(onClick = { controller.retry(draft.id) },enabled = !controller.busy) { Text("Создать черновик без изображений") }
                TextButton(onClick = { controller.reject(draft.id) },enabled = !controller.busy) { Text("Отклонить черновик") }
                TextButton(onClick = { controller.select(null) }) { Text("Библиотека") }
            }
            tool != null -> {
                val spec = remember(tool.spec) { ToolSpec.parse(tool.spec) }
                Text(spec.title,style = MaterialTheme.typography.headlineSmall)
                Text("Версия ${tool.revision} · данные ${tool.stateVersion}",style = MaterialTheme.typography.labelSmall)
                ToolContent(tool.ui,tool.state,!controller.busy) { id,value -> controller.interact(tool,id,value) }
                com.alpha.assistant.capabilities.ToolCapabilityPanel(controller,tool)
                if(tool.saved) { Text("Сохранено в библиотеке",style = MaterialTheme.typography.labelLarge); com.alpha.assistant.surfaces.PinToolButton(tool) }
                else Button(onClick = { controller.save(tool.id) },enabled = !controller.busy) { Text("Сохранить в библиотеку") }
                TextButton(onClick = { refine = !refine; prompt = "" }) { Text("Изменить по запросу") }
                if(refine) {
                    OutlinedTextField(prompt,{ prompt = it },label = { Text("Опиши изменение") },modifier = Modifier.fillMaxWidth())
                    Button(onClick = { confirm = true },enabled = prompt.isNotBlank() && !controller.busy) { Text("Предпросмотр изменений") }
                }
                Text("История версий",style = MaterialTheme.typography.titleMedium)
                controller.revisions.forEach { revision ->
                    TextButton(onClick = { controller.rollback(tool,revision.revision) },enabled = !controller.busy && revision.revision != tool.revision) { Text("Восстановить версию ${revision.revision} · сохранить текущие данные") }
                }
                TextButton(onClick = { controller.select(null) }) { Text("Библиотека") }
            }
            else -> {
                Text("Создать инструмент",style = MaterialTheme.typography.titleLarge)
                Text("Опиши инструмент. После применения он работает на устройстве. Для системных действий инструменту нужен отдельный доступ. Каждое действие требует твоего подтверждения.")
                OutlinedTextField(prompt,{ prompt = it },label = { Text("Запрос для инструмента") },modifier = Modifier.fillMaxWidth())
                if(chatText.isNotBlank()) TextButton(onClick = { prompt = chatText }) { Text("Взять текст из чата") }
                if(images.isNotEmpty()) Row {
                    Checkbox(sendImages,{ sendImages = it })
                    Text("Добавить выбранные изображения в запрос: ${images.size}",Modifier.weight(1f))
                }
                Button(onClick = { confirm = true },enabled = prompt.isNotBlank() && prompt.length <= 16000 && !controller.busy) { Text("Создать предпросмотр") }
                if(controller.drafts.isNotEmpty()) Text("Черновики",style = MaterialTheme.typography.titleMedium)
                controller.drafts.forEach { pending -> TextButton(onClick = { controller.selectDraft(pending.id) }) { Text(pending.prompt.take(100)) } }
                Text("Библиотека",style = MaterialTheme.typography.titleLarge)
                val saved = controller.tools.filter { it.saved }
                if(saved.isEmpty()) Text("Сохранённых инструментов пока нет. Примени предпросмотр, затем сохрани инструмент в библиотеку.")
                saved.forEach { item -> ToolCard(item) { controller.select(item.id) } }
                val unsaved = controller.tools.filterNot { it.saved }
                if(unsaved.isNotEmpty()) Text("Применённые инструменты",style = MaterialTheme.typography.titleMedium)
                unsaved.forEach { item -> ToolCard(item) { controller.select(item.id) } }
            }
        }
    }
    if(confirm) AlertDialog(onDismissRequest = { confirm = false },title = { Text("Отправить запрос для инструмента в DeepSeek?") },text = {
        Text(if(tool != null) "DeepSeek получит запрос на изменение, код инструмента и текущие данные. Рабочая версия останется активной до применения изменений. Старые изображения не отправляются."
            else "DeepSeek получит запрос для инструмента${if(sendImages && images.isNotEmpty()) " и выбранные изображения: ${images.size}" else ""}. Предпросмотр не выполняет внешних действий. Проверь результат перед применением.")
    },confirmButton = { TextButton(onClick = {
        confirm = false
        controller.generate(prompt,tool?.id,conversationId,if(tool == null && sendImages) images else emptyList())
        prompt = ""; sendImages = false; refine = false
    }) { Text("Отправить запрос") } },dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}

@Composable private fun ToolContent(ui: String, state: String, enabled: Boolean, event: (String,Any?) -> Unit) {
    val node = remember(ui) { JSONObject(ui) }
    val values = remember(state) { JSONObject(state) }
    NativeNode(node,values,enabled,event)
}
@Composable private fun NativeNode(node: JSONObject, state: JSONObject, enabled: Boolean, event: (String,Any?) -> Unit) {
    val type = node.getString("type")
    val text = node.optString("text")
    val binding = node.optString("bind")
    when(type) {
        // shortcut: rows use vertical flow; add adaptive horizontal flow when tools need side-by-side controls.
        "column", "row" -> Column(Modifier.fillMaxWidth(),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val children = node.getJSONArray("children")
            repeat(children.length()) { NativeNode(children.getJSONObject(it),state,enabled,event) }
        }
        "text" -> Text(if(binding.isEmpty()) text else "$text: ${state.get(binding)}",style = MaterialTheme.typography.bodyLarge)
        "list" -> {
            Text(text,style = MaterialTheme.typography.titleMedium)
            val records = state.getJSONArray(binding)
            if(records.length() == 0) Text("Записей пока нет")
            repeat(records.length()) { index ->
                val record = records.getJSONObject(index)
                val label = record.keys().asSequence().filter { it != "id" }.joinToString(" · ") { key ->
                    val field = when(key) { "text" -> "Текст"; "title" -> "Название"; "name" -> "Название"; "done" -> "Готово"; "note" -> "Заметка"; "value" -> "Значение"; "date" -> "Дата"; else -> key }
                    val value = record.get(key).let { if(it is Boolean) { if(it) "Да" else "Нет" } else it }
                    "$field: $value"
                }
                if(node.has("action")) OutlinedButton(onClick = { event(node.getString("action"),record.getString("id")) },enabled = enabled,modifier = Modifier.fillMaxWidth()) { Text(label) }
                else Text(label)
            }
        }
        "button" -> Button(onClick = { event(node.getString("action"),null) },enabled = enabled,modifier = Modifier.fillMaxWidth()) { Text(text) }
        "toggle" -> Row(Modifier.fillMaxWidth()) {
            Checkbox(state.getBoolean(binding),{ event(node.getString("action"),it) },enabled = enabled)
            Text(text,Modifier.weight(1f).padding(top = 12.dp))
        }
        "input" -> {
            var value by remember(state.toString(),binding) { mutableStateOf(state.getString(binding)) }
            OutlinedTextField(value,{ if(it.length <= 2048) value = it },label = { Text(text) },enabled = enabled,modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { event(node.getString("action"),value) },enabled = enabled && value != state.getString(binding)) { Text("Применить: $text") }
        }
    }
}
