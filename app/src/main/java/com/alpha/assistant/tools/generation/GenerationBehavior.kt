package com.alpha.assistant.tools.generation

import com.alpha.assistant.cloud.ResponseShape
import com.alpha.assistant.tools.BehaviorFailureRule
import com.alpha.assistant.tools.BehaviorValueShape
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface GenerationBehavior {
    data class Unchecked(val reason: String) : GenerationBehavior

    data class Scenario(val slots: Map<String, Slot>, val steps: List<Step>) : GenerationBehavior

    enum class Kind(val wire: String) {
        ACTION("action"), SURFACE("surface"), TIMER("timer"), VALUE("value"), COLLECTION("collection"), GEOMETRY("geometry"),
        STATE("state"), AUDIO("audio");
        val observable get() = this in setOf(VALUE, COLLECTION, GEOMETRY, STATE, AUDIO)
    }
    data class Slot(val kind: Kind, val part: String?, val member: String?)

    sealed interface Step {
        data class Action(val slot: String, val value: Any?) : Step
        data class Surface(val slot: String, val event: String, val phase: String?, val x: Double?, val y: Double?, val direction: String?) : Step
        data class Advance(val slot: String, val count: Int, val observe: String?, val changes: Int) : Step
        data class Capture(val slot: String, val name: String) : Step
        data class Assert(val slot: String, val test: String, val value: Any?, val capture: String?) : Step
    }

    class BindingFailure(val slotId: String?, val slotKind: Kind?, val expected: String,
        val valueShape: BehaviorValueShape?) : IllegalArgumentException()

    sealed interface Binding {
        data class Id(val value: String) : Binding
        data class Ui(val path: List<Int>, val native: Boolean = false) : Binding
        data class Drawing(val surface: String, val path: List<Int>) : Binding
    }

    companion object {
        private const val FIELD = "$.behavior"
        private const val BINDINGS = "$.behaviorBindings"
        const val MAX_EVENTS = 64
        val workflowScopeGuidance = """
            The original request defines the feature set. Use the supplied context to clarify the request.
            Include only features the user requests or needs to complete the primary workflow.
            Choose a practical default when the workflow can proceed without asking the user to choose.
        """.trimIndent()
        private val scenarioLimits = """
            Use synthetic inputs and deterministic expectations. Do not depend on elapsed wall-clock time.
            Do not ask to execute an effect or use private values, arbitrary code, or values supplied only by external sources.
        """.trimIndent()

        val diagnosticGuidance = """
            Use behavior.status checkable and behavior.scenario with only slots and steps.
            Each slot has only kind. Use only offered action, surface, value, collection, and geometry targets.
            Use every slot. Use at most 16 steps. Start from the saved definition's initial state.
            An action is {"op":"action","slot":"actionSlot"}. Add a scalar value for an input and omit value for a button.
            To compare an observable result with its earlier value, use {"op":"capture","slot":"result","as":"before"}.
            Then use {"op":"assert","slot":"result","test":"changed|unchanged|equals","capture":"before"}.
            To test an explicit expected result, use equals with a typed scalar value instead of capture.
            Use nonempty only for visible text, records or drawing ink. Include an observation at or after the final action.
            Use actual controls and a valid input sequence. Stop before controls become unavailable.
            A value observes a visible ${behaviorScalarNodes.joinToString()}.
            A collection observes the record count of a visible ${behaviorCollectionNodes.joinToString()}.
            For a game, use surface and geometry when a drawing or its motion is the requested result.
            Surface steps send {"op":"surface","slot":"surface","event":"start|pause|resume|restart|viewport"},
            {"op":"surface","slot":"surface","event":"pointer","phase":"down|move|up|cancel","x":0.5,"y":0.5}, or
            {"op":"surface","slot":"surface","event":"control","direction":"left|right"}.
            Use {"op":"frames","slot":"surface","count":4,"observe":"geometry","changes":3} for motion or
            {"op":"timers","slot":"timer","count":1} for a timer. End with a check of the requested result.
            Use {"status":"unchecked","reason":"external_outcome"} only for a real external outcome.
            Use {"status":"unchecked","reason":"unsupported_observation"} only if these checks cannot express the result.
        """.trimIndent() + "\n" + scenarioLimits

        fun parse(raw: Any?, needIds: Set<String>, threeScene: Boolean = false): GenerationBehavior = try {
            parseBehavior(raw, needIds, threeScene)
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
                "a native behavior member" -> "slot_member"
                "a native observation member" -> "slot_member"
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

        private fun parseBehavior(raw: Any?, needIds: Set<String>, threeScene: Boolean): GenerationBehavior {
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
                    parseScenario(obj(behavior.opt("scenario"), FIELD), needIds, threeScene)
                }
                else -> throw PartsContractFailure(FIELD, "a checkable or explicitly unchecked behavior")
            }
        }

        private fun parseScenario(raw: JSONObject, needIds: Set<String>, threeScene: Boolean): Scenario {
            raw.requireKeys(FIELD, setOf("slots", "steps"))
            val slotObject = obj(raw.opt("slots"), FIELD)
            requireParts(slotObject.length() in 1..12, FIELD, "1 to 12 slots")
            val slots = ToolValueSchema.keys(slotObject).associateWith { id ->
                identifier(id, FIELD)
                val row = obj(slotObject.opt(id), FIELD)
                row.requireKeys(FIELD, setOf("kind"), setOf("part", "member"))
                val kind = Kind.entries.firstOrNull { it.wire == row.opt("kind") }
                    ?: throw PartsContractFailure(FIELD, "a supported slot kind")
                requireParts(if (threeScene) kind != Kind.TIMER else kind !in setOf(Kind.STATE, Kind.AUDIO),
                    FIELD, "a supported slot kind")
                val part = row.opt("part")?.let { identifier(it, FIELD) }
                val member = row.opt("member")?.let { identifier(it, FIELD) }
                requireParts(part == null || part in needIds && kind in setOf(Kind.ACTION, Kind.VALUE, Kind.COLLECTION), FIELD, "a compatible declared part")
                requireParts(member == null || part != null, FIELD, "a native behavior member")
                requireParts(part == null || kind == Kind.ACTION || member != null, FIELD, "a native observation member")
                Slot(kind, part, member)
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
                        val action = slot(Kind.ACTION)
                        Step.Action(action, row.opt("value")?.let { actionValue(it, slots.getValue(action), at) })
                    }
                    "surface" -> {
                        val event = row.opt("event") as? String
                        val extras = when (event) {
                            "pointer" -> setOf("phase", "x", "y")
                            "control" -> if (!threeScene) setOf("direction") else throw PartsContractFailure(at, "a supported surface event")
                            "viewport", "start", "pause", "resume", "restart" -> emptySet()
                            "reopen" -> if (threeScene) emptySet() else throw PartsContractFailure(at, "a supported surface event")
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
                        requireParts(!threeScene || frames, at, "a supported step")
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
                        requireParts(value == null || kind in setOf(Kind.VALUE, Kind.STATE) || kind == Kind.AUDIO && value == "completed" ||
                            kind == Kind.COLLECTION && value is Number, at, "a compatible expected value")
                        requireParts(test !in setOf("greater", "less") || kind !in setOf(Kind.GEOMETRY, Kind.AUDIO) && (value == null || value is Number), at, "a numeric comparison")
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
            val firstPointer = steps.indexOfFirst { it is Step.Surface && it.event == "pointer" }
            if (firstPointer >= 0) {
                val before = steps.take(firstPointer).filterIsInstance<Step.Capture>().map { it.name }.toSet()
                requireParts(steps.drop(firstPointer + 1).any { step ->
                    when (step) {
                        is Step.Assert -> step.value != null || step.test in setOf("changed", "greater", "less") && step.capture in before
                        is Step.Advance -> step.observe != null
                        else -> false
                    }
                }, FIELD, "an exact result or observed change after the pointer action")
            }
            return Scenario(slots, steps)
        }

        fun bindings(raw: Any?, slots: Map<String, Slot>): Map<String, Binding> {
            val objectValue = raw as? JSONObject
                ?: throw BindingFailure(null, null, "an object", BehaviorValueShape.of(raw))
            if (ToolValueSchema.keys(objectValue) != slots.keys)
                throw BindingFailure(null, null, "exactly the requested slot IDs", BehaviorValueShape.OBJECT)
            return slots.mapValues { (id, slot) ->
                val value = objectValue.opt(id)
                fun <T> read(input: Any?, format: String, parse: () -> T): T = try {
                    parse()
                } catch (failure: PartsContractFailure) {
                    throw BindingFailure(id, slot.kind, "$format (${failure.expected})", BehaviorValueShape.of(input))
                }
                when (slot.kind) {
                    Kind.ACTION -> if (value is JSONArray) read(value, "a UI index path") { Binding.Ui(indices(value, BINDINGS)) }
                        else read(value, "an action ID string or a UI index path") { Binding.Id(identifier(value, BINDINGS, 48)) }
                    Kind.SURFACE, Kind.TIMER, Kind.STATE, Kind.AUDIO -> read(value, "a ${slot.kind.wire} ID string") {
                        Binding.Id(identifier(value, BINDINGS, 48))
                    }
                    Kind.VALUE, Kind.COLLECTION -> read(value, "a UI index path") { Binding.Ui(indices(value, BINDINGS)) }
                    Kind.GEOMETRY -> {
                        val drawing = read(value, "a geometry object with surface and path") {
                            obj(value, BINDINGS).also { it.requireKeys(BINDINGS, setOf("surface", "path")) }
                        }
                        val surface = drawing.opt("surface")
                        val path = drawing.opt("path")
                        Binding.Drawing(
                            read(surface, "a geometry surface ID string") { identifier(surface, BINDINGS, 48) },
                            read(path, "a geometry path of drawing indices") { indices(path, BINDINGS) },
                        )
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
        private fun actionValue(raw: Any, slot: Slot, path: String): Any {
            if (raw !is JSONObject || slot.member == null) return scalar(raw, path)
            raw.requireKeys(path, setOf("key", "value"))
            return JSONObject().put("key", planText(raw.get("key"), path, 512))
                .put("value", scalar(raw.get("value"), path))
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

private val behaviorScalarNodes = setOf("text", "button", "metric", "badge", "fact", "timelineItem", "progress", "progressRing")
private val behaviorCollectionNodes = setOf("list", "table", "groupedList", "lazyList", "lazyGrid")

private val nativeInputObservationNodes = setOf("input", "numberInput", "slider", "toggle")

internal fun supportsBehaviorObservation(type: String, kind: GenerationBehavior.Kind, native: Boolean = false): Boolean = when (kind) {
    GenerationBehavior.Kind.COLLECTION -> type in behaviorCollectionNodes
    GenerationBehavior.Kind.VALUE -> type in behaviorScalarNodes || native && type in nativeInputObservationNodes
    else -> false
}

internal fun behaviorUiObservation(node: JSONObject, state: JSONObject, kind: GenerationBehavior.Kind, native: Boolean = false,
    rejected: ((BehaviorFailureRule, BehaviorValueShape?) -> Unit)? = null): Any? {
    val type = node.getString("type")
    if (!supportsBehaviorObservation(type, kind, native)) {
        rejected?.invoke(BehaviorFailureRule.OBSERVATION_KIND, null)
        return null
    }
    if (kind == GenerationBehavior.Kind.COLLECTION) return ToolUiCatalog.records(node, state).length()
    val field = node.optString("bind")
    val value = if (field.isEmpty()) node.opt("text") else ToolUiCatalog.value(state, field)
    return when (value) {
        is String, is Number, is Boolean -> value
        else -> {
            val shape = BehaviorValueShape.of(value)
            rejected?.invoke(if (value == null) BehaviorFailureRule.SCALAR_MISSING else BehaviorFailureRule.SCALAR_SHAPE, shape)
            null
        }
    }
}


internal fun GenerationBehavior.Scenario.framesSinceCapture(index: Int, assertion: GenerationBehavior.Step.Assert): Int? {
    val capture = assertion.capture ?: return null
    val start = steps.indexOfFirst { it is GenerationBehavior.Step.Capture && it.name == capture }
    if (start !in 0 until index) return null
    return steps.subList(start + 1, index).filterIsInstance<GenerationBehavior.Step.Advance>()
        .filter { slots.getValue(it.slot).kind == GenerationBehavior.Kind.SURFACE }.sumOf { it.count }.takeIf { it > 0 }
}

/** Executes the frozen workflow. Renderers supply input delivery and observable output. */
internal suspend fun GenerationBehavior.Scenario.execute(
    before: suspend (Int) -> Unit,
    read: suspend (String) -> Any,
    perform: suspend (GenerationBehavior.Step) -> Unit,
    assert: (Int, GenerationBehavior.Step.Assert, Any, Any?) -> Unit,
) {
    val captures = mutableMapOf<String, Any>()
    steps.forEachIndexed { index, step ->
        before(index)
        when (step) {
            is GenerationBehavior.Step.Capture -> captures[step.name] = read(step.slot)
            is GenerationBehavior.Step.Assert ->
                assert(index, step, read(step.slot), step.capture?.let(captures::getValue) ?: step.value)
            else -> perform(step)
        }
    }
}
