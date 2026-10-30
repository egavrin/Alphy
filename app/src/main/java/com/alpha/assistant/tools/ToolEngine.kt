package com.alpha.assistant.tools

import com.alpha.assistant.cloud.*
import com.alpha.assistant.capabilities.*
import org.json.JSONArray
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

data class ToolPreview(val spec: ToolSpec, val state: String, val ui: String, val effects: List<CapabilityRequest> = emptyList())
class ToolGenerationService(private val provider: ChatProvider, private val sandbox: SandboxClient) {
    suspend fun generate(key: String, prompt: String, base: ToolRecord?, images: List<String>, session: CloudSession, artifactId: String): ToolPreview {
        require(prompt.isNotBlank() && prompt.length <= 16000)
        require(images.size <= ImageInput.MAX_IMAGES)
        val instructions = """Create a local Alpha native mini-tool. Return only a JSON object, without Markdown. Schema:
{"formatVersion":2,"title":"short title","description":"short description","capabilities":[],"stateSchema":{"field":"number|string|boolean"},"initialState":{"field":0},"ui":{"type":"column","children":[{"type":"text","text":"Label","bind":"field"},{"type":"button","text":"Action","action":"uniqueId"}]},"source":"return {state:input.state};"}
Use column/row containers and text/button/input/toggle/list components. Text may bind a scalar state field. Input requires a string bind and action. Toggle requires a boolean bind and action. List requires a collection bind and text; optional action receives the selected record ID as event.value. Every action ID is unique. Every leaf needs text. No other props. Limits: 80 nodes, depth 8, 40 state fields, strings 2048 characters, state 48 KiB. Scalar schema types: number, string, boolean. Collection schema example: {"items":{"type":"list","maxItems":100,"fields":{"id":"string","text":"string","done":"boolean"}}}. Initial items is []. Records contain exactly the declared scalar fields and unique nonempty stable string id. No nested records or arrays. Use collections for task lists, shopping lists, notes and trackers.
JavaScript is a function BODY with input {artifactId,revision,state,event}. Events are {type:'preview'}, {type:'render'}, or {type:'action',actionId:'id',value:...}. Return {state:...}, optionally ui with the same native schema and effects:[{id:'uniqueId',capability:'catalog.id',args:{...}}]. Only action events may request effects, at most 3. Preview/render must preserve state and return no effects. No network, files, credentials, host APIs or imports. The native host validates declared capabilities, grants, Android permissions and explicit user confirmation. Never claim an external action completed from a request. Calendar creation only opens a form; saving is unverified. Capability results remain native and do not enter this model request automatically. Preserve all existing state fields, collection definitions and IDs in refinement. New fields may use initial defaults. Use the user's language for labels.
Implemented capabilities and exact args: ${CapabilityCatalog.definitions.joinToString("; ") { it.id + " " + it.arguments }}. Declare only IDs used by effects. Unavailable: ${CapabilityCatalog.unavailable.keys.joinToString()}. Request: $prompt"""
        val context = base?.let { "\nCurrent tool: ${it.spec}\nCurrent state: ${it.state}" }.orEmpty()
        val output = StringBuilder()
        provider.respond(key, CloudRequest(listOf(CloudTurn("user", instructions + context, images)), false), session) { event ->
            if(event is CloudEvent.Text) { require(output.length + event.value.length <= 65536) { "Ответ генератора превышает допустимый размер" }; output.append(event.value) }
        }
        val spec = ToolSpec.parse(output.toString().trim())
        val state = base?.let { ToolSpec.stateForRevision(ToolSpec.parse(it.spec),spec,it.state) } ?: spec.initialState
        spec.validateState(state)
        return evaluate(spec, artifactId, (base?.revision ?: 0) + 1, state, JSONObject().put("type", "preview"), spec.ui.toString()).also {
            require(JSONObject(it.state).toString() == JSONObject(state).toString()) { "Предпросмотр попытался изменить сохранённые данные" }
        }
    }
    suspend fun evaluate(spec: ToolSpec, id: String, revision: Int, state: String, event: JSONObject, fallbackUi: String): ToolPreview {
        spec.validateState(state)
        val input = JSONObject().put("artifactId",id).put("revision",revision).put("state",JSONObject(state)).put("event",event)
        val result = sandbox.execute(spec.source, input.toString())
        check(result.status == "OK") { "Не удалось безопасно выполнить инструмент: ${result.status}" }
        val json = JSONObject(checkNotNull(result.json))
        val next = spec.validateState(json.getJSONObject("state").toString()).toString()
        val ui = spec.validateUi(json.optJSONObject("ui") ?: JSONObject(fallbackUi)).toString()
        val effects = CapabilityCatalog.parseEffects(json.optJSONArray("effects") ?: JSONArray(),spec.capabilities,event.getString("type"))
        if(event.getString("type") != "action") require(JSONObject(next).toString() == JSONObject(state).toString()) { "Предпросмотр или отображение попытались изменить сохранённые данные" }
        return ToolPreview(spec, next, ui, effects)
    }
}

/** Revision and state tokens reject commands from old cards and drafts. */
class ToolEngine(private val store: ToolStore, private val generation: ToolGenerationService) {
    private val mutex = Mutex()
    suspend fun createDraft(prompt: String, toolId: String?, conversationId: String? = null): ToolDraft = mutex.withLock {
        require(prompt.isNotBlank() && prompt.length <= 16000)
        val base = toolId?.let { checkNotNull(store.tool(it)) }
        ToolDraft(UUID.randomUUID().toString(), toolId, base?.revision ?: 0, base?.stateVersion ?: 0, prompt, null, null, null, base?.conversationId ?: conversationId).also(store::saveDraft)
    }
    suspend fun generate(draftId: String, key: String, images: List<String>, session: CloudSession): ToolDraft {
        val (draft, base) = mutex.withLock { val d = checkNotNull(store.draft(draftId)); d to d.toolId?.let { current(it,d.baseRevision,d.baseStateVersion) } }
        val preview = generation.generate(key, draft.prompt, base, images, session, draft.toolId ?: draft.id)
        return mutex.withLock {
            val next = draft.copy(candidate = preview.spec.json, previewState = preview.state, previewUi = preview.ui)
            store.transaction {
                check(store.draft(draftId) == draft) { "Черновик изменился" }
                draft.toolId?.let { current(it,draft.baseRevision,draft.baseStateVersion) }
                store.saveDraft(next)
            }
            next
        }
    }
    suspend fun accept(draftId: String): ToolRecord = mutex.withLock {
        val d = checkNotNull(store.draft(draftId))
        val old = d.toolId?.let { current(it,d.baseRevision,d.baseStateVersion) }
        val spec = ToolSpec.parse(checkNotNull(d.candidate))
        val state = spec.validateState(checkNotNull(d.previewState)).toString()
        val ui = spec.validateUi(JSONObject(checkNotNull(d.previewUi))).toString()
        ToolRecord(old?.id ?: d.id, (old?.revision ?: 0)+1, (old?.stateVersion ?: 0)+1, spec.json, state, ui, d.conversationId, old?.saved ?: false).also { t ->
            store.commitTool(t,old,true,d)
        }
    }
    suspend fun reject(draftId: String) = mutex.withLock { store.deleteDraft(draftId) }
    suspend fun interact(id: String, revision: Int, stateVersion: Int, actionId: String, value: Any?): ToolRecord = mutex.withLock {
        val old = current(id,revision,stateVersion)
        val spec = ToolSpec.parse(old.spec)
        val node = checkNotNull(spec.action(JSONObject(old.ui),actionId)) { "Действие недоступно" }
        when(node.getString("type")) {
            "input" -> require(value is String && value.length <= 2048)
            "toggle" -> require(value is Boolean)
            "button" -> require(value == null)
            "list" -> {
                require(value is String && value.length <= 128)
                val records = JSONObject(old.state).getJSONArray(node.getString("bind"))
                require((0 until records.length()).any { records.getJSONObject(it).getString("id") == value }) { "Запись недоступна" }
            }
            else -> error("Действие недоступно")
        }
        val event = JSONObject().put("type","action").put("actionId",actionId).put("value",value ?: JSONObject.NULL)
        val result = generation.evaluate(spec,id,revision,old.state,event,old.ui)
        old.copy(stateVersion = stateVersion+1,state = result.state,ui = result.ui).also { store.commitTool(it,old,false,effects = result.effects) }
    }
    suspend fun rollback(id: String, revision: Int, stateVersion: Int, target: Int): ToolRecord = mutex.withLock {
        val old = current(id,revision,stateVersion)
        val previous = checkNotNull(store.revisions(id).find { it.revision == target })
        val spec = ToolSpec.parse(previous.spec)
        spec.validateState(old.state)
        val preview = generation.evaluate(spec,id,revision+1,old.state,JSONObject().put("type","render"),previous.ui)
        require(JSONObject(preview.state).toString() == JSONObject(old.state).toString()) { "Восстановление версии попыталось изменить данные" }
        old.copy(revision = revision+1,stateVersion = stateVersion+1,spec = spec.json,ui = preview.ui).also { store.commitTool(it,old,true) }
    }
    suspend fun save(id: String) = mutex.withLock { store.markSaved(id) }
    private fun current(id: String, revision: Int, stateVersion: Int) = checkNotNull(store.tool(id)).also {
        check(it.revision == revision && it.stateVersion == stateVersion) { "Инструмент изменился. Открой текущую версию." }
    }
}
