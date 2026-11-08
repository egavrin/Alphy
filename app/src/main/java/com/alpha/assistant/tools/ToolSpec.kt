package com.alpha.assistant.tools

import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.generation.NativePresentation
import com.alpha.assistant.tools.generation.PartsContractFailure
import com.alpha.assistant.tools.generation.ToolComposition
import org.json.JSONObject

internal enum class ToolValidationCategory(val id: String, val guidance: String) {
    JSON("tool_json", "Return one JSON object without Markdown. Keep the object within 65536 bytes and nesting depth 24."),
    FIELDS("tool_fields", "Use exactly these fields: formatVersion, title, description, capabilities, stateSchema, initialState, ui, source. Optional surfaces declares identified native drawing surfaces. Optional presentation selects app or immersive mode. Optional hostControls declares playback and movement for declared surfaces. Optional capabilityResults and collections enable local typed results and private durable records. Remove other extra fields and include missing fields."),
    VERSION("tool_version", "Set formatVersion to the integer 3. Incompatible saved artifacts remain unavailable with their data intact."),
    METADATA("tool_metadata", "Use a title string with 1 to 100 characters and a description string with at most 500 characters."),
    CAPABILITIES("tool_capabilities", "Use an array of unique capability IDs from the supplied catalog. For local counters, use an empty array."),
    STATE_SCHEMA("tool_state_schema", "Use 1 to 40 named fields with scalar types number, string, or boolean, or documented bounded list, object, and array definitions. Do not use JSON Schema objects for scalars."),
    SOURCE("tool_source", "Use a nonempty JavaScript function body string within 32768 bytes."),
    INITIAL_STATE("tool_initial_state", "Supply exactly the declared state fields with valid types and bounded values. Lists need valid records with unique stable IDs."),
    STATE_NUMBER("tool_state_number", "Keep numeric state values finite and within ±${ToolValueSchema.MAX_SAFE_NUMBER.toLong()}, including calculated results at input bounds. Show an unavailable result if a calculation exceeds this range."),
    UI_STRUCTURE("tool_ui_structure", "Use the native UI catalog. Keep UI within 256 nodes, depth 8, 50 children per container, and 32768 bytes."),
    UI_PROPERTIES("tool_ui_properties", "Remove unsupported properties. Put a component's tone and emphasis inside its style object. Use only the styles allowed for that component. visible and enabled must be booleans."),
    UI_BINDINGS("tool_ui_bindings", "Use declared dotted object paths with the documented type. Paths must exist in both schema and state. Never use array indices or prototype access."),
    UI_ACTIONS("tool_ui_actions", "Use a unique action ID for each actionable control. IDs must start with an ASCII letter and contain at most 48 letters, digits, or underscores."),
    COMPOSITION("tool_composition", "Keep the host-owned parts in their fixed positions. Use only fill-owned state, actions, and surfaces in generated fill."),
    NATIVE_PRESENTATION("tool_native_presentation", "Keep the existing focal slot structure, its position, and its action.");

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

private fun <T> validateHostMetadata(category: ToolValidationCategory, rule: String, block: () -> T): T = validateTool(category) {
    try {
        block()
    } catch (failure: PartsContractFailure) {
        throw ToolValidationFailure(category, ToolValidationDetails(rule,failure.path,failure.expected,"invalid host metadata"))
    }
}

private fun <T> validateComposition(block: () -> T): T =
    validateHostMetadata(ToolValidationCategory.COMPOSITION,"composition",block)

private fun <T> validateNativePresentation(block: () -> T): T =
    validateHostMetadata(ToolValidationCategory.NATIVE_PRESENTATION,"nativePresentation",block)

internal fun validAppLanguageTag(tag: String) = tag.length in 2..3 && tag.all { it in 'a'..'z' }

/** Generated data describes native controls. Generated code never runs in the host process. */
@ConsistentCopyVisibility
data class ToolSpec private constructor(val json: String, val title: String, val description: String, val source: String,
    val schema: JSONObject, val initialState: String, val ui: JSONObject, val capabilities: Set<String>, val capabilityResults: Boolean,
    val collections: JSONObject, val surfaces: Map<String,ToolSurface>, val presentation: String,
    internal val hostControls: ArtifactHostControls,
    internal val composition: ToolComposition?, internal val nativePresentation: NativePresentation?,
    internal val appLanguage: String?) {
    internal val isSealedStandalone: Boolean get() = composition?.isSealedStandalone == true
    internal val canQueueLocalButtons: Boolean get() = capabilities.isEmpty() && collections.length() == 0 && surfaces.isEmpty()

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
            val catalogRoot = composition?.let { validateComposition { it.catalogUi(root,state) } } ?: root
            validateTool(ToolValidationCategory.UI_STRUCTURE) {ToolUiCatalog.validate(catalogRoot,schema,state,trace,capabilities)}
            nativePresentation?.let { validateNativePresentation { it.focalNode(root) } }
            root
        } catch(failure: ToolValidationFailure) {
            throw ToolValidationFailure(failure.category,failure.details,trace.failure(failure.category))
        }
    }
    fun action(ui: JSONObject, id: String): JSONObject? = ToolUiCatalog.action(ui,id)

    internal fun providerDefinition(): String {
        if (composition == null && nativePresentation == null && appLanguage == null) return json
        val definition = composition?.fillDefinition(this) ?: JSONObject(json).apply { remove(NativePresentation.FIELD) }
        definition.remove(APP_LANGUAGE_FIELD)
        return definition.toString()
    }

    internal fun withAppLanguage(tag: String?): ToolSpec {
        if (tag == null || tag == appLanguage) return this
        require(validAppLanguageTag(tag))
        val encoded = JSONObject(json).put(APP_LANGUAGE_FIELD, tag).toString()
        validateTool(ToolValidationCategory.JSON, ToolValidationDetails("definition_size", "$",
            "at most 65536 bytes including native presentation metadata", "oversized definition")) {
            require(encoded.toByteArray(Charsets.UTF_8).size <= SandboxContract.MAX_BYTES)
        }
        return copy(json = encoded, appLanguage = tag)
    }

    internal fun preserveNativePresentation(candidate: ToolSpec, observer: ((ToolUiDiagnostic) -> Unit)? = null): ToolSpec {
        require(candidate.nativePresentation == null)
        val recipe = nativePresentation ?: return candidate
        validateNativePresentation { recipe.focalNode(candidate.ui) }
        val definition = JSONObject(candidate.json).put(NativePresentation.FIELD, recipe.json())
        return parsePersisted(definition.toString(),observer)
    }

    companion object {
        private const val APP_LANGUAGE_FIELD = "appLanguage"

        fun available(raw: String): ToolSpec? = try {parse(raw)} catch(_: IllegalArgumentException) {null} catch(_: IllegalStateException) {null} catch(_: org.json.JSONException) {null}
        /** Only app-owned records may contain a host composition manifest. */
        internal fun availablePersisted(raw: String): ToolSpec? = try {parsePersisted(raw)} catch(_: IllegalArgumentException) {null} catch(_: IllegalStateException) {null} catch(_: org.json.JSONException) {null}
        fun storedTitle(raw: String): String = runCatching {JSONObject(raw).optString("title").take(100).ifBlank {"Unavailable mini-app"}}.getOrDefault("Unavailable mini-app")
        /** Refinements may add fields, but cannot remove, retype, or reset stored values. */
        fun stateForRevision(previous: ToolSpec, next: ToolSpec, raw: String): String {
            requireSameComposition(previous,next)
            requireSameNativePresentation(previous,next)
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
        fun forRestore(current: ToolSpec,previous: ToolSpec): ToolSpec {
            requireSameComposition(current,previous)
            requireSameNativePresentation(current,previous)
            val definition=JSONObject(previous.json)
                .put("stateSchema",current.schema)
                .put("initialState",JSONObject(current.initialState))
            if(current.collections.length()>0) definition.put("collections",current.collections)
            else definition.remove("collections")
            return parsePersisted(definition.toString())
        }
        private fun requireSameComposition(previous: ToolSpec,next: ToolSpec) = validateComposition {
            val composition = previous.composition
            if (composition == null) require(next.composition == null)
            else composition.requireSameOwnership(next.composition)
        }

        private fun requireSameNativePresentation(previous: ToolSpec,next: ToolSpec) = validateNativePresentation {
            val recipe = previous.nativePresentation
            if (recipe == null) require(next.nativePresentation == null)
            else {
                require(recipe.sameSelection(next.nativePresentation))
                recipe.focalNode(next.ui)
            }
        }

        /** Parse provider, import, or manual edit input. This boundary rejects host metadata. */
        fun parse(raw: String): ToolSpec = parse(raw,null)
        internal fun parse(raw: String,observer: ((ToolUiDiagnostic)->Unit)?): ToolSpec = parse(raw,observer,false)

        /** Parse host-created artifacts from app-owned storage or a host builder. */
        internal fun parsePersisted(raw: String,observer: ((ToolUiDiagnostic)->Unit)? = null): ToolSpec = parse(raw,observer,true)

        private val requiredGeneratedFields = setOf("formatVersion", "title", "description", "capabilities", "stateSchema", "initialState", "ui", "source")
        private val optionalGeneratedFields = setOf("capabilityResults", "collections", "surfaces", "presentation", "hostControls")
        internal val generatedFields = requiredGeneratedFields + optionalGeneratedFields

        private fun parse(raw: String,observer: ((ToolUiDiagnostic)->Unit)?,persisted: Boolean): ToolSpec {
            val j = validateTool(ToolValidationCategory.JSON) { SandboxContract.objectFrom(raw) }
            validateTool(ToolValidationCategory.FIELDS) {
                val fields = j.keys().asSequence().toSet()
                val optional = optionalGeneratedFields +
                    (if (persisted) setOf("composition",NativePresentation.FIELD,APP_LANGUAGE_FIELD) else emptySet())
                require(fields.containsAll(requiredGeneratedFields) && fields.all { it in requiredGeneratedFields || it in optional })
                require(!j.has("capabilityResults") || j.get("capabilityResults") is Boolean) { "Неверные поля инструмента" }
            }
            validateTool(ToolValidationCategory.VERSION) { require(j.get("formatVersion") is Int && j.getInt("formatVersion") == 3) }
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
            val surfaces = validateTool(ToolValidationCategory.SOURCE) {
                val declarations=if(j.has("surfaces")) j.getJSONArray("surfaces") else org.json.JSONArray()
                require(declarations.length() <= 4) {"At most four surfaces are permitted."}
                (0 until declarations.length()).map {GraphicsContract.declaration(declarations.getJSONObject(it),schema)}
                    .also {require(it.map(ToolSurface::id).distinct().size == it.size)}.associateBy(ToolSurface::id)
            }
            validateTool(ToolValidationCategory.SOURCE) {require(surfaces.values.none {it.sizing=="viewport"} || surfaces.size==1 && surfaces.values.single().x==null) {"Viewport sizing requires one unpositioned surface."}}
            val hostControls = validateTool(ToolValidationCategory.FIELDS,
                ToolValidationDetails("host_controls", "$.hostControls", "Boolean playback and unique declared movement surface IDs", "invalid controls")) {
                if (j.has("hostControls")) ArtifactHostControls.parse(j.getJSONObject("hostControls"), surfaces.keys)
                else ArtifactHostControls()
            }
            val presentation=validateTool(ToolValidationCategory.FIELDS) {
                if(j.has("presentation")) j.getJSONObject("presentation").let {
                    require(it.keys().asSequence().toSet()==setOf("mode") && it.get("mode") in setOf("app","immersive"))
                    it.getString("mode")
                } else "app"
            }
            val appLanguage = validateTool(ToolValidationCategory.METADATA) {
                if (j.has(APP_LANGUAGE_FIELD)) (j.get(APP_LANGUAGE_FIELD) as String).also {
                    require(validAppLanguageTag(it))
                } else null
            }
            val initialState = validateTool(ToolValidationCategory.INITIAL_STATE) { j.getJSONObject("initialState").toString() }
            val ui = try {validateTool(ToolValidationCategory.UI_STRUCTURE) { j.getJSONObject("ui") }}
                catch(failure: ToolValidationFailure) {
                    val trace=ToolUiValidationTrace(ToolUiValidationStage.DEFINITION,observer)
                    trace.at("ui_object","$.ui","object",j.opt("ui"))
                    throw ToolValidationFailure(failure.category,failure.details,trace.failure(failure.category))
                }
            val composition = if (j.has("composition")) validateComposition {
                ToolComposition.read(j,schema,JSONObject(initialState),surfaces.keys)
            } else null
            val nativePresentation = if (j.has(NativePresentation.FIELD)) validateNativePresentation {
                NativePresentation.parse(j.getJSONObject(NativePresentation.FIELD),ui).also {
                    composition?.requireNativePresentation(it)
                }
            } else null
            return ToolSpec(j.toString(), j.getString("title"), j.getString("description"), source, schema,
                initialState, ui, capabilities, j.optBoolean("capabilityResults",false),j.optJSONObject("collections") ?: JSONObject(),surfaces,presentation,hostControls,composition,nativePresentation,appLanguage).also {
                validateTool(ToolValidationCategory.INITIAL_STATE) { it.validateState(it.initialState) }
                it.validateUi(it.ui,JSONObject(it.initialState),ToolUiValidationStage.DEFINITION,observer)
            }
        }
    }
}
