package com.alpha.assistant.tools.generation

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.courses.EXERCISE_FIELD
import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject
import com.alpha.assistant.tools.generation.NativeObservation.*
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

internal data class PartFragmentIds(val state: Set<String>, val actions: Set<String>)

internal sealed interface PartHandler {
    data class Script(val source: String, val transition: (JSONObject, Any?) -> Unit) : PartHandler
    data class Clocked(val transition: (JSONObject, Any?, PartClockSample) -> Unit) : PartHandler
}

internal data class PartReadProjection(
    val fields: Set<String>,
    val reads: Set<String> = emptySet(),
    val render: (JSONObject, PartClockSample, Map<String, Double?>) -> JSONObject,
)

internal data class PartNumberOutput(val field: String, val available: String? = null,
    val records: Boolean = false, val member: String? = null) {
    fun read(state: JSONObject): Double? {
        if (available != null && !state.getBoolean(available)) return null
        if (records) {
            val rows = state.getJSONArray(field)
            return if (member == null) rows.length().toDouble() else (0 until rows.length()).sumOf { rows.getJSONObject(it).getDouble(member) }
        }
        return (state.opt(field) as? Number)?.toDouble()
    }
}

internal data class PartFragment(
    val full: JSONObject,
    val compact: JSONObject,
    val schema: JSONObject,
    val initial: JSONObject,
    val handlers: Map<String, PartHandler>,
    val projectionSource: String? = null,
    val readProjection: PartReadProjection? = null,
)

internal data class PartCandidate(
    val id: String,
    val section: String,
    val description: String,
    val config: JSONObject,
    val fragment: PartFragment,
    val primaryActionId: String? = null,
    val secondaryActionIds: Set<String> = emptySet(),
    val numberOutput: PartNumberOutput? = null,
    val observationPaths: Map<NativeObservation, List<Int>> = emptyMap(),
    val behaviorActionId: String? = null,
    val aggregates: Map<String, PartNumberOutput> = emptyMap(),
    val clockTicks: ((JSONObject, PartClockSample) -> Boolean)? = null,
)

internal data class PartRequest(
    val id: String,
    val label: String,
    val description: String,
    val type: NeedType?,
    val candidates: List<PartCandidate>,
    val slotFailure: PartsContractFailure? = null
)

internal fun partInstanceId(id: String) = "p${id.length}_$id"

internal fun dueSlotId(slot: Int) = "due${slot + 1}"

internal fun initialDueSlot(slot: Int, name: String, date: String, repeatDays: Int): JSONObject =
    JSONObject().put("id", dueSlotId(slot)).put("name", name).put("date", date)
        .put("repeatDays", repeatDays).put("completed", false).put("occurrence", 0)

internal fun dueDate(from: LocalDate, days: Int, path: String): LocalDate = try {
    from.plusDays(days.toLong())
} catch (_: DateTimeException) {
    throw PartsContractFailure(path, "a date in the supported calendar")
}

internal class FragmentBuilder(id: String) {
    val instance = partInstanceId(id)
    private val schema = JSONObject()
    private val initial = JSONObject()
    private val handlers = linkedMapOf<String, PartHandler>()

    fun state(name: String, type: Any, value: Any): String {
        val key = "${instance}_$name"
        check(!schema.has(key)) { "Part state names must be unique." }
        schema.put(key, type)
        initial.put(key, value)
        return key
    }

    fun exerciseDefinition(value: String) {
        schema.put(EXERCISE_FIELD, "string")
        initial.put(EXERCISE_FIELD, value)
    }

    private fun action(name: String, handler: PartHandler): String {
        val key = "${instance}_$name"
        check(handlers.put(key, handler) == null) { "Part action names must be unique." }
        return key
    }

    fun action(name: String, code: String, transition: (JSONObject, Any?) -> Unit): String =
        action(name, PartHandler.Script(code, transition))

    fun clockedAction(name: String, transition: (JSONObject, Any?, PartClockSample) -> Unit): String =
        action(name, PartHandler.Clocked(transition))

    fun projectedAction(name: String, code: String): String = action(name, code) { _, _ ->
        error("This action requires the complete parts runtime.")
    }

    fun finish(full: JSONObject, compact: JSONObject = full, projectionSource: String? = null,
        readProjection: PartReadProjection? = null): PartFragment =
        PartFragment(full, compact, schema, initial, handlers, projectionSource, readProjection)
}

internal fun partNode(type: String, text: String? = null): JSONObject =
    JSONObject().put("type", type).apply { if (text != null) put("text", text) }

internal fun partContainer(type: String, children: Iterable<JSONObject>): JSONObject =
    partNode(type).put("children", jsonArray(children))

private fun control(type: String, text: String, action: String, binding: String? = null): JSONObject =
    partNode(type, text).put("action", action).apply { if (binding != null) put("bind", binding) }

private fun integer(value: Any?, range: IntRange, path: String): Int {
    val number = (value as? Number)?.toDouble()
    requireParts(number != null && number >= range.first && number <= range.last && number == floor(number),
        path, "an integer from ${range.first} to ${range.last}")
    return checkNotNull(number).toInt()
}

private class Slots(need: PlanNeed, private val constants: Map<String, PlanValue>, val path: String) {
    private val remaining = need.uses.toMutableMap()

    private fun read(name: String): PlanValue? {
        val id = remaining.remove(name) ?: return null
        return constants[id] ?: throw PartsContractFailure("$path.$name", "a constant for this part")
    }

    fun text(name: String, max: Int, default: String? = null): String =
        planText((read(name) ?: default?.let { PlanValue.Text(it) })?.raw, "$path.$name", max)

    fun number(name: String, range: IntRange, default: Int? = null): Int =
        integer(read(name)?.raw ?: default, range, "$path.$name")

    fun numeric(name: String): Double = planNumber(read(name)?.raw, "$path.$name")

    fun pictogram(): String? = read("pictogram")?.let { lessonPictogram(it.raw, "$path.pictogram") }

    fun has(name: String) = name in remaining

    fun finish() {
        requireParts(remaining.isEmpty(), path, "references supported by the part")
    }
}

private fun lessonPictogram(value: Any?, path: String): String =
    planText(value, path, 3).also {
        requireParts(UCharacter.hasBinaryProperty(it, UProperty.BASIC_EMOJI), path, "one basic emoji")
    }

internal object ToolParts {
    private val counterInitial = -1_000_000..1_000_000
    private val counterStep = 1..1_000_000

    val sectionDescriptions = mapOf(
        "controls" to "Editable inputs, counters, native clocks, and computed numeric outputs with declared dependencies.",
        "records" to "Local notes, one-tap logs, points ledgers, and readings with history.",
        "plans" to "Local itineraries, due dates, and idea decks.",
        "lesson" to "Native lesson text, picture cards with a color pictogram and caption, and exercises.",
        "course" to "A passive exercise graded by the course player."
    )

    fun requests(plan: NeedsPlan, lessonTarget: CourseSlideKind? = null, clock: PartClockSample? = null): List<PartRequest> = plan.inputs.map { input ->
        val candidate = inputPart(input)
        PartRequest(input.id, input.label, "An independent editable ${candidate.id} value.", null, listOf(candidate))
    } + plan.needs.mapIndexed { index, need ->
        val slots = Slots(need, plan.constants, "$.needs[$index].uses")
        try {
            val builder = FragmentBuilder(need.id)
            val part = if (lessonTarget != null) {
                if (need.type in passiveLessonNeeds) content(builder, need.label, slots.text("body", 300), slots.pictogram())
                else lessonExercise(builder, need.label, lessonTarget, slots)
            } else when (need.type) {
                NeedType.COUNTER -> counter(builder, need.label, slots.number("initial", counterInitial, 0),
                    slots.number("step", counterStep, 1), plan.russian)
                NeedType.COUNTDOWN -> ClockParts.build(need.id, need.label, slots.number("length", 1..86400), plan.russian)
                NeedType.STOPWATCH -> ClockParts.build(need.id, need.label, null, plan.russian)
                NeedType.FIGURE -> need.figure?.let { figure ->
                    val constants = JSONObject()
                    for (id in figure.expression.references) {
                        (plan.constants[id] as? PlanValue.Numeric)?.let { constants.put(id, it.raw) }
                    }
                    figure(builder, need.label, figure.expression, constants, figure.unit, plan.russian)
                }
                NeedType.ENTRIES -> if (need.recordFields.isEmpty()) entries(builder, need.label, plan.russian)
                    else RecordParts.build(need.id, need.label, need.recordFields, plan.russian)
                NeedType.QUICK_LOG -> quickLog(builder, need.label, slots, plan.russian)
                NeedType.POINTS_LEDGER -> pointsLedgerFromSlots(builder, need.label, slots, plan.russian)
                NeedType.ITINERARY -> itinerary(builder, need.label, need.itineraryStops,
                    slots.number("start", 0..1439), slots.number("gap", itineraryGap, 0), plan.russian)
                NeedType.EDITABLE_ITINERARY -> editableItinerary(builder, need.label,
                    slots.number("duration", itineraryDuration),
                    if (slots.has("start")) slots.number("start", 0..1439) else null, plan.russian)
                NeedType.READING_STATUS -> readingStatus(builder, need.label, need.readingBands,
                    if (slots.has("unit")) slots.text("unit", 100) else "",
                    if (slots.has("initial")) slots.numeric("initial") else null, plan.russian)
                NeedType.DUE_ITEMS -> {
                    val sample = clock ?: throw PartsContractFailure(slots.path, "a host clock sample")
                    val items = need.dueItems.map { item ->
                        if (item.date != null) item else item.copy(
                            date = dueDate(sample.localDate, checkNotNull(item.offsetDays), slots.path), offsetDays = null)
                    }
                    dueItems(builder, need.label, items, slots.number("window", 0..PLAN_NUMBER_LIMIT), plan.russian)
                }
                NeedType.IDEA_DECK -> {
                    requireParts(clock != null, slots.path, "a host clock sample")
                    ideaDeck(builder, need.label, need.deckIdeas, plan.russian)
                }
                NeedType.CONTENT, NeedType.EXPLANATION, NeedType.EXAMPLE, NeedType.SUMMARY ->
                    content(builder, need.label, slots.text("body", 300))
                NeedType.CHOICE_EXERCISE -> {
                    val choices = (1..4).map { "option$it" }
                    val optionCount = choices.count(slots::has)
                    requireParts(optionCount in 2..4, slots.path, "two to four consecutive exercise options")
                    val options = choices.take(optionCount).map { slots.text(it, 100) }
                    requireParts(options.distinct().size == options.size, slots.path, "distinct exercise options")
                    exercise(builder, need.label, options, slots.number("correct", 1..optionCount), plan.russian)
                }
                else -> null
            }
            if (part != null) slots.finish()
            PartRequest(need.id, need.label, need.description, need.type, listOfNotNull(part))
        } catch (failure: PartsContractFailure) {
            PartRequest(need.id, need.label, need.description, need.type, emptyList(), failure)
        }
    }

    fun restore(needId: String, partId: String, config: JSONObject, path: String, allowNativeEdits: Boolean = false): PartCandidate {
        val label = planText(config.opt("label"), "$path.label", 100)
        fun requireConfig(vararg fields: String) = config.requireKeys(path, setOf("label", *fields))
        fun requireEditableConfig(vararg fields: String) = config.requireKeys(path, setOf("label", *fields),
            if (allowNativeEdits) setOf(NativePartEditMetadata.FIELD) else emptySet())
        fun russian(): Boolean = config.opt("russian") as? Boolean
            ?: throw PartsContractFailure("$path.russian", "a boolean")
        val builder = FragmentBuilder(needId)
        return when (partId) {
            "string_input", "number_input", "boolean_input" -> {
                config.requireKeys(path, setOf("label", "initial"), NumberControl.fields)
                val initial = PlanValue.parse(config.opt("initial"), "$path.initial")
                val part = inputPart(PlanInput(needId, label, initial, NumberControl.read(config, initial, path)))
                requireParts(part.id == partId, "$path.initial", "the part input type")
                part
            }
            "numeric_figure" -> {
                requireConfig("expression", "constants", "unit", "russian")
                val expression = NumberExpression.parse(config.opt("expression"), "$path.expression")
                val constants = config.optJSONObject("constants") ?: throw PartsContractFailure(path, "numeric constants")
                figure(builder, label, expression, constants, planText(config.opt("unit"), "$path.unit", 20, true), russian())
            }
            "counter" -> {
                requireConfig("initial", "step", "russian")
                counter(builder, label, integer(config.opt("initial"), counterInitial, "$path.initial"),
                    integer(config.opt("step"), counterStep, "$path.step"), russian())
            }
            "countdown", "stopwatch" -> {
                requireConfig("seconds", "russian")
                val seconds = integer(config.opt("seconds"), 0..86400, "$path.seconds")
                requireParts((partId == "stopwatch") == (seconds == 0), "$path.seconds", "the declared clock mode")
                ClockParts.build(needId, label, seconds.takeIf { it != 0 }, russian())
            }
            "notes" -> {
                requireConfig("russian")
                entries(builder, label, russian())
            }
            "record_log" -> {
                requireConfig("fields", "russian")
                RecordParts.build(needId, label, PlanRecordField.read(config, path), russian())
            }
            "points_ledger" -> pointsLedger(builder, label, config, path)
            "itinerary" -> {
                requireConfig("russian", "start", "gap", "stops")
                itinerary(builder, label, config.readItineraryStops(path),
                    integer(config.opt("start"), 0..1439, "$path.start"),
                    integer(config.opt("gap"), itineraryGap, "$path.gap"), russian())
            }
            "editable_itinerary" -> {
                config.requireKeys(path, setOf("label", "russian", "duration"), setOf("start"))
                editableItinerary(builder, label, integer(config.opt("duration"), itineraryDuration, "$path.duration"),
                    if (config.has("start")) integer(config.opt("start"), 0..1439, "$path.start") else null, russian())
            }
            "reading_status" -> {
                config.requireKeys(path, setOf("label", "russian", "bands", "unit"), setOf("initial"))
                readingStatus(builder, label, config.readReadingBands(path),
                    planText(config.opt("unit"), "$path.unit", 100, allowEmpty = true),
                    if (config.has("initial")) planNumber(config.opt("initial"), "$path.initial") else null, russian())
            }
            "due_items" -> {
                requireEditableConfig("russian", "window", "items")
                val items = config.readDueItems(path, allowOffsets = false)
                dueItems(builder, label, items, integer(config.opt("window"), 0..PLAN_NUMBER_LIMIT, "$path.window"),
                    russian(), NativePartEditMetadata.read(config, items.size, path))
            }
            "idea_deck" -> {
                requireEditableConfig("russian", "ideas")
                val ideas = config.readDeckIdeas(path)
                ideaDeck(builder, label, ideas, russian(), NativePartEditMetadata.read(config, ideas.size, path))
            }
            "information" -> {
                config.requireKeys(path, setOf("label", "body"), setOf("pictogram"))
                val pictogram = if (config.has("pictogram")) lessonPictogram(config.opt("pictogram"), "$path.pictogram") else null
                content(builder, label, planText(config.opt("body"), "$path.body", 300), pictogram)
            }
            "choice_exercise" -> {
                requireConfig("options", "correct", "russian")
                val options = config.items("options", 2..4, "$path.options").mapIndexed { index, item ->
                    planText(item, "$path.options[$index]", 100)
                }
                requireParts(options.distinct().size == options.size, "$path.options", "distinct exercise options")
                exercise(builder, label, options, integer(config.opt("correct"), 1..options.size, "$path.correct"), russian())
            }
            "course_choice", "course_fill_in", "course_order" -> courseExercise(builder, label, partId, config, path)
            else -> throw PartsContractFailure(path, "configuration for a known part")
        }
    }

    private fun lessonExercise(builder: FragmentBuilder, label: String, target: CourseSlideKind, slots: Slots): PartCandidate {
        fun consecutive(prefix: String, range: IntRange, maxLength: Int): List<String> {
            val count = (1..range.last).count { slots.has("$prefix$it") }
            requireParts(count in range, slots.path, "${range.first} to ${range.last} consecutive $prefix values")
            return (1..count).map { slots.text("$prefix$it", maxLength) }
        }
        val config = JSONObject().put("label", label)
        val partId = when (target) {
            CourseSlideKind.CHOICE -> {
                val options = consecutive("option", 2..4, 100)
                config.put("options", jsonArray(options)).put("correct", slots.number("correct", 1..options.size))
                "course_choice"
            }
            CourseSlideKind.FILL_IN -> {
                config.put("accepted", jsonArray(consecutive("accepted", 1..4, 500)))
                "course_fill_in"
            }
            CourseSlideKind.ORDER -> {
                val options = consecutive("option", 2..4, 100)
                val order = options.indices.map { slots.number("position${it + 1}", 1..options.size) }
                config.put("options", jsonArray(options)).put("order", jsonArray(order))
                "course_order"
            }
            else -> throw PartsContractFailure(slots.path, "a course exercise")
        }
        return courseExercise(builder, label, partId, config, slots.path)
    }

    private fun courseExercise(builder: FragmentBuilder, label: String, partId: String, config: JSONObject, path: String): PartCandidate {
        val canonical = JSONObject().put("label", label)
        val definition = JSONObject().put("question", label)
        fun textList(name: String, bounds: IntRange, maxLength: Int): List<String> {
            val values = config.items(name, bounds, "$path.$name").mapIndexed { index, value ->
                planText(value, "$path.$name[$index]", maxLength).trim()
            }
            requireParts(values.distinct().size == values.size, "$path.$name", "distinct text values")
            canonical.put(name, jsonArray(values))
            return values
        }
        fun options(): List<String> {
            val labels = textList("options", 2..4, 100)
            val ids = labels.indices.map { "${builder.instance}_option${it + 1}" }
            definition.put("options", jsonArray(ids.zip(labels).map { (id, text) ->
                JSONObject().put("id", id).put("label", text)
            }))
            return ids
        }
        val kind = when (partId) {
            "course_choice" -> {
                config.requireKeys(path, setOf("label", "options", "correct"))
                val ids = options()
                val correct = integer(config.opt("correct"), 1..ids.size, "$path.correct")
                canonical.put("correct", correct)
                definition.put("correct", ids[correct - 1])
                CourseSlideKind.CHOICE
            }
            "course_fill_in" -> {
                config.requireKeys(path, setOf("label", "accepted"))
                definition.put("accepted", jsonArray(textList("accepted", 1..4, 500)))
                CourseSlideKind.FILL_IN
            }
            "course_order" -> {
                config.requireKeys(path, setOf("label", "options", "order"))
                val ids = options()
                val positions = config.items("order", ids.size..ids.size, "$path.order").mapIndexed { index, item ->
                    integer(item, 1..ids.size, "$path.order[$index]")
                }
                requireParts(positions.distinct().size == ids.size, "$path.order", "a permutation of all options")
                canonical.put("order", jsonArray(positions))
                definition.put("correctOrder", jsonArray(positions.map { ids[it - 1] }))
                CourseSlideKind.ORDER
            }
            else -> error("Unknown course part.")
        }
        val metadata = definition.put("kind", kind.name).toString()
        requireParts(metadata.length <= 2048, path, "exercise metadata within 2048 characters")
        builder.exerciseDefinition(metadata)
        // The player renders the question and controls, so the part contributes only a divider.
        return PartCandidate(partId, "course", "Provide one ${kind.name} exercise for the course player.",
            canonical, builder.finish(partNode("divider")))
    }

    private fun inputPart(input: PlanInput): PartCandidate {
        val builder = FragmentBuilder(input.id)
        val (type, schema) = when (input.initial) {
            is PlanValue.Text -> "input" to "string"
            is PlanValue.Numeric -> (if (input.control?.slider == true) "slider" else "numberInput") to "number"
            is PlanValue.Flag -> "toggle" to "boolean"
        }
        val value = builder.state("value", schema, input.initial.raw)
        val edit = builder.action("edit", "state.$value = event.value;") { state, payload ->
            state.put(value, checkNotNull(payload))
        }
        val full = control(type, input.label, edit, value)
        if (type == "input") full.put("maxLength", 500)
        val config = JSONObject().put("label", input.label).put("initial", input.initial.raw)
        input.control?.let { bounds ->
            bounds.min?.let { full.put("min", it) }
            bounds.max?.let { full.put("max", it) }
            bounds.step?.let { full.put("step", it) }
            val encoded = bounds.json()
            encoded.keys().forEach { config.put(it, encoded.get(it)) }
        }
        val compact = when (input.initial) {
            is PlanValue.Text -> partNode("text", input.label)
            is PlanValue.Numeric -> partNode("metric", input.label).put("bind", value)
            is PlanValue.Flag -> full
        }
        return PartCandidate(schema + "_input", "controls", "Edit one ${input.label} value and keep it locally.",
            config, builder.finish(full, compact),
            numberOutput = if (input.initial is PlanValue.Numeric) PartNumberOutput(value) else null,
            observationPaths = mapOf(VALUE to emptyList()), behaviorActionId = edit)
    }

    private fun figure(builder: FragmentBuilder, label: String, expression: NumberExpression,
        constants: JSONObject, unit: String, russian: Boolean): PartCandidate {
        val numbers = constants.keys().asSequence().associateWith { id ->
            requireParts(id in expression.references, "$.figure.constants", "referenced numeric constants")
            planNumber(constants.opt(id), "$.figure.constants")
        }
        val value = builder.state("value", "number", 0)
        val available = builder.state("available", "boolean", false)
        val invalid = builder.state("invalid", "boolean", true)
        val title = if (unit.isEmpty()) label else "$label ($unit)"
        val metric = partNode("metric", title).put("bind", value).put("visibleBind", available)
        val feedback = if (russian) "$title: проверьте введённые значения" else "$title: check the input values"
        val ui = partContainer("column", listOf(metric, partNode("text", feedback).put("visibleBind", invalid)))
        val projection = PartReadProjection(setOf(value, available, invalid), expression.references - numbers.keys) { _, _, reads ->
            val computed = expression.value { id -> numbers[id] ?: reads[id] }
            JSONObject().put(value, computed ?: 0).put(available, computed != null).put(invalid, computed == null)
        }
        val config = JSONObject().put("label", label).put("expression", expression.json).put("constants", constants)
            .put("unit", unit).put("russian", russian)
        return PartCandidate("numeric_figure", "controls", "Calculate $label from the declared numeric values.", config,
            builder.finish(ui, readProjection = projection), numberOutput = PartNumberOutput(value, available),
            observationPaths = nativeObservationPaths(ui, VALUE to metric))
    }

    private fun counter(builder: FragmentBuilder, label: String, initial: Int, step: Int, russian: Boolean): PartCandidate {
        val value = builder.state("value", "number", initial)
        val add = builder.action("add", "state.$value = Math.min(1000000000, state.$value + $step);") { state, _ ->
            val next = minOf(1_000_000_000.0, state.getDouble(value) + step)
            state.put(value, if (next == floor(next)) next.toLong() else next)
        }
        val reset = builder.action("reset", "state.$value = $initial;") { state, _ -> state.put(value, initial) }
        val metric = partNode("metric", label).put("bind", value)
        val buttons = partContainer("row", listOf(
            control("button", if (russian) "Прибавить" else "Increase", add),
            control("button", if (russian) "Сбросить" else "Reset", reset)
        ))
        val full = partContainer("column", listOf(metric, buttons))
        return PartCandidate("counter", "controls", "A local counter with a fixed positive step and reset.",
            JSONObject().put("label", label).put("initial", initial).put("step", step).put("russian", russian),
            builder.finish(full, metric), primaryActionId = add, secondaryActionIds = setOf(reset),
            observationPaths = nativeObservationPaths(full, VALUE to metric))
    }

    private fun entries(builder: FragmentBuilder, label: String, russian: Boolean): PartCandidate {
        val draft = builder.state("draft", "string", "")
        val fields = JSONObject().put("id", "string").put("text", "string")
        val listSchema = JSONObject().put("type", "list").put("maxItems", 20).put("fields", fields)
        val items = builder.state("items", listSchema, JSONArray())
        val edit = builder.action("edit", "state.$draft = event.value;") { state, payload ->
            state.put(draft, checkNotNull(payload))
        }
        val add = builder.action("add", """
            const text = state.$draft.trim();
            if (text && state.$items.length < 20) {
              let id = 0;
              while (state.$items.some(row => row.id === String(id))) id++;
              state.$items.push({id: String(id), text});
              state.$draft = "";
            }
        """.trimIndent()) { state, _ ->
            val text = state.getString(draft).trim { it in JS_WHITESPACE }
            val rows = state.getJSONArray(items)
            if (text.isNotEmpty() && rows.length() < 20) {
                val used = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
                val id = (0..rows.length()).first { it.toString() !in used }
                rows.put(JSONObject().put("id", id.toString()).put("text", text))
                state.put(draft, "")
            }
        }
        val remove = builder.action("remove", "state.$items = state.$items.filter(row => row.id !== event.value);") { state, payload ->
            val rows = state.getJSONArray(items)
            state.put(items, jsonArray((0 until rows.length()).map(rows::getJSONObject).filter { it.getString("id") != payload }))
        }
        val input = control("input", if (russian) "Новая запись" else "New note", edit, draft).put("maxLength", 300)
        val button = control("button", if (russian) "Добавить" else "Add", add)
        val column = JSONObject().put("text", if (russian) "Запись" else "Note").put("field", "text")
        val removal = JSONObject().put("text", if (russian) "Удалить" else "Remove").put("action", remove)
        val list = partNode("list", label).put("bind", items)
            .put("columns", JSONArray().put(column)).put("rowActions", JSONArray().put(removal))
            .put("emptyText", if (russian) "Записей пока нет" else "No notes yet")
        val full = partContainer("column", listOf(input, button, list))
        return PartCandidate("notes", "records", "Add or remove up to 20 short text notes. No other fields or actions.",
            JSONObject().put("label", label).put("russian", russian),
            builder.finish(full, partNode("text", label)), primaryActionId = add,
            observationPaths = nativeObservationPaths(full, RECORDS to list))
    }

    private fun quickLog(builder: FragmentBuilder, label: String, slots: Slots, russian: Boolean): PartCandidate {
        fun localized(ru: String, en: String) = if (russian) ru else en
        val amount = slots.number("amount", 1..1_000_000)
        val alternateAmount = if (slots.has("alternateAmount")) slots.number("alternateAmount", 1..1_000_000) else null
        requireParts(alternateAmount != amount, "${slots.path}.alternateAmount", "a distinct alternate amount")
        val goal = slots.number("goal", 0..1_000_000, 0)
        val unit = if (slots.has("unit")) slots.text("unit", 12) else ""
        fun defaultLabel(value: Int) = localized("Добавить $value", "Log $value")
        val defaultButton = if (alternateAmount == null) localized("Добавить запись", "Log one") else defaultLabel(amount)
        val buttonLabel = slots.text("button", 60, defaultButton)
        val alternateLabel = alternateAmount?.let { slots.text("alternateButton", 60, defaultLabel(it)) }
        requireParts(alternateLabel?.trim() != buttonLabel.trim(), "${slots.path}.alternateButton", "a distinct alternate button label")
        val readings = (1..3).filter { slots.has("initial$it") }.let { indices ->
            requireParts(indices == (1..indices.size).toList(), slots.path, "consecutive initial readings")
            indices.map { slots.number("initial$it", 1..1_000_000) }
        }
        val day = LocalDate.now().toString()
        val rows = readings.mapIndexed { index, value ->
            JSONObject().put("id", "initial${index + 1}").put("amount", value).put("day", day).put("at", day)
        }.reversed()
        val fields = JSONObject().put("id", "string").put("amount", "number").put("day", "string").put("at", "string")
        val capacity = 100
        val schema = JSONObject().put("type", "list").put("maxItems", capacity).put("fields", fields)
        val history = builder.state("history", schema, jsonArray(rows))
        fun addAction(name: String, value: Int) = builder.projectedAction(name, """
            const rows = state.$history;
            if (rows.length < $capacity) {
              $QUICK_LOG_DAY_SOURCE
              const now = new Date();
              const day = localDay(now);
              const time = pad(now.getHours()) + ":" + pad(now.getMinutes());
              let id = 0;
              while (rows.some(row => row.id === String(id))) id++;
              rows.unshift({id: String(id), amount: $value, day, at: day + " " + time});
            }
        """.trimIndent())
        val addId = addAction("add", amount)
        val addButtons = buildList {
            add(control("button", buttonLabel, addId))
            if (alternateAmount != null && alternateLabel != null) {
                add(control("button", alternateLabel, addAction("add_alternate", alternateAmount)))
            }
        }
        val undo = builder.projectedAction("undo", """
            $QUICK_LOG_DAY_SOURCE
            const rows = state.$history;
            const today = localDay(new Date());
            const last = rows.findIndex(row => row.day === today);
            if (last >= 0) rows.splice(last, 1);
        """.trimIndent())
        val remove = builder.projectedAction("remove", "state.$history = state.$history.filter(row => row.id !== event.value);")
        val suffix = if (unit.isEmpty()) "" else " $unit"
        val goalSuffix = if (goal == 0) suffix else localized(" из $goal", " of $goal") + suffix
        val todayLabel = localized("Сегодня: ", "Today: ")
        val countLabel = localized("Записей сегодня: ", "Entries today: ")
        val yesterdayLabel = localized("Вчера: ", "Yesterday: ")
        val historyLabel = localized("История", "History")
        val amountLabel = localized("Количество", "Amount") + if (unit.isEmpty()) "" else " ($unit)"
        val columns = listOf(
            JSONObject().put("text", amountLabel).put("field", "amount"),
            JSONObject().put("text", localized("Дата и время", "Date and time")).put("field", "at")
        )
        val historyView = partNode("list", historyLabel).put("bind", history).put("columns", jsonArray(columns))
            .put("rowActions", JSONArray().put(JSONObject().put("text", localized("Удалить", "Remove")).put("action", remove)))
            .put("emptyText", localized("Записей пока нет", "No readings yet"))
        val todayView = partNode("text", todayLabel + readings.sum() + goalSuffix)
            .put("style", JSONObject().put("emphasis", "headline"))
        val countView = partNode("text", countLabel + readings.size)
        val yesterdayView = partNode("text", yesterdayLabel + "0" + suffix).put("visible", false)
        val view = partContainer("column", listOf(
            todayView, countView, yesterdayView,
            partNode("text", localized("Журнал заполнен", "The log is full")).put("visible", false)
        ) + addButtons + listOf(
            control("button", localized("Отменить последнюю", "Undo last"), undo).put("visible", readings.isNotEmpty()),
            historyView
        ))
        val projection = """
            $QUICK_LOG_DAY_SOURCE
            const now = new Date();
            const today = localDay(now);
            const yesterday = localDay(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1));
            const rows = state.$history;
            const current = rows.filter(row => row.day === today);
            const previous = rows.filter(row => row.day === yesterday);
            const total = (entries) => entries.reduce((sum, row) => sum + row.amount, 0);
            const [todayText, countText, yesterdayText, capacityText, ...controls] = view.children;
            todayText.text = ${JSONObject.quote(todayLabel)} + total(current) + ${JSONObject.quote(goalSuffix)};
            countText.text = ${JSONObject.quote(countLabel)} + current.length;
            yesterdayText.text = ${JSONObject.quote(yesterdayLabel)} + total(previous) + ${JSONObject.quote(suffix)};
            yesterdayText.visible = previous.length > 0;
            capacityText.visible = rows.length >= $capacity;
            for (const button of controls.slice(0, ${addButtons.size})) button.enabled = !capacityText.visible;
            controls[${addButtons.size}].visible = current.length > 0;
        """.trimIndent()
        return PartCandidate("quick_log", "records",
            "Log one or two fixed whole amounts with one tap per amount. Show daily totals, count and dated history with Undo and Remove. No reminders.",
            JSONObject().put("label", label).put("amount", amount).put("goal", goal).put("unit", unit)
                .put("button", buttonLabel).put("initialReadings", jsonArray(readings)).apply {
                    if (alternateAmount != null) put("alternateAmount", alternateAmount).put("alternateButton", alternateLabel)
                },
            builder.finish(view, partNode("text", label), projection), primaryActionId = addId, secondaryActionIds = setOf(undo),
            observationPaths = nativeObservationPaths(view, TODAY to todayView, COUNT to countView,
                YESTERDAY to yesterdayView, HISTORY to historyView))
    }

    private val ledgerPoints = 1..1_000_000

    private fun pointsLedgerFromSlots(builder: FragmentBuilder, label: String, slots: Slots, russian: Boolean): PartCandidate {
        val earn1 = slots.number("earn1", ledgerPoints)
        val earn2 = slots.number("earn2", ledgerPoints)
        val cost = slots.number("cost", ledgerPoints)
        val config = JSONObject().put("label", label).put("russian", russian)
            .put("team1", slots.text("team1", 60)).put("team2", slots.text("team2", 60))
            .put("earn1", earn1).put("earn2", earn2).put("cost", cost)
            .put("earn1Label", slots.text("earn1Label", 60, if (russian) "Начислить $earn1" else "Earn $earn1"))
            .put("earn2Label", slots.text("earn2Label", 60, if (russian) "Начислить $earn2" else "Earn $earn2"))
            .put("spendLabel", slots.text("spendLabel", 60, if (russian) "Списать $cost" else "Spend $cost"))
        return pointsLedger(builder, label, config, slots.path)
    }

    private fun pointsLedger(builder: FragmentBuilder, label: String, config: JSONObject, path: String): PartCandidate {
        config.requireKeys(path, setOf("label", "russian", "team1", "team2", "earn1", "earn2", "cost",
            "earn1Label", "earn2Label", "spendLabel"))
        val russian = config.opt("russian") as? Boolean ?: throw PartsContractFailure("$path.russian", "a boolean")
        fun localized(ru: String, en: String) = if (russian) ru else en
        fun text(name: String) = planText(config.opt(name), "$path.$name", 60)
        fun points(name: String) = integer(config.opt(name), ledgerPoints, "$path.$name")
        val teams = listOf(text("team1"), text("team2"))
        val labels = listOf(text("earn1Label"), text("earn2Label"), text("spendLabel"))
        requireParts(teams.map(String::trim).distinct().size == teams.size, path, "distinct team names")
        requireParts(labels.map(String::trim).distinct().size == labels.size, path, "distinct operation labels")
        val deltas = listOf(points("earn1"), points("earn2"), -points("cost"))
        val capacity = ToolValueSchema.LIST_MAX_ITEMS
        val fields = JSONObject().put("id", "string").put("owner", "number").put("team", "string")
            .put("operation", "string").put("points", "number")
        val schema = JSONObject().put("type", "list").put("maxItems", capacity).put("fields", fields)
        val history = builder.state("history", schema, JSONArray())
        val selected = builder.state("selected", "number", 1)
        val denied = builder.state("denied", "boolean", false)
        val full = builder.state("full", "boolean", false)
        val canUndo = builder.state("canUndo", "boolean", false)
        val balances = listOf(builder.state("balance1", "number", 0), builder.state("balance2", "number", 0))
        val select = builder.action("select", "state.$selected = event.value; state.$denied = false;") { state, value ->
            state.put(selected, checkNotNull(value)).put(denied, false)
        }
        val actions = labels.zip(deltas).mapIndexed { index, (operation, delta) ->
            builder.action("post${index + 1}", """
                const owner = state.$selected;
                const balance = ${jsonArray(balances)}[owner - 1];
                const rows = state.$history;
                const total = state[balance] + $delta;
                state.$denied = total < 0;
                if (!state.$denied && rows.length < $capacity) {
                  state[balance] = total;
                  rows.unshift({id: String(rows.length), owner, team: ${jsonArray(teams)}[owner - 1],
                    operation: ${JSONObject.quote(operation)}, points: $delta});
                  state.$canUndo = true;
                  state.$full = rows.length === $capacity;
                }
            """.trimIndent()) { state, _ ->
                val owner = state.getInt(selected)
                val balance = balances[owner - 1]
                val rows = state.getJSONArray(history)
                val total = state.getDouble(balance) + delta
                state.put(denied, total < 0)
                if (total >= 0 && rows.length() < capacity) {
                    val row = JSONObject().put("id", rows.length().toString()).put("owner", owner)
                        .put("team", teams[owner - 1]).put("operation", operation).put("points", delta)
                    val next = jsonArray(listOf(row) + List(rows.length(), rows::getJSONObject))
                    state.put(balance, total).put(history, next).put(canUndo, true).put(full, next.length() == capacity)
                }
            }
        }
        val undo = builder.action("undo", """
            const rows = state.$history;
            if (rows.length) {
              const last = rows.shift();
              const balance = ${jsonArray(balances)}[last.owner - 1];
              state[balance] -= last.points;
              state.$canUndo = rows.length > 0;
              state.$full = false;
              state.$denied = false;
            }
        """.trimIndent()) { state, _ ->
            val rows = state.getJSONArray(history)
            if (rows.length() > 0) {
                val last = rows.getJSONObject(0)
                val balance = balances[last.getInt("owner") - 1]
                rows.remove(0)
                state.put(balance, state.getDouble(balance) - last.getDouble("points"))
                    .put(canUndo, rows.length() > 0).put(full, false).put(denied, false)
            }
        }
        val metrics = teams.zip(balances).map { (team, binding) -> partNode("metric", team).put("bind", binding) }
        val options = teams.mapIndexed { index, team -> JSONObject().put("text", team).put("value", index + 1) }
        val teamSelect = control("singleChoice", localized("Команда", "Team"), select, selected).put("options", jsonArray(options))
        val buttons = labels.zip(actions).map { (operation, action) -> control("button", operation, action) }
        val columns = listOf(
            "team" to localized("Команда", "Team"), "operation" to localized("Действие", "Action"),
            "points" to localized("Очки", "Points")
        ).map { (field, title) -> JSONObject().put("field", field).put("text", title) }
        val ledger = partNode("list", localized("История", "History")).put("bind", history).put("columns", jsonArray(columns))
        val deniedView = partNode("text", localized("Недостаточно очков", "Not enough points")).put("visibleBind", denied)
        val fullUi = partContainer("column", metrics + teamSelect + buttons + listOf(
            deniedView,
            partNode("text", localized("Журнал заполнен", "The ledger is full")).put("visibleBind", full),
            control("button", localized("Отменить последнее", "Undo last"), undo).put("enabledBind", canUndo),
            ledger
        ))
        return PartCandidate("points_ledger", "records",
            "Track two teams with two fixed credit amounts, one fixed redemption, separate balances, and undoable history. Prevent debt.",
            config, builder.finish(fullUi, partContainer("column", metrics)), secondaryActionIds = setOf(undo),
            observationPaths = nativeObservationPaths(fullUi, BALANCE1 to metrics[0], BALANCE2 to metrics[1],
                DENIED to deniedView, HISTORY to ledger))
    }

    private val itineraryGap = 0..1_000_000_000
    private val itineraryDuration = 1..PLAN_NUMBER_LIMIT

    private fun itineraryClock(total: Long, dayUnit: String): String {
        val time = total % 1440
        val hours = (time / 60).toString().padStart(2, '0')
        val minutes = (time % 60).toString().padStart(2, '0')
        val days = total / 1440
        return "$hours:$minutes" + if (days > 0) " (+$days $dayUnit)" else ""
    }

    private fun itineraryClockSource(dayUnit: String) = """
        const clock = (total) => {
          const time = total % 1440;
          const hours = String(Math.floor(time / 60)).padStart(2, "0");
          const minutes = String(time % 60).padStart(2, "0");
          const days = Math.floor(total / 1440);
          return hours + ":" + minutes + (days > 0 ? " (+" + days + " " + ${JSONObject.quote(dayUnit)} + ")" : "");
        };
    """.trimIndent()

    private fun itinerary(
        builder: FragmentBuilder, label: String, stops: List<ItineraryStop>, start: Int, gap: Int, russian: Boolean,
    ): PartCandidate {
        fun localized(ru: String, en: String) = if (russian) ru else en
        val dayUnit = localized("дн.", "d")
        fun clock(total: Long) = itineraryClock(total, dayUnit)
        fun schedule(startMinute: Int): Pair<List<String>, String> {
            var at = startMinute.toLong()
            val ranges = stops.mapIndexed { index, stop ->
                val finish = at + stop.minutes
                val range = "${clock(at)}–${clock(finish)}"
                at = finish + if (index < stops.lastIndex) gap else 0
                range
            }
            return ranges to clock(at)
        }

        val (ranges, initialEnd) = schedule(start)
        val initialRows = stops.mapIndexed { index, stop ->
            JSONObject().put("id", "stop${index + 1}").put("name", stop.name)
                .put("range", ranges[index]).put("visited", false)
        }
        val fields = JSONObject().put("id", "string").put("name", "string").put("range", "string").put("visited", "boolean")
        val schema = JSONObject().put("type", "list").put("maxItems", ToolValueSchema.LIST_MAX_ITEMS).put("fields", fields)
        val rows = builder.state("stops", schema, jsonArray(initialRows))
        val startTime = builder.state("start", "number", start)
        val endTime = builder.state("end", "string", initialEnd)
        val editStart = builder.action("start", """
            const durations = ${jsonArray(stops.map(ItineraryStop::minutes))};
            ${itineraryClockSource(dayUnit)}
            state.$startTime = event.value;
            let at = event.value;
            state.$rows.forEach((row, index) => {
              const finish = at + durations[index];
              row.range = clock(at) + "–" + clock(finish);
              at = finish + (index + 1 < durations.length ? $gap : 0);
            });
            state.$endTime = clock(at);
        """.trimIndent()) { state, payload ->
            val startMinute = (payload as Number).toInt()
            val (updated, end) = schedule(startMinute)
            val current = state.getJSONArray(rows)
            updated.forEachIndexed { index, range -> current.getJSONObject(index).put("range", range) }
            state.put(startTime, startMinute).put(endTime, end)
        }
        val mark = builder.action("visit", """
            const row = state.$rows.find(stop => stop.id === event.value.key);
            if (row) row.visited = event.value.value;
        """.trimIndent()) { state, payload ->
            val visit = payload as JSONObject
            val current = state.getJSONArray(rows)
            val key = visit.getString("key")
            val row = (0 until current.length()).map(current::getJSONObject).firstOrNull { it.getString("id") == key }
            row?.put("visited", visit.getBoolean("value"))
        }
        val template = partContainer("column", listOf(
            partNode("text", localized("Остановка", "Stop")).put("bind", "name"),
            partNode("metric", localized("Время", "Time")).put("bind", "range"),
            control("toggle", localized("Посещено", "Visited"), mark, "visited")
        ))
        val list = partNode("list", localized("Остановки", "Stops")).put("bind", rows).put("rowTemplate", template)
        val end = partNode("metric", localized("Конец маршрута", "Route ends")).put("bind", endTime)
        val full = partContainer("column", listOf(
            control("timeInput", localized("Начало маршрута", "Route starts"), editStart, startTime), list, end
        ))
        val config = JSONObject().put("label", label).put("russian", russian).put("start", start).put("gap", gap)
            .put("stops", jsonArray(stops.map { JSONObject().put("name", it.name).put("minutes", it.minutes) }))
        return PartCandidate("itinerary", "plans",
            "Schedule predefined stops from an editable start time with fixed gaps and independent visit marks.",
            config, builder.finish(full, end), observationPaths = nativeObservationPaths(full, END to end, STOPS to list))
    }

    private fun editableItinerary(builder: FragmentBuilder, label: String, duration: Int, start: Int?, russian: Boolean): PartCandidate {
        fun localized(ru: String, en: String) = if (russian) ru else en
        val dayUnit = localized("дн.", "d")
        fun clock(total: Long) = itineraryClock(total, dayUnit)
        val capacity = ToolValueSchema.LIST_MAX_ITEMS
        val fields = JSONObject().put("id", "string").put("name", "string").put("minutes", "number").put("range", "string")
        val schema = JSONObject().put("type", "list").put("maxItems", capacity).put("fields", fields)
        val rows = builder.state("stops", schema, JSONArray())
        val startTime = builder.state("start", "number", start ?: 0)
        val hasStart = builder.state("hasStart", "boolean", start != null)
        val endTime = builder.state("end", "string", start?.let { clock(it.toLong() + duration) }.orEmpty())
        val remaining = builder.state("remaining", "number", duration)
        val nextId = builder.state("nextId", "number", 1)
        val draftName = builder.state("draftName", "string", "")
        val draftMinutes = builder.state("draftMinutes", "number", 0)
        val invalid = builder.state("invalid", "boolean", false)
        val full = builder.state("full", "boolean", false)
        fun sync(state: JSONObject) {
            val current = state.getJSONArray(rows)
            val started = state.getBoolean(hasStart)
            var elapsed = 0L
            val from = state.getLong(startTime)
            repeat(current.length()) { index ->
                val row = current.getJSONObject(index)
                val next = elapsed + row.getLong("minutes")
                row.put("range", if (started) "${clock(from + elapsed)}–${clock(from + next)}" else "—")
                elapsed = next
            }
            state.put(endTime, if (started) clock(from + duration) else "").put(remaining, duration - elapsed)
                .put(full, current.length() >= capacity)
        }
        val syncSource = """
            ${itineraryClockSource(dayUnit)}
            let elapsed = 0;
            const from = state.$startTime;
            const started = state.$hasStart;
            state.$rows.forEach(row => {
              const next = elapsed + row.minutes;
              row.range = started ? clock(from + elapsed) + "–" + clock(from + next) : "—";
              elapsed = next;
            });
            state.$endTime = started ? clock(from + $duration) : "";
            state.$remaining = $duration - elapsed;
            state.$full = state.$rows.length >= $capacity;
        """.trimIndent()
        fun validMinutes(value: Double) = value in 1.0..PLAN_NUMBER_LIMIT.toDouble() && value == floor(value)
        val changeStart = builder.action("start", "state.$startTime = event.value; state.$hasStart = true;\n$syncSource") { state, value ->
            state.put(startTime, checkNotNull(value)).put(hasStart, true)
            sync(state)
        }
        val enterName = builder.action("name", "state.$draftName = event.value; state.$invalid = false;") { state, value ->
            state.put(draftName, checkNotNull(value)).put(invalid, false)
        }
        val enterMinutes = builder.action("minutes", "state.$draftMinutes = event.value; state.$invalid = false;") { state, value ->
            state.put(draftMinutes, checkNotNull(value)).put(invalid, false)
        }
        val add = builder.action("add", """
            const name = state.$draftName.trim();
            const minutes = state.$draftMinutes;
            state.$invalid = !name || !Number.isInteger(minutes) || minutes < 1 || minutes > $PLAN_NUMBER_LIMIT;
            if (!state.$invalid && state.$rows.length < $capacity) {
              state.$rows.push({id: "stop" + state.$nextId, name, minutes, range: "—"});
              state.$nextId++;
              state.$draftName = "";
              state.$draftMinutes = 0;
              $syncSource
            }
        """.trimIndent()) { state, _ ->
            val name = state.getString(draftName).trim { it in JS_WHITESPACE }
            val minutes = state.getDouble(draftMinutes)
            val valid = name.isNotEmpty() && validMinutes(minutes)
            state.put(invalid, !valid)
            val current = state.getJSONArray(rows)
            if (valid && current.length() < capacity) {
                val id = state.getLong(nextId)
                current.put(JSONObject().put("id", "stop$id").put("name", name).put("minutes", minutes.toInt()).put("range", "—"))
                state.put(nextId, id + 1).put(draftName, "").put(draftMinutes, 0)
                sync(state)
            }
        }
        fun rowFor(state: JSONObject, payload: JSONObject): JSONObject {
            val current = state.getJSONArray(rows)
            val key = payload.getString("key")
            return (0 until current.length()).asSequence().map(current::getJSONObject).first { it.getString("id") == key }
        }
        val rename = builder.action("rename", """
            const name = event.value.value.trim();
            state.$invalid = !name;
            if (name) state.$rows.find(row => row.id === event.value.key).name = name;
        """.trimIndent()) { state, value ->
            val payload = value as JSONObject
            val name = payload.getString("value").trim { it in JS_WHITESPACE }
            state.put(invalid, name.isEmpty())
            if (name.isNotEmpty()) rowFor(state, payload).put("name", name)
        }
        val resize = builder.action("resize", """
            const minutes = event.value.value;
            state.$invalid = !Number.isInteger(minutes) || minutes < 1 || minutes > $PLAN_NUMBER_LIMIT;
            if (!state.$invalid) {
              state.$rows.find(row => row.id === event.value.key).minutes = minutes;
              $syncSource
            }
        """.trimIndent()) { state, value ->
            val payload = value as JSONObject
            val minutes = payload.getDouble("value")
            state.put(invalid, !validMinutes(minutes))
            if (validMinutes(minutes)) {
                rowFor(state, payload).put("minutes", minutes.toInt())
                sync(state)
            }
        }
        val remove = builder.action("remove", """
            state.$rows = state.$rows.filter(row => row.id !== event.value.key);
            state.$invalid = false;
            $syncSource
        """.trimIndent()) { state, value ->
            val key = (value as JSONObject).getString("key")
            val current = state.getJSONArray(rows)
            state.put(rows, jsonArray(List(current.length(), current::getJSONObject).filter { it.getString("id") != key }))
                .put(invalid, false)
            sync(state)
        }
        fun nameInput(text: String, action: String, bind: String) =
            control("input", text, action, bind).put("maxLength", 100).put("maxLines", 1)
        fun minuteInput(text: String, action: String, bind: String, minimum: Int) =
            control("numberInput", text, action, bind).put("min", minimum).put("max", PLAN_NUMBER_LIMIT).put("step", 1)
        val template = partContainer("column", listOf(
            nameInput(localized("Остановка", "Stop"), rename, "name"),
            minuteInput(localized("Минуты", "Minutes"), resize, "minutes", 1),
            partNode("metric", localized("Время", "Time")).put("bind", "range"),
            control("button", localized("Удалить", "Remove"), remove)
        ))
        val list = partNode("list", localized("Остановки", "Stops")).put("bind", rows).put("rowTemplate", template)
            .put("emptyText", localized("Остановок пока нет", "No stops yet"))
        val end = partNode("metric", localized("Окончание", "Ends")).put("bind", endTime).put("visibleBind", hasStart)
        val minutesLeft = partNode("metric", localized("Осталось, мин", "Minutes left")).put("bind", remaining)
        val ui = partContainer("column", listOf(
            control("timeInput", localized("Укажите начало", "Set start time"), changeStart, startTime),
            end, minutesLeft,
            nameInput(localized("Новая остановка", "New stop"), enterName, draftName),
            minuteInput(localized("Минуты на остановке", "Stop minutes"), enterMinutes, draftMinutes, 0),
            control("button", localized("Добавить остановку", "Add stop"), add),
            partNode("text", localized("Укажите название и целые минуты", "Enter a name and whole minutes")).put("visibleBind", invalid),
            partNode("text", localized("Список остановок заполнен", "The stop list is full")).put("visibleBind", full),
            list
        ))
        val config = JSONObject().put("label", label).put("russian", russian).put("duration", duration)
        if (start != null) config.put("start", start)
        return PartCandidate("editable_itinerary", "plans",
            "Build a local schedule from a start time, a fixed window, and stops the user enters and edits. Use no external information.",
            config, builder.finish(ui, partContainer("column", listOf(end, minutesLeft))), primaryActionId = add,
            observationPaths = nativeObservationPaths(ui, END to end, REMAINING to minutesLeft, STOPS to list))
    }

    private val decimalReading = "[+-]?[0-9]+(?:[.,][0-9]+)?"
    private val decimalReadingPattern = Regex(decimalReading)

    private fun readingStatus(
        builder: FragmentBuilder, label: String, bands: List<ReadingBand>, unit: String, initial: Double?, russian: Boolean,
    ): PartCandidate {
        fun localized(ru: String, en: String) = if (russian) ru else en
        fun reading(id: String, value: Double) = JSONObject().put("id", id).put("value", value)
            .put("status", bands.last { it.minimum == null || value >= it.minimum }.label)

        val capacity = ToolValueSchema.LIST_MAX_ITEMS
        val seed = initial?.let { reading("initial", it) }
        val fields = JSONObject().put("id", "string").put("value", "number").put("status", "string")
        val listSchema = JSONObject().put("type", "list").put("maxItems", capacity).put("fields", fields)
        val history = builder.state("history", listSchema, jsonArray(listOfNotNull(seed)))
        val draft = builder.state("draft", "string", "")
        val latest = builder.state("latest", "number", initial ?: 0)
        val status = builder.state("status", "string", seed?.getString("status").orEmpty())
        val hasReading = builder.state("hasReading", "boolean", seed != null)
        val hasChart = builder.state("hasChart", "boolean", false)
        val invalid = builder.state("invalid", "boolean", false)
        val full = builder.state("full", "boolean", false)
        fun arraySchema() = JSONObject().put("type", "array").put("maxItems", capacity).put("items", "number")
        val categories = builder.state("categories", arraySchema(), jsonArray(if (seed == null) emptyList() else listOf(1)))
        val chartValues = builder.state("values", arraySchema(), jsonArray(listOfNotNull(initial)))
        fun sync(state: JSONObject, rows: JSONArray) {
            val first = rows.optJSONObject(0)
            state.put(latest, first?.getDouble("value") ?: 0)
                .put(status, first?.getString("status").orEmpty())
                .put(hasReading, rows.length() > 0).put(hasChart, rows.length() >= 2)
                .put(full, rows.length() >= capacity)
                .put(categories, jsonArray((1..rows.length()).toList()))
                .put(chartValues, jsonArray((rows.length() - 1 downTo 0).map { rows.getJSONObject(it).getDouble("value") }))
        }
        val syncSource = """
            const first = rows[0];
            state.$latest = first ? first.value : 0;
            state.$status = first ? first.status : "";
            state.$hasReading = rows.length > 0;
            state.$hasChart = rows.length >= 2;
            state.$full = rows.length >= $capacity;
            state.$categories = rows.map((_, index) => index + 1);
            state.$chartValues = rows.map(row => row.value).reverse();
        """.trimIndent()
        val edit = builder.action("edit", "state.$draft = event.value; state.$invalid = false;") { state, value ->
            state.put(draft, checkNotNull(value)).put(invalid, false)
        }
        val pattern = JSONObject.quote("^${decimalReading}$")
        val minima = jsonArray(bands.mapNotNull(ReadingBand::minimum))
        val labels = jsonArray(bands.map(ReadingBand::label))
        val add = builder.action("add", """
            const text = state.$draft.trim();
            const value = new RegExp($pattern).test(text) ? Number(text.replace(",", ".")) : NaN;
            state.$invalid = !Number.isFinite(value) || Math.abs(value) > $PLAN_NUMBER_LIMIT;
            const rows = state.$history;
            if (!state.$invalid && rows.length < $capacity) {
              let band = 0;
              for (const minimum of $minima) {
                if (value < minimum) break;
                band++;
              }
              let id = 0;
              while (rows.some(row => row.id === String(id))) id++;
              rows.unshift({id: String(id), value, status: $labels[band]});
              state.$draft = "";
              $syncSource
            }
        """.trimIndent()) { state, _ ->
            val text = state.getString(draft).trim { it in JS_WHITESPACE }
            val value = text.takeIf(decimalReadingPattern::matches)?.replace(',', '.')?.toDoubleOrNull()
            val valid = value != null && abs(value) <= PLAN_NUMBER_LIMIT
            state.put(invalid, !valid)
            val current = state.getJSONArray(history)
            if (valid && current.length() < capacity) {
                val rows = List(current.length(), current::getJSONObject)
                val used = rows.map { it.getString("id") }.toSet()
                val id = (0..rows.size).first { it.toString() !in used }.toString()
                val next = jsonArray(listOf(reading(id, checkNotNull(value))) + rows)
                state.put(history, next).put(draft, "")
                sync(state, next)
            }
        }
        val remove = builder.action("remove", """
            const rows = state.$history.filter(row => row.id !== event.value);
            state.$history = rows;
            $syncSource
        """.trimIndent()) { state, value ->
            val current = state.getJSONArray(history)
            val next = jsonArray(List(current.length(), current::getJSONObject).filter { it.getString("id") != value })
            state.put(history, next)
            sync(state, next)
        }
        val suffix = if (unit.isEmpty()) "" else " ($unit)"
        val latestView = partNode("metric", localized("Последнее", "Latest") + suffix).put("bind", latest)
        val statusView = partNode("metric", localized("Метка", "Status")).put("bind", status)
        val summary = partContainer("column", listOf(latestView, statusView)).put("visibleBind", hasReading)
        val series = JSONObject().put("text", localized("Показания", "Readings")).put("bind", chartValues)
        val chart = partNode("chart", localized("График показаний", "Reading chart") + suffix)
            .put("kind", "line").put("categories", categories).put("series", JSONArray().put(series)).put("visibleBind", hasChart)
        val columns = listOf("value" to localized("Значение", "Value"), "status" to localized("Метка", "Status"))
            .map { (key, title) -> JSONObject().put("field", key).put("text", title) }
        val removal = JSONObject().put("text", localized("Удалить", "Remove")).put("action", remove)
        val list = partNode("list", localized("История", "History") + suffix).put("bind", history)
            .put("columns", jsonArray(columns)).put("rowActions", JSONArray().put(removal))
            .put("emptyText", localized("Показаний пока нет", "No readings yet"))
        val ui = partContainer("column", listOf(
            summary,
            control("input", localized("Новое показание", "New reading") + suffix, edit, draft).put("maxLines", 1),
            control("button", localized("Добавить показание", "Add reading"), add),
            partNode("text", localized("Введите число", "Enter a number")).put("visibleBind", invalid),
            partNode("text", localized("Журнал заполнен", "The log is full")).put("visibleBind", full),
            chart,
            list
        ))
        val config = JSONObject().put("label", label).put("russian", russian).put("unit", unit)
            .put("bands", jsonArray(bands.map { band ->
                JSONObject().put("label", band.label).apply { band.minimum?.let { put("min", it) } }
            }))
        if (initial != null) config.put("initial", initial)
        return PartCandidate("reading_status", "records",
            "Classify numeric readings using supplied bands. Show the latest status, newest-first history, and a line chart.",
            config, builder.finish(ui, summary), primaryActionId = add,
            observationPaths = nativeObservationPaths(ui, LATEST to latestView, STATUS to statusView, HISTORY to list))
    }

    private fun dueItems(builder: FragmentBuilder, label: String, items: List<DueItemPlan>, window: Int, russian: Boolean,
        edit: NativePartEditMetadata = NativePartEditMetadata()): PartCandidate {
        fun localized(ru: String, en: String) = if (russian) ru else en
        fun listSchema(fields: JSONObject) =
            JSONObject().put("type", "list").put("maxItems", ToolValueSchema.LIST_MAX_ITEMS).put("fields", fields)
        fun occurrence(row: JSONObject) = edit.actionTarget(row.getString("id") + "@" + row.getLong("occurrence"))
        val initial = items.mapIndexed { index, item ->
            initialDueSlot(index, item.name, checkNotNull(item.date).toString(), item.repeatDays)
        }
        val rows = builder.state("items", listSchema(JSONObject().put("id", "string").put("name", "string")
            .put("date", "string").put("repeatDays", "number").put("completed", "boolean").put("occurrence", "number")), jsonArray(initial))
        val display = builder.state("display", listSchema(JSONObject().put("id", "string").put("name", "string")
            .put("date", "string").put("status", "string")), JSONArray())
        val overdue = builder.state("overdue", "number", 0)
        val upcoming = builder.state("upcoming", "number", 0)
        val projection = PartReadProjection(setOf(display, overdue, upcoming)) { state, clock, _ ->
            val current = state.getJSONArray(rows)
            val active = (0 until current.length()).filterNot(edit.retiredSlots::contains).map(current::getJSONObject)
                .filterNot { it.getBoolean("completed") }
                .map { it to LocalDate.parse(it.getString("date")) }.sortedBy { it.second }
            var late = 0
            var soon = 0
            val visible = active.map { (row, date) ->
                val days = ChronoUnit.DAYS.between(clock.localDate, date)
                if (days < 0) late++
                if (days in 0L..window.toLong()) soon++
                val status = when {
                    days < 0 -> localized("Просрочено", "Overdue")
                    days == 0L -> localized("Сегодня", "Today")
                    days <= window -> localized("Скоро", "Upcoming")
                    else -> localized("Запланировано", "Scheduled")
                }
                JSONObject().put("id", occurrence(row)).put("name", row.getString("name"))
                    .put("date", date.toString()).put("status", status)
            }
            JSONObject().put(display, jsonArray(visible)).put(overdue, late).put(upcoming, soon)
        }
        val complete = builder.clockedAction("complete") { state, payload, clock ->
            val current = state.getJSONArray(rows)
            val row = (0 until current.length()).asSequence().filterNot(edit.retiredSlots::contains).map(current::getJSONObject)
                .firstOrNull { !it.getBoolean("completed") && occurrence(it) == payload }
                ?: throw PartsContractFailure("$.event.value", "a current due occurrence")
            val repeat = row.getInt("repeatDays")
            if (repeat == 0) row.put("completed", true)
            else row.put("date", dueDate(clock.localDate, repeat, "$.event.value").toString())
                .put("occurrence", row.getLong("occurrence") + 1)
        }
        val columns = listOf("name" to localized("Задача", "Item"), "date" to localized("Срок", "Due date"),
            "status" to localized("Статус", "Status")).map { (field, title) ->
            JSONObject().put("field", field).put("text", title)
        }
        val action = JSONObject().put("action", complete).put("text", localized("Отметить", "Mark complete"))
        val list = partNode("list", if (edit.showLabel) label else localized("Сроки", "Due items")).put("bind", display)
            .put("columns", jsonArray(columns)).put("rowActions", JSONArray().put(action))
            .put("emptyText", localized("Нет открытых задач", "No open items"))
        val overdueView = partNode("metric", localized("Просрочено", "Overdue")).put("bind", overdue)
        val upcomingView = partNode("metric", localized("В ближайшие $window дн.", "Next $window days")).put("bind", upcoming)
        val summary = listOf(overdueView, upcomingView)
        val full = partContainer("column", summary + list)
        val config = JSONObject().put("label", label).put("russian", russian).put("window", window)
            .put("items", jsonArray(items.map { item ->
                JSONObject().put("name", item.name).put("date", checkNotNull(item.date).toString()).apply {
                    if (item.repeatDays > 0) put("repeatDays", item.repeatDays)
                }
            })).also(edit::write)
        return PartCandidate("due_items", "plans",
            "Sort supplied deadlines, show local-day counts, and complete or reschedule an item from the current day.",
            config, builder.finish(full, partContainer("column", summary), readProjection = projection),
            observationPaths = nativeObservationPaths(full, OVERDUE to overdueView, UPCOMING to upcomingView, ITEMS to list))
    }

    private fun ideaDeck(builder: FragmentBuilder, label: String, ideas: List<DeckIdea>, russian: Boolean,
        edit: NativePartEditMetadata = NativePartEditMetadata()): PartCandidate {
        fun localized(ru: String, en: String) = if (russian) ru else en
        fun effortLabel(effort: IdeaEffort) = when (effort) {
            IdeaEffort.LOW -> localized("Мало сил", "Low")
            IdeaEffort.MEDIUM -> localized("Средне", "Medium")
            IdeaEffort.HIGH -> localized("Много сил", "High")
        }
        val limit = builder.state("limit", "number", ideas.maxOf(DeckIdea::minutes))
        val effort = builder.state("effort", "string", "all")
        val cursor = builder.state("cursor", "number", 0)
        val version = builder.state("version", "number", 0)
        val capacity = ToolValueSchema.ARRAY_MAX_ITEMS
        val record = JSONObject().put("type", "object").put("fields", JSONObject().put("idea", "number").put("day", "string"))
        val history = builder.state("history", JSONObject().put("type", "array").put("maxItems", capacity).put("items", record), JSONArray())
        val fields = JSONObject().put("id", "string").put("name", "string").put("minutes", "number").put("effort", "string")
            .put("canCycle", "boolean").put("canComplete", "boolean")
        val display = builder.state("display", JSONObject().put("type", "list").put("maxItems", 1).put("fields", fields), JSONArray())
        val availableCount = builder.state("available", "number", 0)
        val completedCount = builder.state("completed", "number", 0)
        val historyFull = builder.state("historyFull", "boolean", false)

        fun doneToday(state: JSONObject, day: String): Set<Int> {
            val rows = state.getJSONArray(history)
            return (0 until rows.length()).asSequence().map(rows::getJSONObject)
                .filter { it.getString("day") == day }.map { it.getInt("idea") }.toSet()
        }
        fun available(state: JSONObject, done: Set<Int>): List<Int> {
            val minutes = state.getInt(limit)
            val selectedEffort = state.getString(effort)
            return ideas.indices.filter { index ->
                val idea = ideas[index]
                index !in edit.retiredSlots && index !in done && idea.minutes <= minutes &&
                    (selectedEffort == "all" || idea.effort.wire == selectedEffort)
            }
        }
        fun featured(state: JSONObject, choices: List<Int>): Int? =
            choices.firstOrNull { it >= state.getInt(cursor) } ?: choices.firstOrNull()
        fun advance(state: JSONObject) {
            state.put(version, state.getLong(version) + 1)
        }
        val projection = PartReadProjection(setOf(display, availableCount, completedCount, historyFull)) { state, clock, _ ->
            val day = clock.localDate.toString()
            val done = doneToday(state, day)
            val choices = available(state, done)
            val full = state.getJSONArray(history).length() >= capacity
            val row = featured(state, choices)?.let { index ->
                val idea = ideas[index]
                val key = edit.actionTarget("idea${index + 1}@$day#${state.getLong(version)}")
                JSONObject().put("id", key).put("name", idea.name).put("minutes", idea.minutes)
                    .put("effort", effortLabel(idea.effort)).put("canCycle", choices.size > 1).put("canComplete", !full)
            }
            JSONObject().put(display, jsonArray(listOfNotNull(row))).put(availableCount, choices.size)
                .put(completedCount, done.size).put(historyFull, full)
        }
        val editLimit = builder.clockedAction("limit") { state, value, _ ->
            state.put(limit, integer(value, 1..PLAN_NUMBER_LIMIT, "$.event.value")).put(cursor, 0)
            advance(state)
        }
        val editEffort = builder.clockedAction("effort") { state, value, _ ->
            state.put(effort, checkNotNull(value)).put(cursor, 0)
            advance(state)
        }
        val cycle = builder.clockedAction("cycle") { state, _, clock ->
            val choices = available(state, doneToday(state, clock.localDate.toString()))
            val current = choices.indexOf(checkNotNull(featured(state, choices)))
            state.put(cursor, choices[(current + 1) % choices.size])
            advance(state)
        }
        val complete = builder.clockedAction("complete") { state, _, clock ->
            val day = clock.localDate.toString()
            val current = checkNotNull(featured(state, available(state, doneToday(state, day))))
            state.getJSONArray(history).put(JSONObject().put("idea", current).put("day", day))
            advance(state)
        }
        val timeInput = control("numberInput", localized("Лимит времени, мин", "Time limit, min"), editLimit, limit)
            .put("min", 1).put("max", PLAN_NUMBER_LIMIT).put("step", 1)
        val options = listOf(JSONObject().put("text", localized("Любые", "Any")).put("value", "all")) +
            IdeaEffort.entries.map { JSONObject().put("text", effortLabel(it)).put("value", it.wire) }
        val effortInput = control("singleChoice", localized("Усилие", "Effort"), editEffort, effort).put("options", jsonArray(options))
        val columns = listOf(
            "name" to localized("Идея", "Idea"), "minutes" to localized("Минуты", "Minutes"), "effort" to localized("Усилие", "Effort")
        ).map { (field, title) -> JSONObject().put("field", field).put("text", title) }
        val rowActions = listOf(
            JSONObject().put("text", localized("Другая", "Next")).put("action", cycle).put("enabledField", "canCycle"),
            JSONObject().put("text", localized("Сделано сегодня", "Done today")).put("action", complete).put("enabledField", "canComplete")
        )
        val list = partNode("list", if (edit.showLabel) label else localized("Идея", "Idea")).put("bind", display).put("columns", jsonArray(columns))
            .put("rowActions", jsonArray(rowActions)).put("emptyText", localized("Нет подходящих идей", "No matching ideas"))
        val availableView = partNode("metric", localized("Доступно", "Available")).put("bind", availableCount)
        val completedView = partNode("metric", localized("Сделано сегодня", "Done today")).put("bind", completedCount)
        val summary = listOf(availableView, completedView)
        val ui = partContainer("column", listOf(timeInput, effortInput) + summary + listOf(
            list, partNode("text", localized("История заполнена", "Completion history is full")).put("visibleBind", historyFull)
        ))
        val config = JSONObject().put("label", label).put("russian", russian)
            .put("ideas", jsonArray(ideas.map { JSONObject().put("name", it.name).put("minutes", it.minutes).put("effort", it.effort.wire) }))
            .also(edit::write)
        return PartCandidate("idea_deck", "plans",
            "Cycle supplied ideas with time and effort filters. Keep daily completions and their history.",
            config, builder.finish(ui, partContainer("column", summary), readProjection = projection),
            observationPaths = nativeObservationPaths(ui, AVAILABLE to availableView, COMPLETED to completedView, IDEAS to list))
    }

    private fun content(builder: FragmentBuilder, label: String, body: String, pictogram: String? = null): PartCandidate {
        val config = JSONObject().put("label", label).put("body", body)
        val bodyView = partNode("text", body)
        val plain = if (label == body) bodyView else partContainer("section", listOf(bodyView)).put("text", label)
        if (pictogram == null) {
            return PartCandidate("information", "lesson", "Show one title and a short piece of text without interaction.",
                config, builder.finish(plain, partNode("text", label)), observationPaths = nativeObservationPaths(plain, BODY to bodyView))
        }

        val card = partContainer("card", listOf(
            partContainer("theme", listOf(
                partNode("text", label).put("style", JSONObject().put("emphasis", "headline").put("align", "center")),
                partNode("text", pictogram).put("style", JSONObject().put("emphasis", "number")),
            )).put("preset", "playful").put("typeScale", 1.5).put("style", JSONObject().put("align", "center")),
            bodyView.put("style", JSONObject().put("align", "center")),
        )).put("style", JSONObject().put("align", "center").put("gap", "medium"))
            .put("layout", JSONObject().put("fillWidth", true))
        return PartCandidate("information", "lesson", "Show a large title, the color pictogram $pictogram, and its caption.",
            config.put("pictogram", pictogram), builder.finish(card, partNode("text", label)),
            observationPaths = nativeObservationPaths(card, BODY to bodyView))
    }

    private fun exercise(builder: FragmentBuilder, label: String, options: List<String>, correct: Int, russian: Boolean): PartCandidate {
        val answer = builder.state("answer", "number", 0)
        val success = builder.state("success", "boolean", false)
        val failure = builder.state("failure", "boolean", false)
        val select = builder.action("select", """
            state.$answer = event.value;
            state.$success = event.value === $correct;
            state.$failure = event.value !== 0 && event.value !== $correct;
        """.trimIndent()) { state, payload ->
            val chosen = (payload as Number).toInt()
            state.put(answer, chosen).put(success, chosen == correct).put(failure, chosen != 0 && chosen != correct)
        }
        val all = listOf(if (russian) "Выберите ответ" else "Choose an answer") + options
        val optionNodes = all.mapIndexed { index, option -> JSONObject().put("text", option).put("value", index) }
        val choice = control("singleChoice", label, select, answer).put("options", jsonArray(optionNodes))
        val right = partNode("text", if (russian) "Верно" else "Correct").put("visibleBind", success)
        val wrong = partNode("text", if (russian) "Попробуйте ещё раз" else "Try again").put("visibleBind", failure)
        val full = partContainer("column", listOf(choice, right, wrong))
        return PartCandidate("choice_exercise", "lesson", "Choose one answer and show whether it is correct.",
            JSONObject().put("label", label).put("options", jsonArray(options)).put("correct", correct).put("russian", russian),
            builder.finish(full, partNode("text", label)), observationPaths = nativeObservationPaths(full, CORRECT to right, INCORRECT to wrong))
    }

    private val JS_WHITESPACE = ('\u0009'..'\u000d').toSet() + ('\u2000'..'\u200a') +
        " \u00a0\u1680\u2028\u2029\u202f\u205f\u3000\ufeff".toSet()

    private val QUICK_LOG_DAY_SOURCE = """
        const pad = (value) => String(value).padStart(2, "0");
        const localDay = (date) => date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
    """.trimIndent()
}
