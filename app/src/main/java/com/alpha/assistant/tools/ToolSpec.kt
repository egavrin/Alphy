package com.alpha.assistant.tools

import com.alpha.assistant.images.GeneratedImage
import com.alpha.assistant.images.ImageIntent
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.three.ThreeSceneAssetRef
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.tools.generation.NativePresentation
import com.alpha.assistant.tools.generation.PartsContractFailure
import com.alpha.assistant.tools.generation.ToolComposition
import org.json.JSONArray
import org.json.JSONObject

internal enum class ToolValidationCategory(val id: String, val guidance: String) {
    JSON("tool_json", "Return one JSON object without Markdown. Keep the object within ${ToolSpec.MAX_DEFINITION_BYTES} bytes and nesting depth 24."),
    FIELDS("tool_fields", "Use exactly these fields: formatVersion, title, description, capabilities, stateSchema, initialState, ui, source. Optional surfaces declares identified native drawing surfaces. Optional presentation selects app or immersive mode. Optional hostControls declares playback and movement for declared surfaces. Optional capabilityResults and collections enable local typed results and private durable records. Remove other extra fields and include missing fields."),
    VERSION("tool_version", "Set formatVersion to the integer 3. Incompatible saved artifacts remain unavailable with their data intact."),
    METADATA("tool_metadata", "Use a title string with 1 to 100 characters and a description string with at most 500 characters."),
    CAPABILITIES("tool_capabilities", "Use an array of unique capability IDs from the supplied catalog. For local counters, use an empty array."),
    STATE_SCHEMA("tool_state_schema", "Use 1 to 40 named fields with scalar types number, string, or boolean, or documented bounded list, object, and array definitions. Do not use JSON Schema objects for scalars."),
    SOURCE("tool_source", "Use a nonempty JavaScript function body string within ${SandboxContract.MAX_SOURCE_BYTES} bytes."),
    INITIAL_STATE("tool_initial_state", "Supply exactly the declared state fields with valid types and bounded values. Lists need valid records with unique stable IDs."),
    STATE_NUMBER("tool_state_number", "Keep numeric state values finite and within ±${ToolValueSchema.MAX_SAFE_NUMBER.toLong()}, including calculated results at input bounds. Show an unavailable result if a calculation exceeds this range."),
    UI_STRUCTURE("tool_ui_structure", "Use the native UI catalog. Keep UI within ${ToolUiCatalog.MAX_NODES} nodes, depth 8, 50 children per container, and ${ToolUiCatalog.MAX_UI_BYTES} bytes."),
    UI_PROPERTIES("tool_ui_properties", "Remove unsupported properties. Put a component's tone and emphasis inside its style object. Use only the styles allowed for that component. visible and enabled must be booleans."),
    UI_BINDINGS("tool_ui_bindings", "Use declared dotted object paths with the documented type. Paths must exist in both schema and state. Never use array indices or prototype access."),
    UI_ACTIONS("tool_ui_actions", "Use a unique action ID for each actionable control. IDs must start with an ASCII letter and contain at most 48 letters, digits, or underscores."),
    DRAWING("tool_drawing", "Return only the documented fields for each drawing command."),
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
    internal val appLanguage: String?, internal val iconId: String?,
    internal val threeScene: Boolean, internal val sceneAssets: List<ThreeSceneAssetRef>,
    internal val generatedImages: List<GeneratedImage>, internal val lessonImageIntent: ImageIntent?) {
    internal val isSealedStandalone: Boolean get() = composition?.isSealedStandalone == true
    internal val canQueueLocalButtons: Boolean get() = !threeScene && capabilities.isEmpty() && collections.length() == 0 && surfaces.isEmpty()

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
            if (generatedImages.isNotEmpty()) validateTool(ToolValidationCategory.UI_STRUCTURE) {
                require(generatedImages.map { it.slot }.containsAll(imageSlots(root))) { "Unknown generated image slot." }
            }
            val placed = canvasSurfaceIds(root)
            if (placed.isNotEmpty() && (presentation != "app" || placed.size != surfaces.size || placed.toSet() != surfaces.keys ||
                    canvasSurfaceIds(ui).isEmpty())) {
                val observed = when {
                    presentation == "immersive" -> "canvas UI node in immersive presentation"
                    canvasSurfaceIds(ui).isEmpty() -> "inline canvas missing from initial UI"
                    else -> "invalid surface ownership"
                }
                throw ToolValidationFailure(ToolValidationCategory.UI_STRUCTURE, ToolValidationDetails(
                    "canvas_placement", "$.ui", "Declare inline canvases in initial app UI. Place each surface once. Immersive uses the host stage.", observed))
            }
            nativePresentation?.let { validateNativePresentation { it.focalNode(root) } }
            root
        } catch(failure: ToolValidationFailure) {
            if (failure.details?.rule == "canvas_placement") throw failure
            throw ToolValidationFailure(failure.category,failure.details,trace.failure(failure.category))
        }
    }
    fun action(ui: JSONObject, id: String): JSONObject? = ToolUiCatalog.action(ui,id)

    internal fun providerDefinition(): String {
        if (composition == null && nativePresentation == null && appLanguage == null && iconId == null && !threeScene && generatedImages.isEmpty() && lessonImageIntent == null) return json
        val definition = composition?.fillDefinition(this) ?: JSONObject(json).apply { remove(NativePresentation.FIELD) }
        definition.remove(APP_LANGUAGE_FIELD)
        definition.remove(ICON_ID_FIELD)
        definition.remove("sceneAssets")
        definition.remove("threeLibrary")
        definition.remove(GeneratedImage.FIELD)
        definition.remove("lessonImageIntent")
        return definition.toString()
    }

    internal fun withThreeAssets(assets: List<ThreeSceneAssetRef>): ToolSpec {
        require(threeScene)
        val definition = JSONObject(json).put("threeLibrary", THREE_LIBRARY)
            .put("sceneAssets", org.json.JSONArray(assets.map { it.json() }))
        return parsePersisted(definition.toString())
    }

    internal fun withGeneratedImages(images: List<GeneratedImage>): ToolSpec {
        if (images.isEmpty() && generatedImages.isEmpty()) return this
        val definition = JSONObject(json)
        if (images.isEmpty()) definition.remove(GeneratedImage.FIELD)
        else definition.put(GeneratedImage.FIELD, org.json.JSONArray(images.map { it.json() }))
        return parsePersisted(definition.toString())
    }

    internal fun withHostPolish(preset: String?, images: List<GeneratedImage>): ToolSpec {
        val definition = JSONObject(json)
        val root = definition.getJSONObject("ui")
        val children = root.getJSONArray("children")
        val existingSlots = generatedImages.map { it.slot }.toSet()
        val content = (0 until children.length()).map(children::getJSONObject).filterNot {
            it.optString("type") == "image" && it.optString("assetSlot") in existingSlots
        }
        root.put("children", JSONArray(content + images.map(::hostGeneratedImageNode)))
        if (images.isNotEmpty()) definition.put(GeneratedImage.FIELD, JSONArray(images.map { it.json() }))
        else definition.remove(GeneratedImage.FIELD)
        if (preset != null) {
            val recipe = nativePresentation
            definition.put(NativePresentation.FIELD, NativePresentation.json(
                recipe?.profile ?: com.alpha.assistant.tools.generation.NativePresentationProfile.FOCUS,
                preset, recipe?.focalPosition ?: 0, recipe?.primaryActionId))
        }
        return parsePersisted(definition.toString())
    }

    internal fun withLessonImage(intent: ImageIntent?, image: GeneratedImage? = null): ToolSpec {
        if (intent == lessonImageIntent && image == null) return this
        val definition = JSONObject(json)
        if (intent == null) definition.remove("lessonImageIntent") else definition.put("lessonImageIntent", intent.json())
        if (image != null) definition.put(GeneratedImage.FIELD, org.json.JSONArray(listOf(image.json())))
        return parsePersisted(definition.toString())
    }

    internal fun withAppLanguage(tag: String?): ToolSpec {
        if (tag == null || tag == appLanguage) return this
        require(validAppLanguageTag(tag))
        val encoded = JSONObject(json).put(APP_LANGUAGE_FIELD, tag).toString()
        validateTool(ToolValidationCategory.JSON, ToolValidationDetails("definition_size", "$",
            "at most $MAX_DEFINITION_BYTES bytes including metadata", "oversized definition")) {
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_DEFINITION_BYTES)
        }
        return copy(json = encoded, appLanguage = tag)
    }

    internal fun withIcon(id: String?): ToolSpec {
        if (id == iconId) return this
        val definition = JSONObject(json)
        if (id == null) definition.remove(ICON_ID_FIELD) else definition.put(ICON_ID_FIELD, id)
        return parsePersisted(definition.toString())
    }

    internal fun preserveNativePresentation(candidate: ToolSpec, observer: ((ToolUiDiagnostic) -> Unit)? = null): ToolSpec {
        require(candidate.nativePresentation == null)
        val recipe = nativePresentation ?: return candidate
        validateNativePresentation { recipe.focalNode(candidate.ui) }
        val definition = JSONObject(candidate.json).put(NativePresentation.FIELD, recipe.json())
        return parsePersisted(definition.toString(),observer)
    }

    companion object {
        private const val THREE_LIBRARY = "three@0.180.0"
        internal const val MAX_DEFINITION_BYTES = 256 * 1024

        private const val ICON_ID_FIELD = "iconId"
        private const val APP_LANGUAGE_FIELD = "appLanguage"

        fun available(raw: String): ToolSpec? = try {parse(raw)} catch(_: IllegalArgumentException) {null} catch(_: IllegalStateException) {null} catch(_: org.json.JSONException) {null}
        /** Only app-owned records may contain a host composition manifest. */
        internal fun availablePersisted(raw: String): ToolSpec? = try {parsePersisted(raw)} catch(_: IllegalArgumentException) {null} catch(_: IllegalStateException) {null} catch(_: org.json.JSONException) {null}
        internal fun availablePersisted(record: ToolRecord): ToolSpec? = try {
            parsePersisted(record.spec).also { it.validateUi(JSONObject(record.ui),JSONObject(record.state)) }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
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
            return parsePersisted(definition.toString()).withIcon(current.iconId)
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
        private val optionalGeneratedFields = setOf("capabilityResults", "collections", "surfaces", "presentation", "hostControls", "runtime")
        internal val generatedFields = requiredGeneratedFields + optionalGeneratedFields

        private fun parse(raw: String,observer: ((ToolUiDiagnostic)->Unit)?,persisted: Boolean): ToolSpec {
            val j = validateTool(ToolValidationCategory.JSON) { SandboxContract.objectFrom(raw, MAX_DEFINITION_BYTES) }
            validateTool(ToolValidationCategory.FIELDS) {
                val fields = j.keys().asSequence().toSet()
                val optional = optionalGeneratedFields +
                    (if (persisted) setOf("composition",NativePresentation.FIELD,APP_LANGUAGE_FIELD,ICON_ID_FIELD,"sceneAssets","threeLibrary",GeneratedImage.FIELD,"lessonImageIntent") else emptySet())
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
                j.getString("source").also { require(it.isNotBlank() && it.toByteArray().size <= SandboxContract.MAX_SOURCE_BYTES) }
            }
            val surfaces = validateTool(ToolValidationCategory.FIELDS, ToolValidationDetails(
                "surface_declarations", "$.surfaces", "an array of up to four {id,width,height} declarations with distinct IDs", "invalid surface declarations")) {
                val declarations=if(j.has("surfaces")) j.getJSONArray("surfaces") else org.json.JSONArray()
                require(declarations.length() <= 4) {"At most four surfaces are permitted."}
                (0 until declarations.length()).map {GraphicsContract.declaration(declarations.getJSONObject(it),schema)}
                    .also {require(it.map(ToolSurface::id).distinct().size == it.size)}.associateBy(ToolSurface::id)
            }
            validateTool(ToolValidationCategory.FIELDS, ToolValidationDetails(
                "surface_viewport", "$.surfaces", "one unpositioned surface for viewport sizing", "invalid viewport layout")) {
                require(surfaces.values.none { it.sizing == "viewport" } || surfaces.size == 1 && surfaces.values.single().x == null)
            }
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
            val iconId = validateTool(ToolValidationCategory.METADATA) {
                if (j.has(ICON_ID_FIELD)) (j.get(ICON_ID_FIELD) as String).also { id ->
                    require(AppBadge.entries.any { it.id == id })
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
            val threeScene = j.has("runtime")
            val sceneAssets = validateTool(ToolValidationCategory.FIELDS) {
                if (threeScene) {
                    val runtime = j.getJSONObject("runtime")
                    require(runtime.keys().asSequence().toSet() == setOf("kind", "version") &&
                        runtime.get("kind") == "threejs-worker" && runtime.get("version") == 1)
                    require(capabilities.isEmpty() && surfaces.isEmpty() && composition == null &&
                        nativePresentation == null && canvasSurfaceIds(ui).isEmpty() &&
                        !j.has("collections") && !j.has("hostControls") && !j.optBoolean("capabilityResults"))
                    if (persisted) {
                        require(j.get("threeLibrary") == THREE_LIBRARY)
                        ThreeSceneAssets.read(j.getJSONArray("sceneAssets"))
                    } else emptyList()
                } else {
                    require(!j.has("threeLibrary") && !j.has("sceneAssets"))
                    emptyList()
                }
            }
            val generatedImages = validateTool(ToolValidationCategory.FIELDS) {
                GeneratedImage.list(j.optJSONArray(GeneratedImage.FIELD)).also { images ->
                    require(!j.has(GeneratedImage.FIELD) || j.optJSONArray(GeneratedImage.FIELD) != null)
                    require(!threeScene || images.all { image -> sceneAssets.any { it.id == image.asset.id && it.mime == image.asset.mime && it.size == image.asset.size } })
                    if (j.has(GeneratedImage.FIELD)) require(images.map { it.slot }.containsAll(imageSlots(ui)))
                }
            }
            val lessonImageIntent = validateTool(ToolValidationCategory.FIELDS) {
                if (j.has("lessonImageIntent")) ImageIntent.read(j.getJSONObject("lessonImageIntent")) else null
            }
            val encoded = j.toString()
            validateTool(ToolValidationCategory.JSON) {
                require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_DEFINITION_BYTES)
            }
            return ToolSpec(encoded, j.getString("title"), j.getString("description"), source, schema,
                initialState, ui, capabilities, j.optBoolean("capabilityResults",false),j.optJSONObject("collections") ?: JSONObject(),surfaces,presentation,hostControls,composition,nativePresentation,appLanguage,iconId,threeScene,sceneAssets,generatedImages,lessonImageIntent).also {
                validateTool(ToolValidationCategory.INITIAL_STATE) { it.validateState(it.initialState) }
                it.validateUi(it.ui,JSONObject(it.initialState),ToolUiValidationStage.DEFINITION,observer)
            }
        }
    }
}

internal fun canvasSurfaceIds(node: JSONObject, state: JSONObject? = null): List<String> = buildList {
    if (state != null && !ToolUiCatalog.visible(node, state)) return@buildList
    val type = node.optString("type")
    if (state != null && type == "transition" && ToolUiCatalog.value(state, node.getString("bind")) != true) return@buildList
    if (state != null && type == "transform") {
        val pose = NativeLayerMath.target(node, state)
        val facing = kotlin.math.cos(pose[0] * Math.PI / 180) * kotlin.math.cos(pose[1] * Math.PI / 180)
        if (pose[7] == 0.0 || node.optBoolean("solid") && facing <= 0.0) return@buildList
    }
    if (node.optString("type") == "canvas") {
        val ids = node.getJSONArray("surfaces")
        repeat(ids.length()) { add(ids.getString(it)) }
    }
    node.optJSONArray("children")?.let { children ->
        val active = if (state == null) null else when (type) {
            "pager" -> (ToolUiCatalog.value(state, node.getString("bind")) as Number).toInt()
            "routeStack" -> {
                val route = ToolUiCatalog.routeStack(node, state).last()
                val routes = node.getJSONArray("routes")
                (0 until routes.length()).firstOrNull { routes.getString(it) == route }
            }
            else -> null
        }
        if (active != null) addAll(canvasSurfaceIds(children.getJSONObject(active), state))
        else repeat(children.length()) { addAll(canvasSurfaceIds(children.getJSONObject(it), state)) }
    }
}


internal fun hostGeneratedImageNode(image: GeneratedImage): JSONObject =
    JSONObject().put("type", "image").put("assetSlot", image.slot).put("text", image.alt)

internal fun imageSlots(root: JSONObject): Set<String> = buildSet {
    fun visit(value: Any?) {
        when (value) {
            is JSONObject -> {
                if (value.optString("type") == "image" && value.has("assetSlot")) add(value.getString("assetSlot"))
                value.keys().forEach { visit(value.opt(it)) }
            }
            is org.json.JSONArray -> repeat(value.length()) { visit(value.opt(it)) }
        }
    }
    visit(root)
}
