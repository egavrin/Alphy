package com.alpha.assistant.tools.generation

import com.alpha.assistant.cloud.ResponseShape
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface GenerationBehavior {
    data class Unchecked(val reason: String) : GenerationBehavior

    data class Scenario(val slots: Map<String, Slot>, val steps: List<Step>) : GenerationBehavior {
        fun writerGuidance(hostBindings: Map<String, Binding>): String {
            val requested = slots.filterKeys { it !in hostBindings }
            if (requested.isEmpty()) return ""
            val targets = JSONObject()
            requested.forEach { (id, slot) ->
                targets.put(id, slot.kind.wire)
            }
            val actionTypes = JSONObject()
            steps.filterIsInstance<Step.Action>().filter { it.slot in requested }.groupBy(Step.Action::slot)
                .forEach { (id, actions) ->
                    val types = actions.map { step ->
                        when (step.value) {
                            is Number -> "number"
                            is String -> "string"
                            is Boolean -> "boolean"
                            else -> "null"
                        }
                    }.distinct()
                    actionTypes.put(id, jsonArray(types))
                }
            return """
                Add behaviorBindings to the top-level response beside the existing fields. Its keys must be exactly the IDs below.
                An action binding uses the action ID string or a UI path to a control with its own action field.
                Surface and timer bindings use their actual ID strings.
                A UI path is an array of zero-based children indices from the final assembled UI root. An empty path selects the root.
                Use stable UI paths throughout the workflow. Value and collection bindings use UI paths to visible results.
                Each geometry binding is {"surface":"surface ID","path":[0]} where path indexes drawing commands, then group children.
                Use a stable shape or group. A path of [] observes all drawing commands for that surface.
                Keep bindings outside fill, ui, and source. Bindings describe existing targets, never tests or expected values.
                Slots: $targets
            """.trimIndent() + if (actionTypes.length() == 0) "" else
                "\nBind each action to a visible, enabled control that accepts its payload type. " +
                    "Use null for a button press. Honor the request's input range. Action payload types: $actionTypes"
        }
    }

    enum class Kind(val wire: String) {
        ACTION("action"), SURFACE("surface"), TIMER("timer"), VALUE("value"), COLLECTION("collection"), GEOMETRY("geometry");
        val observable get() = this == VALUE || this == COLLECTION || this == GEOMETRY
    }
    data class Slot(val kind: Kind, val part: String?, val member: String?, val path: List<Int>?)

    sealed interface Step {
        data class Action(val slot: String, val value: Any?) : Step
        data class Surface(val slot: String, val event: String, val phase: String?, val x: Double?, val y: Double?, val direction: String?) : Step
        data class Advance(val slot: String, val count: Int, val observe: String?, val changes: Int) : Step
        data class Capture(val slot: String, val name: String) : Step
        data class Assert(val slot: String, val test: String, val value: Any?, val capture: String?) : Step
    }

    sealed interface Binding {
        data class Id(val value: String) : Binding
        data class Ui(val path: List<Int>) : Binding
        data class Drawing(val surface: String, val path: List<Int>) : Binding
    }

    companion object {
        private const val FIELD = "$.behavior"
        private const val BINDINGS = "$.behaviorBindings"
        const val MAX_EVENTS = 64
        val newAppPlanGuidance = """
            The behavior field is part of the new mini-app plan. Include it beside wholeScreen or beside the full needs plan fields.
            Return either exactly wholeScreen and behavior, or the seven full plan fields plus behavior.
            For example, a whole-screen passive display plan is:
            {"wholeScreen":true,"behavior":{"status":"checkable","scenario":{"slots":{"result":{"kind":"value"}},"steps":[{"op":"assert","slot":"result","test":"nonempty"}]}}}
            For an interactive request, replace the example scenario with an action and a check of the requested result.
            If the full plan asks a blocking question, omit behavior until the user answers.
        """.trimIndent()
        private val scenarioGuidance = """
            Include a top-level "behavior" field. Plan one bounded executable check of the primary user-visible outcome.
            Use {"status":"checkable","scenario":{"slots":{...},"steps":[...]}}.
            Use short descriptive ASCII slot IDs such as amountInput, displayedTotal, or movingObject.
            Use 1 to 12 slots. Map each slot ID to {"kind":"action|surface|timer|value|collection|geometry"}.
            Use every declared slot in a step. End the interaction sequence with an assertion of its result.
            A value comes from visible text, metric, badge, fact, timeline item, or progress. Use the bound scalar when present.
            Collection assertions compare the number of records in a visible list, grid, or table.
            Use geometry for a drawing or part of a drawing. Do not equate a status change with motion.
            To edit a declared input, use {"kind":"action","part":"the input ID"}. Omit member for input edits.
            For a native catalog part, only action, value, and collection slots can use part with the declared need ID. A figure provides a value slot, never an input action.
            For a native part action, omit member for the primary action or give the local action ID. For a native value or collection, give the relative children path if known.
            A scene is written by the code writer even when the scene has a declared need ID. For every writer-owned slot, including surface, timer, and geometry slots, omit part, member, and path.
            With no path, the host selects the only suitable observation in that part, if there is exactly one.
            Begin from a fresh artifact after the host opens it, applies its viewport, and starts automatic playback.
            Use at most 16 steps and 59 explicit events. Use these step forms:
            Each step is one flat object with exactly the fields in its form. Do not add descriptions or expected results to interaction steps.
            {"op":"action","slot":"action slot","value":7} (omit value for a button; use a scalar for input)
            {"op":"surface","slot":"surface slot","event":"start|pause|resume|restart|stop|viewport"}
            {"op":"surface","slot":"surface slot","event":"pointer","phase":"down|move|up|cancel","x":0.5,"y":0.5}
            {"op":"surface","slot":"surface slot","event":"control","direction":"left|right"}
            A pointer step uses fractions from 0 to 1. Use 1 to 24 frames or timer events in each advance step.
            {"op":"frames","slot":"surface slot","count":4,"observe":"geometry slot","changes":3}
            {"op":"timers","slot":"timer slot","count":1}
            {"op":"capture","slot":"observation slot","as":"baseline"}
            {"op":"assert","slot":"observation slot","test":"equals|greater|less","value":14}
            {"op":"assert","slot":"observation slot","test":"equals|changed|unchanged","capture":"baseline"}
            {"op":"assert","slot":"observation slot","test":"nonempty"}
            In an assertion, use value or capture, never both. For nonempty, omit both value and capture.
            Keep assertions as separate steps. An action without an input value has only op and slot.
            Frames use positive elapsed time. Include observe and changes together to require at least two changes.
            At least one assertion must check the requested observable result. For passive UI, assert its requested content.
            Use {"status":"unchecked","reason":"external_outcome"} if the primary result depends on a native or external effect.
            Use {"status":"unchecked","reason":"unsupported_observation"} only when these steps cannot express the primary check.
        """.trimIndent()
        private val touchSceneExample = """
            Here is a complete example of the shape for a touch-driven scene. Use the request's language and behavior.
            {"title":"Touch surface","description":"Draw a visible mark after a touch.","language":"en","inputs":[],"constants":[],"needs":[{"id":"scene","type":"scene","label":"Touch surface","description":"Draw a mark where the user touches.","reads":[]}],"questions":[],"behavior":{"status":"checkable","scenario":{"slots":{"canvas":{"kind":"surface"},"drawing":{"kind":"geometry"}},"steps":[{"op":"surface","slot":"canvas","event":"pointer","phase":"down","x":0.5,"y":0.5},{"op":"surface","slot":"canvas","event":"pointer","phase":"up","x":0.5,"y":0.5},{"op":"assert","slot":"drawing","test":"nonempty"}]}}}
        """.trimIndent()
        private val scenarioLimits = """
            Use synthetic inputs and deterministic expectations. Do not depend on elapsed wall-clock time.
            Do not ask to execute an effect or use private values, arbitrary code, or values supplied only by external sources.
        """.trimIndent()

        val guidance = "$scenarioGuidance\n$scenarioLimits"
        val newAppBehaviorGuidance = "$scenarioGuidance\n$touchSceneExample\n$scenarioLimits"

        fun parse(raw: Any?, needIds: Set<String>): GenerationBehavior = try {
            parseBehavior(raw, needIds)
        } catch (failure: PartsContractFailure) {
            // Only host-owned rule codes enter traces, never candidate values or exception text.
            val rule = when (failure.expected) {
                "an object" -> "object"
                "the declared fields" -> "fields"
                "a supported unchecked reason" -> "unchecked_reason"
                "a checkable or explicitly unchecked behavior" -> "status"
                "1 to 12 slots" -> "slots_count"
                "a supported slot kind" -> "slot_kind"
                "a compatible declared part" -> "slot_part"
                "a part action member" -> "slot_member"
                "a part observation path" -> "slot_path"
                "a compatible declared slot" -> "step_slot"
                "an array" -> "steps_array"
                "1 to 16 items" -> "steps_count"
                "a supported surface event" -> "surface_event"
                "a supported pointer phase" -> "pointer_phase"
                "a supported direction" -> "direction"
                "a frame observation" -> "frame_observation"
                "a unique capture" -> "capture_unique"
                "a supported assertion" -> "assertion"
                "an earlier capture of the same kind" -> "capture_kind"
                "a compatible expected value" -> "expected_value"
                "a numeric comparison" -> "numeric_comparison"
                "a supported behavior step" -> "step"
                "only used slots and at most 64 events" -> "slots_or_events"
                "an observable assertion" -> "observable_assertion"
                "an assertion at or after the final interaction" -> "final_assertion"
                "a valid identifier" -> "identifier"
                "a normalized coordinate" -> "coordinate"
                "an integer in the supported range" -> "integer"
                "an index path", "at most 12 indices" -> "indices"
                "a scalar value" -> "scalar"
                else -> "value"
            }
            throw PartsContractFailure(failure.path, failure.expected,
                ResponseShape("behavior.$rule", failure.path, "unknown"))
        }

        private fun parseBehavior(raw: Any?, needIds: Set<String>): GenerationBehavior {
            val behavior = obj(raw, FIELD)
            return when (behavior.opt("status") as? String) {
                "unchecked" -> {
                    behavior.requireKeys(FIELD, setOf("status", "reason"))
                    val reason = behavior.opt("reason")
                    requireParts(reason in setOf("external_outcome", "unsupported_observation"), FIELD, "a supported unchecked reason")
                    Unchecked(reason as String)
                }
                "checkable" -> {
                    behavior.requireKeys(FIELD, setOf("status", "scenario"))
                    parseScenario(obj(behavior.opt("scenario"), FIELD), needIds)
                }
                else -> throw PartsContractFailure(FIELD, "a checkable or explicitly unchecked behavior")
            }
        }

        private fun parseScenario(raw: JSONObject, needIds: Set<String>): Scenario {
            raw.requireKeys(FIELD, setOf("slots", "steps"))
            val slotObject = obj(raw.opt("slots"), FIELD)
            requireParts(slotObject.length() in 1..12, FIELD, "1 to 12 slots")
            val slots = ToolValueSchema.keys(slotObject).associateWith { id ->
                identifier(id, FIELD)
                val row = obj(slotObject.opt(id), FIELD)
                row.requireKeys(FIELD, setOf("kind"), setOf("part", "member", "path"))
                val kind = Kind.entries.firstOrNull { it.wire == row.opt("kind") }
                    ?: throw PartsContractFailure(FIELD, "a supported slot kind")
                val part = row.opt("part")?.let { identifier(it, FIELD) }
                val member = row.opt("member")?.let { identifier(it, FIELD) }
                val path = row.opt("path")?.let { indices(it, FIELD) }
                requireParts(part == null || part in needIds && kind in setOf(Kind.ACTION, Kind.VALUE, Kind.COLLECTION), FIELD, "a compatible declared part")
                requireParts(member == null || part != null && kind == Kind.ACTION, FIELD, "a part action member")
                requireParts(path == null || part != null && kind in setOf(Kind.VALUE, Kind.COLLECTION), FIELD, "a part observation path")
                Slot(kind, part, member, path)
            }
            val captures = mutableMapOf<String, Kind>()
            val used = mutableSetOf<String>()
            // Reserve open and up to four startup events for the host.
            var events = 5
            fun use(row: JSONObject, field: String, kinds: Set<Kind>, at: String): String {
                val id = identifier(row.opt(field), at)
                requireParts(slots[id]?.kind in kinds, at, "a compatible declared slot")
                used += id
                return id
            }
            val observations = Kind.entries.filter { it.observable }.toSet()
            val steps = raw.items("steps", 1..16, FIELD).mapIndexed { index, item ->
                val at = "$FIELD.scenario.steps[$index]"
                val row = obj(item, at)
                fun slot(vararg kinds: Kind) = use(row, "slot", kinds.toSet(), at)
                when (row.opt("op") as? String) {
                    "action" -> {
                        row.requireKeys(at, setOf("op", "slot"), setOf("value"))
                        events++
                        Step.Action(slot(Kind.ACTION), row.opt("value")?.let { scalar(it, at) })
                    }
                    "surface" -> {
                        val event = row.opt("event") as? String
                        val extras = when (event) {
                            "pointer" -> setOf("phase", "x", "y")
                            "control" -> setOf("direction")
                            "viewport", "start", "pause", "resume", "restart", "stop" -> emptySet()
                            else -> throw PartsContractFailure(at, "a supported surface event")
                        }
                        row.requireKeys(at, setOf("op", "slot", "event") + extras)
                        val phase = row.opt("phase")
                        val direction = row.opt("direction")
                        requireParts(event != "pointer" || phase in setOf("down", "move", "up", "cancel"), at, "a supported pointer phase")
                        requireParts(event != "control" || direction in setOf("left", "right"), at, "a supported direction")
                        events++
                        Step.Surface(slot(Kind.SURFACE), checkNotNull(event), phase as? String,
                            row.opt("x")?.let { fraction(it, at) }, row.opt("y")?.let { fraction(it, at) }, direction as? String)
                    }
                    "frames", "timers" -> {
                        val frames = row.opt("op") == "frames"
                        val withObservation = row.has("observe") || row.has("changes")
                        requireParts(!withObservation || frames, at, "a frame observation")
                        val extra = if (withObservation) setOf("observe", "changes") else emptySet()
                        row.requireKeys(at, setOf("op", "slot", "count") + extra)
                        val count = integer(row.opt("count"), 1..24, at)
                        val observe = if (withObservation) use(row, "observe", setOf(Kind.GEOMETRY), at) else null
                        val changes = if (withObservation) integer(row.opt("changes"), 2..count, at) else 0
                        events += count
                        Step.Advance(slot(if (frames) Kind.SURFACE else Kind.TIMER), count, observe, changes)
                    }
                    "capture" -> {
                        row.requireKeys(at, setOf("op", "slot", "as"))
                        val source = use(row, "slot", observations, at)
                        val name = identifier(row.opt("as"), at)
                        requireParts(captures.put(name, slots.getValue(source).kind) == null, at, "a unique capture")
                        Step.Capture(source, name)
                    }
                    "assert" -> {
                        val source = use(row, "slot", observations, at)
                        val kind = slots.getValue(source).kind
                        val test = row.opt("test") as? String
                        val operand = when (test) {
                            "nonempty" -> emptySet()
                            "equals", "greater", "less" -> setOf(if (row.has("capture")) "capture" else "value")
                            "changed", "unchanged" -> setOf("capture")
                            else -> throw PartsContractFailure(at, "a supported assertion")
                        }
                        row.requireKeys(at, setOf("op", "slot", "test") + operand)
                        val value = row.opt("value")?.let { scalar(it, at) }
                        val capture = row.opt("capture")?.let { identifier(it, at) }
                        requireParts(capture == null || captures[capture] == kind, at, "an earlier capture of the same kind")
                        requireParts(value == null || kind == Kind.VALUE || kind == Kind.COLLECTION && value is Number, at, "a compatible expected value")
                        requireParts(test !in setOf("greater", "less") || kind != Kind.GEOMETRY && (value == null || value is Number), at, "a numeric comparison")
                        Step.Assert(source, checkNotNull(test), value, capture)
                    }
                    else -> throw PartsContractFailure(at, "a supported behavior step")
                }
            }
            requireParts(used == slots.keys && events <= MAX_EVENTS, FIELD, "only used slots and at most 64 events")
            requireParts(steps.any { it is Step.Assert || it is Step.Advance && it.observe != null }, FIELD, "an observable assertion")
            val finalInteraction = steps.indexOfLast { it is Step.Action || it is Step.Surface || it is Step.Advance }
            requireParts(steps.withIndex().any { (index, step) ->
                index >= finalInteraction && (step is Step.Assert || step is Step.Advance && step.observe != null)
            }, FIELD, "an assertion at or after the final interaction")
            return Scenario(slots, steps)
        }

        fun bindings(raw: Any?, slots: Map<String, Slot>): Map<String, Binding> {
            val objectValue = obj(raw, BINDINGS)
            requireParts(ToolValueSchema.keys(objectValue) == slots.keys, BINDINGS, "exactly the requested slot IDs")
            return slots.mapValues { (id, slot) ->
                val value = objectValue.opt(id)
                when (slot.kind) {
                    Kind.ACTION -> if (value is JSONArray) Binding.Ui(indices(value, BINDINGS))
                        else Binding.Id(identifier(value, BINDINGS, 48))
                    Kind.SURFACE, Kind.TIMER -> Binding.Id(identifier(value, BINDINGS, 48))
                    Kind.VALUE, Kind.COLLECTION -> Binding.Ui(indices(value, BINDINGS))
                    Kind.GEOMETRY -> {
                        val drawing = obj(value, BINDINGS)
                        drawing.requireKeys(BINDINGS, setOf("surface", "path"))
                        Binding.Drawing(identifier(drawing.opt("surface"), BINDINGS, 48), indices(drawing.opt("path"), BINDINGS))
                    }
                }
            }
        }

        private fun obj(raw: Any?, path: String) = raw as? JSONObject ?: throw PartsContractFailure(path, "an object")
        private fun indices(raw: Any?, path: String): List<Int> {
            val array = raw as? JSONArray ?: throw PartsContractFailure(path, "an index path")
            requireParts(array.length() <= 12, path, "at most 12 indices")
            return List(array.length()) { integer(array.opt(it), 0..511, path) }
        }
        private fun integer(raw: Any?, range: IntRange, path: String): Int {
            requireParts(raw is Int && raw in range, path, "an integer in the supported range")
            return raw as Int
        }
        private fun fraction(raw: Any?, path: String): Double = planNumber(raw, path).also {
            requireParts(it in 0.0..1.0, path, "a normalized coordinate")
        }
        private fun identifier(raw: Any?, path: String, limit: Int = 24): String = planText(raw, path, limit).also {
            requireParts(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")), path, "a valid identifier")
        }
        private fun scalar(raw: Any?, path: String): Any = when (raw) {
            is String -> planText(raw, path, 512, allowEmpty = true)
            is Boolean -> raw
            JSONObject.NULL -> JSONObject.NULL
            is Number -> raw.also { planNumber(it, path) }
            else -> throw PartsContractFailure(path, "a scalar value")
        }
    }
}

/** Null means a selected part could not expose a requested slot. Unselected needs stay with the writer. */
internal fun PartsAssembly.behaviorBindings(scenario: GenerationBehavior.Scenario): Map<String, GenerationBehavior.Binding>? {
    val result = mutableMapOf<String, GenerationBehavior.Binding>()
    val state = JSONObject(preview.initialState)
    for ((id, slot) in scenario.slots) {
        val part = parts.find { it.needId == slot.part } ?: continue
        val binding = when (slot.kind) {
            GenerationBehavior.Kind.ACTION -> {
                val action = slot.member?.let { "${part.instanceId}_$it".takeIf(part.ids.actions::contains) }
                    ?: (part.behaviorActionId ?: part.primaryActionId).takeIf { slot.member == null }
                action?.let(GenerationBehavior.Binding::Id)
            }
            GenerationBehavior.Kind.VALUE, GenerationBehavior.Kind.COLLECTION -> {
                fun matches(node: JSONObject) = behaviorUiObservation(node, state, slot.kind) != null
                fun observations(node: JSONObject, path: List<Int>): List<List<Int>> {
                    if (matches(node)) return listOf(path)
                    val children = node.optJSONArray("children") ?: return emptyList()
                    return (0 until children.length()).flatMap { observations(children.getJSONObject(it), path + it) }
                }
                val requestedPath = slot.path ?: part.valuePath
                val path = requestedPath?.takeIf { indices ->
                    var node = part.full
                    for (index in indices) {
                        node = node.optJSONArray("children")?.optJSONObject(index) ?: return@takeIf false
                    }
                    matches(node)
                } ?: if (requestedPath == null) observations(part.full, emptyList()).singleOrNull() else null
                path?.let { GenerationBehavior.Binding.Ui(listOf(part.position) + it) }
            }
            else -> null
        }
        result[id] = binding ?: return null
    }
    return result
}

private val behaviorScalarNodes = setOf("text", "metric", "badge", "fact", "timelineItem", "progress", "progressRing")
private val behaviorCollectionNodes = setOf("list", "table", "groupedList", "lazyList", "lazyGrid")

internal fun behaviorUiObservation(node: JSONObject, state: JSONObject, kind: GenerationBehavior.Kind): Any? {
    val type = node.getString("type")
    if (kind == GenerationBehavior.Kind.COLLECTION && type in behaviorCollectionNodes)
        return ToolUiCatalog.records(node, state).length()
    if (kind != GenerationBehavior.Kind.VALUE || type !in behaviorScalarNodes) return null
    val field = node.optString("bind")
    val value = if (field.isEmpty()) node.opt("text") else ToolUiCatalog.value(state, field)
    return when (value) {
        is String, is Number, is Boolean -> value
        else -> null
    }
}
