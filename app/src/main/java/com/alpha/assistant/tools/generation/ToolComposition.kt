package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolUiDiagnostic
import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject

internal class StandaloneRefinementUnavailable : IllegalStateException(
    "Generated refinement is unavailable for this mini-app. Its saved data is unchanged."
)

/** The host owns part state and UI. The sandbox receives only the fill partition. */
internal class ToolComposition private constructor(private val layout: Layout, private val fillSchema: JSONObject) {
    private data class FixedPart(val position: Int, val needId: String, val part: PartCandidate) {
        val instanceId = partInstanceId(needId)
        val root: JSONObject = JSONObject(part.fragment.full.toString())
            .put("partId", part.id).put("partInstanceId", instanceId)
        val projection = part.fragment.readProjection
        private val fields = ToolValueSchema.keys(part.fragment.schema)
        val displaySchema = JSONObject().apply {
            projection?.fields?.forEach { put(it, part.fragment.schema.get(it)) }
        }
        val durableSchema = JSONObject().apply {
            (fields - projection?.fields.orEmpty()).forEach { put(it, part.fragment.schema.get(it)) }
        }

        fun durableCopy(state: JSONObject): JSONObject = JSONObject(select(state, durableSchema).toString())

        fun checkDisplayDefaults(state: JSONObject) {
            displaySchema.keys().forEach { key ->
                requireParts(sameToolJson(state.opt(key), part.fragment.initial.get(key)), "$.state",
                    "durable state with unchanged display defaults")
            }
        }

        fun manifest() = JSONObject().put("position", position).put("needId", needId)
            .put("partId", part.id).put("instanceId", instanceId).put("config", part.config)
    }

    private class Layout(parts: List<FixedPart>, val fillPositions: List<Int>, val readPorts: Set<String> = emptySet()) {
        val fixed = parts.associateBy(FixedPart::position)
        val size = parts.size + fillPositions.size
        val numbers = parts.flatMap { fixed ->
            listOfNotNull(fixed.part.numberOutput?.let { fixed.needId to it }) +
                fixed.part.aggregates.map { (key, output) -> "${fixed.needId}:$key" to output }
        }.toMap()
        val projections: List<FixedPart>

        init {
            requireParts((fillPositions.isNotEmpty() || readPorts.isEmpty()) && numbers.keys.containsAll(readPorts),
                "$.composition.readPorts", "declared numeric outputs for generated fill")
            val projected = parts.filter { it.projection != null }.associateBy(FixedPart::needId)
            val dependencies = projected.mapValues { checkNotNull(it.value.projection).reads }
            requireParts(dependencies.values.all { reads -> reads.all(numbers::containsKey) },
                "$.composition.parts", "declared numeric part dependencies")
            projections = dependencyOrder(dependencies, "$.composition.parts").map(projected::getValue)
        }

        fun manifest() = JSONObject().put("version", VERSION)
            .put("parts", jsonArray(fixed.values.map(FixedPart::manifest))).put("fillPositions", jsonArray(fillPositions))
            .apply { if (readPorts.isNotEmpty()) put("readPorts", jsonArray(readPorts.sorted())) }

        fun mergeUi(fill: JSONObject): JSONObject {
            val children = columnChildren(fill, fillPositions.size, "$.fill.ui")
            children.forEach(::requireFillActions)
            val fillByPosition = fillPositions.zip(children).toMap()
            return partContainer("column", (0 until size).map { position ->
                fixed[position]?.let { JSONObject(it.root.toString()) } ?: fillByPosition.getValue(position)
            })
        }
    }

    private val partActions = layout.fixed.values.flatMap { fixed ->
        fixed.part.fragment.handlers.map { (id, handler) -> id to (fixed to handler) }
    }.toMap()

    val hasReadProjections: Boolean get() = layout.fixed.values.any { it.projection != null }
    val requiresClockSample: Boolean get() = hasReadProjections || layout.fixed.values.any { fixed ->
        fixed.part.fragment.handlers.values.any { it is PartHandler.Clocked }
    }
    val isSealedStandalone: Boolean get() = layout.fillPositions.isEmpty()
    internal val fillPositions: List<Int> get() = layout.fillPositions
    val readPorts: Set<String> get() = layout.readPorts
    fun needsClockTicks(state: JSONObject, clock: PartClockSample): Boolean =
        layout.fixed.values.any { it.part.clockTicks?.invoke(state, clock) == true }

    /** Values are a disposable snapshot. The sandbox cannot return them as state. */
    fun fillPorts(state: JSONObject, clock: PartClockSample?): JSONObject {
        val view = if (hasReadProjections) projectState(state, checkNotNull(clock)) else state
        return JSONObject().apply {
            for (name in readPorts) put(name, layout.numbers.getValue(name).read(view) ?: JSONObject.NULL)
        }
    }

    /** Project a validated durable state for display. Validate the aggregate before rendering it. */
    fun projectState(durableState: JSONObject, clock: PartClockSample): JSONObject {
        val view = JSONObject(durableState.toString())
        for (fixed in layout.projections) {
            val projection = checkNotNull(fixed.projection)
            fixed.checkDisplayDefaults(durableState)
            val reads = projection.reads.associateWith { layout.numbers.getValue(it).read(view) }
            val display = projection.render(fixed.durableCopy(durableState), clock, reads)
            ToolValueSchema.state(fixed.displaySchema, display)
            display.keys().forEach { view.put(it, display.get(it)) }
        }
        return view
    }

    /** Pass this state as the entire input.state to generated fill. */
    fun fillState(fullState: JSONObject): JSONObject = select(fullState, fillSchema)

    /** Merge only schema-valid fill writes into a copy of the authoritative full state. */
    fun mergeFillState(fullState: JSONObject, resultState: JSONObject): JSONObject {
        ToolValueSchema.state(fillSchema, resultState)
        return JSONObject(fullState.toString()).apply {
            resultState.keys().forEach { put(it, resultState.get(it)) }
        }
    }

    /** The dynamic fill UI uses a column with one child per fill position, in position order. */
    fun mergeFillUi(fillUi: JSONObject): JSONObject = layout.mergeUi(fillUi)

    fun fillUi(fullUi: JSONObject): JSONObject {
        val children = checkedChildren(fullUi)
        val fill = layout.fillPositions.map(children::get)
        fill.forEach(::requireFillActions)
        return partContainer("column", fill)
    }

    /** Returns null for a fill event. A native part event must never enter the sandbox. */
    fun applyPartEvent(event: JSONObject, fullUi: JSONObject, fullState: JSONObject, clock: PartClockSample? = null): JSONObject? {
        if (event.opt("type") != "action") return null
        val id = event.opt("actionId") as? String
        val partAction = partActions[id]
        if (partAction == null) {
            requireParts(!isSealedStandalone && id != null && isFillId(id), "$.event.actionId",
                if (isSealedStandalone) "a declared part action" else "a declared part or fill action")
            return null
        }
        event.requireKeys("$.event", setOf("type", "actionId", "value"))
        val (fixed, handler) = partAction
        val root = checkedChildren(fullUi)[fixed.position]
        val value = event.opt("value").takeUnless { it == JSONObject.NULL }
        val actionId = checkNotNull(id)
        return when (handler) {
            is PartHandler.Script -> {
                ToolUiCatalog.validateAction(root, actionId, value, fullState)
                JSONObject(fullState.toString()).also { handler.transition(it, value) }
            }
            is PartHandler.Clocked -> {
                val sample = clock ?: throw PartsContractFailure("$.event", "a host clock sample")
                ToolUiCatalog.validateAction(root, actionId, value, projectState(fullState, sample))
                val partState = fixed.durableCopy(fullState)
                handler.transition(partState, value, sample)
                ToolValueSchema.state(fixed.durableSchema, partState)
                JSONObject(fullState.toString()).apply { partState.keys().forEach { put(it, partState.get(it)) } }
            }
        }
    }

    /** Use this projection for refinement. It contains only definition data owned by fill. */
    fun fillDefinition(spec: ToolSpec): JSONObject {
        if (isSealedStandalone) throw StandaloneRefinementUnavailable()
        return JSONObject(spec.json).apply {
            remove("composition")
            remove(NativePresentation.FIELD)
            remove("formatVersion")
            put("stateSchema", JSONObject(fillSchema.toString()))
            put("initialState", fillState(JSONObject(spec.initialState)))
            put("ui", fillUi(spec.ui))
        }
    }

    internal fun requireSameOwnership(next: ToolComposition?) {
        requireParts(next != null && sameToolJson(layout.manifest(), next.layout.manifest()),
            "$.composition", "the existing part ownership and fill positions")
    }

    internal fun editableParts(): List<NativeEditablePart> {
        requireParts(isSealedStandalone, "$.composition", "a sealed standalone composition")
        return layout.fixed.values.mapNotNull { fixed ->
            val kind = NativeEditKind.entries.firstOrNull { it.wire == fixed.part.id } ?: return@mapNotNull null
            NativeEditablePart(fixed.position, fixed.instanceId, kind, fixed.part.config.toString())
        }
    }

    internal fun resealNativeEdit(previous: ToolSpec, configs: Map<Int, JSONObject>, title: String, description: String,
        observer: ((ToolUiDiagnostic) -> Unit)?): ToolSpec {
        val editablePositions = editableParts().map(NativeEditablePart::position).toSet()
        requireParts(editablePositions.containsAll(configs.keys),
            "$.composition", "configurations for editable parts")
        val fixed = layout.fixed.values.map { current ->
            val config = configs[current.position] ?: return@map current
            val next = ToolParts.restore(current.needId, current.part.id, config, "$.parts.config", allowNativeEdits = true)
            requireParts(sameToolJson(current.part.fragment.schema, next.fragment.schema) &&
                current.part.fragment.handlers.keys == next.fragment.handlers.keys, "$.composition",
                "the existing state schema and action IDs")
            FixedPart(current.position, current.needId, next)
        }
        return sealStandalone(Layout(fixed, emptyList()), title, description, previous.nativePresentation, observer)
            .withAppLanguage(previous.appLanguage)
    }

    internal fun requireNativePresentation(recipe: NativePresentation) {
        val part = layout.fixed[recipe.focalPosition]?.part
            ?: throw PartsContractFailure("$.nativePresentation.focalPosition", "a host-owned part position")
        requireParts(recipe.primaryActionId == null || recipe.primaryActionId == part.primaryActionId,
            "$.nativePresentation.primaryActionId", "the focal part's primary action")
    }

    internal fun catalogUi(fullUi: JSONObject, fullState: JSONObject): JSONObject {
        requireParts(fullUi.toString().toByteArray(Charsets.UTF_8).size <= 32768, "$.ui", "at most 32768 bytes")
        val children = checkedChildren(fullUi)
        if (!isSealedStandalone) {
            val fill = layout.fillPositions.map(children::get)
            fill.forEach(::requireFillActions)
            ToolUiCatalog.validate(partContainer("column", fill), fillSchema, fillState(fullState))
        }
        return partContainer("column", children.mapIndexed { position, node ->
            layout.fixed[position]?.part?.fragment?.full ?: node
        })
    }

    private fun checkedChildren(fullUi: JSONObject): List<JSONObject> =
        columnChildren(fullUi, layout.size, "$.ui").also { children ->
            layout.fixed.forEach { (position, fixed) ->
                requireParts(sameToolJson(children[position], fixed.root), "$.ui.children[$position]", "the canonical part root")
            }
        }

    companion object {
        const val FILL_PREFIX = "fill_"
        private const val VERSION = 1
        private const val MAX_POSITIONS = 12
        private const val STANDALONE_SOURCE = "return {state:input.state};"
        private val fillRequired = setOf("stateSchema", "initialState", "ui", "source", "capabilities")
        private val fillAuthority = setOf("capabilityResults", "collections", "surfaces", "presentation")
        private val fillOptional = setOf("title", "description") + fillAuthority
        internal val generatedFillFields = fillRequired + fillOptional

        /** Create a standalone definition from restored host parts and revalidate the stored envelope. */
        fun sealStandalone(assembly: PartsAssembly, observer: ((ToolUiDiagnostic) -> Unit)? = null): ToolSpec {
            requireParts(assembly.parts.isNotEmpty() && assembly.holes.isEmpty() && assembly.blockingQuestions.isEmpty(),
                "$.composition", "a complete assembly of host parts")
            return sealStandalone(Layout(restoreParts(assembly), emptyList()), assembly.preview.title, assembly.preview.description,
                assembly.preview.nativePresentation, observer)
        }

        private fun sealStandalone(layout: Layout, title: String, description: String, recipe: NativePresentation?,
            observer: ((ToolUiDiagnostic) -> Unit)?): ToolSpec {
            val fill = JSONObject().put("stateSchema", JSONObject()).put("initialState", JSONObject())
                .put("ui", partContainer("column", emptyList())).put("source", STANDALONE_SOURCE).put("capabilities", JSONArray())
            return seal(layout, title, description, fill, recipe, observer)
        }

        fun complete(assembly: PartsAssembly, rawFill: String, observer: ((ToolUiDiagnostic) -> Unit)? = null): ToolSpec {
            requireParts(assembly.parts.isNotEmpty() && assembly.holes.isNotEmpty() && assembly.blockingQuestions.isEmpty(),
                "$.composition", "selected parts and unresolved fill positions")
            val fixed = restoreParts(assembly)
            val layout = Layout(fixed, assembly.fillPositions(), assembly.readPorts)
            return seal(layout, assembly.preview.title, assembly.preview.description, partsObject(rawFill, "$.fill"),
                assembly.preview.nativePresentation, observer)
        }

        fun refine(previous: ToolSpec, rawFill: String, observer: ((ToolUiDiagnostic) -> Unit)? = null): ToolSpec {
            val composition = previous.composition ?: throw PartsContractFailure("$.composition", "a mixed artifact")
            if (composition.isSealedStandalone) throw StandaloneRefinementUnavailable()
            return seal(composition.layout, previous.title, previous.description, partsObject(rawFill, "$.fill"),
                previous.nativePresentation, observer)
        }

        private fun restoreParts(assembly: PartsAssembly): List<FixedPart> = assembly.parts.map { placement ->
            val part = ToolParts.restore(placement.needId, placement.partId, placement.config, "$.parts.config")
            FixedPart(placement.position, placement.needId, part)
        }

        private fun seal(layout: Layout, title: String, description: String, fill: JSONObject, recipe: NativePresentation?,
            observer: ((ToolUiDiagnostic) -> Unit)?): ToolSpec {
            fill.requireKeys("$.fill", fillRequired, fillOptional)
            val schema = objectField(fill, "stateSchema", "$.fill")
            val initial = objectField(fill, "initialState", "$.fill")
            requireParts(ToolValueSchema.keys(schema).all(::isFillId), "$.fill.stateSchema", "fill-owned state fields")
            requireParts(ToolValueSchema.keys(initial) == ToolValueSchema.keys(schema),
                "$.fill.initialState", "exactly the fill state fields")
            layout.fixed.values.forEach { fixed ->
                fixed.part.fragment.schema.keys().forEach { key ->
                    schema.put(key, fixed.part.fragment.schema.get(key))
                    initial.put(key, fixed.part.fragment.initial.get(key))
                }
            }
            val ui = layout.mergeUi(objectField(fill, "ui", "$.fill"))
            if (!fill.has("title")) fill.put("title", title)
            if (!fill.has("description")) fill.put("description", description)
            fill.put("formatVersion", 3).put("composition", layout.manifest()).put("ui", ui)
            if (recipe != null) fill.put(NativePresentation.FIELD, recipe.json())
            return ToolSpec.parsePersisted(fill.toString(), observer)
        }

        /** Call only from the app-owned stored artifact parser. */
        fun read(definition: JSONObject, schema: JSONObject, initial: JSONObject, surfaceIds: Set<String>): ToolComposition {
            val root = "$.composition"
            val manifest = objectField(definition, "composition", "$")
            manifest.requireKeys(root, setOf("version", "parts", "fillPositions"), setOf("readPorts"))
            requireParts(manifest.opt("version") == VERSION, "$root.version", "composition version $VERSION")
            val fill = manifest.items("fillPositions", 0..MAX_POSITIONS, "$root.fillPositions").mapIndexed { index, item ->
                position(item, "$root.fillPositions[$index]")
            }
            val standalone = fill.isEmpty()
            val readPorts = if (manifest.has("readPorts")) {
                val names = manifest.items("readPorts", 1..MAX_POSITIONS, "$root.readPorts")
                    .map { planText(it, "$root.readPorts", 24) }
                requireParts(names == names.distinct().sorted(), "$root.readPorts", "ordered distinct IDs")
                names.toSet()
            } else emptySet()
            if (standalone) {
                requireParts(definition.opt("source") == STANDALONE_SOURCE, "$.source", "the fixed host no-op")
                requireParts(definition.optJSONArray("capabilities")?.length() == 0, "$.capabilities", "no capabilities")
                for (field in fillAuthority) {
                    requireParts(!definition.has(field), "$.$field", "no fill authority in a standalone composition")
                }
            }
            val parts = manifest.items("parts", 1..MAX_POSITIONS, "$root.parts").mapIndexed { index, row ->
                val path = "$root.parts[$index]"
                val entry = row as? JSONObject ?: throw PartsContractFailure(path, "a part object")
                entry.requireKeys(path, setOf("position", "needId", "partId", "instanceId", "config"))
                val needId = planText(entry.opt("needId"), "$path.needId", 24)
                requireParts(ToolValueSchema.name(needId), "$path.needId", "a valid identifier")
                requireParts(entry.opt("instanceId") == partInstanceId(needId), "$path.instanceId", "the canonical instance ID")
                val partId = planText(entry.opt("partId"), "$path.partId", 24)
                val config = objectField(entry, "config", path)
                val part = ToolParts.restore(needId, partId, config, "$path.config", allowNativeEdits = standalone)
                if (standalone) {
                    requireParts(sameToolJson(config, part.config) && part.fragment.projectionSource == null, "$path.config",
                        "a canonical host part configuration")
                }
                FixedPart(position(entry.opt("position"), "$path.position"), needId, part)
            }
            if (standalone) {
                requireParts(parts.any { it.projection != null || it.part.fragment.handlers.values.any { handler -> handler is PartHandler.Clocked } },
                    "$root.parts", "a host-owned part")
            }
            val positions = parts.map(FixedPart::position) + fill
            requireParts(parts.map(FixedPart::needId).toSet().size == parts.size, "$root.parts", "unique instances")
            requireParts(positions.size <= MAX_POSITIONS && positions.sorted() == positions.indices.toList() &&
                fill == fill.sorted() && parts.map(FixedPart::position) == parts.map(FixedPart::position).sorted(),
                root, "ordered, disjoint positions that cover the screen")
            val fragments = parts.map { it.part.fragment }
            val ids = fragments.map { PartFragmentIds(ToolValueSchema.keys(it.schema), it.handlers.keys) }
            PartsGeneration.requireUniqueIds(ids)
            val partFields = ids.flatMap(PartFragmentIds::state).toSet()
            val fillFields = ToolValueSchema.keys(schema) - partFields
            if (standalone) {
                requireParts(fillFields.isEmpty(), "$.stateSchema", "only canonical part state fields")
            } else {
                requireParts(fillFields.all(::isFillId), "$.stateSchema", "owned state fields")
            }
            fragments.forEach { fragment ->
                fragment.schema.keys().forEach { key ->
                    requireParts(sameToolJson(schema.opt(key), fragment.schema.get(key)), "$.stateSchema", "canonical part schemas")
                    requireParts(sameToolJson(initial.opt(key), fragment.initial.get(key)), "$.initialState", "canonical part defaults")
                }
            }
            requireParts(surfaceIds.all(::isFillId), "$.surfaces", "fill-owned surface IDs")
            val fillSchema = JSONObject().apply { fillFields.forEach { put(it, schema.get(it)) } }
            return ToolComposition(Layout(parts, fill, readPorts), fillSchema)
        }

        private fun position(value: Any?, path: String): Int {
            requireParts(value is Int && value in 0 until MAX_POSITIONS, path, "a valid screen position")
            return value as Int
        }

        private fun columnChildren(root: JSONObject, count: Int, path: String): List<JSONObject> {
            root.requireKeys(path, setOf("type", "children"))
            requireParts(root.opt("type") == "column", "$path.type", "a column")
            return root.items("children", count..count, "$path.children").mapIndexed { index, value ->
                value as? JSONObject ?: throw PartsContractFailure("$path.children[$index]", "a native UI object")
            }
        }

        private fun objectField(root: JSONObject, key: String, path: String): JSONObject =
            root.optJSONObject(key) ?: throw PartsContractFailure("$path.$key", "an object")

        private fun isFillId(id: String) = id.startsWith(FILL_PREFIX) && id.length > FILL_PREFIX.length && ToolValueSchema.name(id)

        private fun requireFillActions(node: JSONObject) {
            requireParts(!node.has("partId") && !node.has("partInstanceId"), "$.fill.ui", "fill-owned UI nodes")
            fun checkAction(raw: Any?) = requireParts(raw is String && isFillId(raw), "$.fill.ui", "fill-owned actions")
            for (key in listOf("action", "dismissAction", "swipeAction")) {
                if (node.has(key)) checkAction(node.opt(key))
            }
            node.optJSONObject("reorder")?.let { checkAction(it.opt("action")) }
            ToolUiCatalog.interactionActions(node).values.forEach(::checkAction)
            for (group in listOf("rowActions", "spans")) {
                node.optJSONArray(group)?.let { items ->
                    repeat(items.length()) { index ->
                        val item = items.getJSONObject(index)
                        if (item.has("action")) checkAction(item.opt("action"))
                    }
                }
            }
            node.optJSONObject("rowTemplate")?.let(::requireFillActions)
            node.optJSONArray("children")?.let { children ->
                repeat(children.length()) { requireFillActions(children.getJSONObject(it)) }
            }
        }

        private fun select(values: JSONObject, fields: JSONObject): JSONObject = JSONObject().apply {
            fields.keys().forEach { put(it, values.get(it)) }
        }
    }
}
