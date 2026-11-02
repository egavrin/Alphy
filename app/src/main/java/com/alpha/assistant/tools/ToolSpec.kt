package com.alpha.assistant.tools

import com.alpha.assistant.sandbox.SandboxContract
import org.json.JSONObject

internal enum class ToolValidationCategory(val id: String, val guidance: String) {
    JSON("tool_json", "Return one JSON object without Markdown. Keep the object within 65536 bytes and nesting depth 24."),
    FIELDS("tool_fields", "Use exactly these fields: formatVersion, title, description, capabilities, stateSchema, initialState, ui, source. Optional surface enables one isolated native drawing session. Optional capabilityResults and collections enable local typed results and private durable records. Remove other extra fields and include missing fields."),
    VERSION("tool_version", "Set formatVersion to the integer 2."),
    METADATA("tool_metadata", "Use a title string with 1 to 100 characters and a description string with at most 500 characters."),
    CAPABILITIES("tool_capabilities", "Use an array of unique capability IDs from the supplied catalog. For local counters, use an empty array."),
    STATE_SCHEMA("tool_state_schema", "Use 1 to 40 named fields with scalar types number, string, or boolean, or documented bounded list, object, and array definitions. Do not use JSON Schema objects for scalars."),
    SOURCE("tool_source", "Use a nonempty JavaScript function body string within 32768 bytes."),
    INITIAL_STATE("tool_initial_state", "Supply exactly the declared state fields with valid types and bounded values. Lists need valid records with unique stable IDs."),
    UI_STRUCTURE("tool_ui_structure", "Use the native UI catalog. Keep UI within 256 nodes, depth 8, 50 children per container, and 32768 bytes."),
    UI_PROPERTIES("tool_ui_properties", "Remove unsupported properties. Use only the documented catalog properties and finite style values. visible and enabled must be booleans."),
    UI_BINDINGS("tool_ui_bindings", "Use declared dotted object paths with the documented type. Paths must exist in both schema and state. Never use array indices or prototype access."),
    UI_ACTIONS("tool_ui_actions", "Use a unique action ID for each actionable control. IDs must start with an ASCII letter and contain at most 48 letters, digits, or underscores.");

    val feedback get() = "Validation category: $id. $guidance"
}
internal data class ToolValidationDetails(val rule: String, val path: String, val expected: String, val observed: String) {
    val feedback get() = "Schema rule: ${rule.take(48)}. Path: ${path.take(512)}. Expected: ${expected.take(128)}. Observed: ${observed.take(64)}."
}
internal class ToolValidationFailure(val category: ToolValidationCategory, val details: ToolValidationDetails? = null, val uiDiagnostic: ToolUiDiagnostic? = null) :
    IllegalArgumentException(category.feedback + details?.let { " ${it.feedback}" }.orEmpty())
internal fun <T> validateTool(category: ToolValidationCategory, details: ToolValidationDetails? = null, validate: () -> T): T = try { validate() }
    catch(failure: Exception) {
        if(failure is ToolValidationFailure) throw failure
        if(failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is org.json.JSONException) throw failure
        throw ToolValidationFailure(category,details)
    }

/** Generated data describes native controls. Generated code never runs in the host process. */
data class ToolSpec(val json: String, val title: String, val description: String, val source: String,
    val schema: JSONObject, val initialState: String, val ui: JSONObject, val capabilities: Set<String>, val capabilityResults: Boolean = false, val collections: JSONObject = JSONObject(), val surface: ToolSurface? = null) {
    fun validateState(value: String): JSONObject {
        require(value.toByteArray().size <= 49152) { "Данные инструмента превышают 48 КиБ" }
        val state = SandboxContract.objectFrom(value)
        ToolValueSchema.state(schema,state)
        return state
    }
    fun validateUi(root: JSONObject, state: JSONObject = JSONObject(initialState)): JSONObject =
        validateUi(root,state,ToolUiValidationStage.STATE,null)
    internal fun validateUi(root: JSONObject,state: JSONObject,stage: ToolUiValidationStage,observer: ((ToolUiDiagnostic)->Unit)?): JSONObject {
        val trace=ToolUiValidationTrace(stage,observer)
        return try {
            validateTool(ToolValidationCategory.UI_STRUCTURE) {ToolUiCatalog.validate(root,schema,state,trace)}
        } catch(failure: ToolValidationFailure) {
            throw ToolValidationFailure(failure.category,failure.details,trace.failure(failure.category))
        }
    }
    fun action(ui: JSONObject, id: String): JSONObject? = ToolUiCatalog.action(ui,id)
    companion object {
        /** Refinements may add fields, but cannot remove, retype, or reset stored values. */
        fun stateForRevision(previous: ToolSpec, next: ToolSpec, raw: String): String {
            val state = previous.validateState(raw)
            previous.schema.keys().forEach { key ->
                require(next.schema.has(key) && ToolValueSchema.same(previous.schema.get(key),next.schema.get(key))) { "Несовместимая схема данных: $key. Сохранённые данные не изменены." }
            }
            previous.collections.keys().forEach { key ->
                require(next.collections.has(key) && ToolValueSchema.same(previous.collections.get(key),next.collections.get(key))) { "Existing collection contracts must remain unchanged." }
            }
            val initial = JSONObject(next.initialState)
            next.schema.keys().forEach { key -> if(!state.has(key)) state.put(key,initial.get(key)) }
            return next.validateState(state.toString()).toString()
        }
        fun parse(raw: String): ToolSpec = parse(raw,null)
        internal fun parse(raw: String,observer: ((ToolUiDiagnostic)->Unit)?): ToolSpec {
            val j = validateTool(ToolValidationCategory.JSON) { SandboxContract.objectFrom(raw) }
            validateTool(ToolValidationCategory.FIELDS) {
                val required = setOf("formatVersion", "title", "description", "capabilities", "stateSchema", "initialState", "ui", "source")
                val fields = j.keys().asSequence().toSet()
                require(fields.containsAll(required) && fields.all { it in required || it in setOf("capabilityResults","collections","surface") })
                require(!j.has("capabilityResults") || j.get("capabilityResults") is Boolean) { "Неверные поля инструмента" }
            }
            validateTool(ToolValidationCategory.VERSION) { require(j.get("formatVersion") is Int && j.getInt("formatVersion") == 2) }
            validateTool(ToolValidationCategory.METADATA) {
                require(j.get("title") is String && j.getString("title").length in 1..100)
                require(j.get("description") is String && j.getString("description").length <= 500)
            }
            val capabilities = validateTool(ToolValidationCategory.CAPABILITIES) {
                val declared = j.getJSONArray("capabilities")
                require(declared.length() <= com.alpha.assistant.capabilities.CapabilityCatalog.ids.size)
                (0 until declared.length()).map { declared.getString(it) }.toSet().also { capabilities ->
                    require(capabilities.size == declared.length() && capabilities.all { it in com.alpha.assistant.capabilities.CapabilityCatalog.ids }) { "Неизвестная системная функция" }
                }
            }
            val schema = validateTool(ToolValidationCategory.STATE_SCHEMA) {
                j.getJSONObject("stateSchema").also { schema ->
                    ToolValueSchema.validateSchemas(schema,if(j.has("collections")) j.getJSONObject("collections") else JSONObject())
                }
            }
            val source = validateTool(ToolValidationCategory.SOURCE) {
                j.getString("source").also { require(it.isNotBlank() && it.toByteArray().size <= 32768) }
            }
            val surface = validateTool(ToolValidationCategory.SOURCE) {
                (if(j.has("surface")) GraphicsContract.declaration(j.getJSONObject("surface"),schema) else null).also {
                    require(source.toByteArray().size + (it?.source?.toByteArray()?.size ?: 0) <= 32768) { "Combined source exceeds 32 KiB." }
                }
            }
            val initialState = validateTool(ToolValidationCategory.INITIAL_STATE) { j.getJSONObject("initialState").toString() }
            val ui = try {validateTool(ToolValidationCategory.UI_STRUCTURE) { j.getJSONObject("ui") }}
                catch(failure: ToolValidationFailure) {
                    val trace=ToolUiValidationTrace(ToolUiValidationStage.DEFINITION,observer)
                    trace.at("ui_object","$.ui","object",j.opt("ui"))
                    throw ToolValidationFailure(failure.category,failure.details,trace.failure(failure.category))
                }
            return ToolSpec(j.toString(), j.getString("title"), j.getString("description"), source, schema,
                initialState, ui, capabilities, j.optBoolean("capabilityResults",false),j.optJSONObject("collections") ?: JSONObject(),surface).also {
                validateTool(ToolValidationCategory.INITIAL_STATE) { it.validateState(it.initialState) }
                it.validateUi(it.ui,JSONObject(it.initialState),ToolUiValidationStage.DEFINITION,observer)
            }
        }
    }
}
