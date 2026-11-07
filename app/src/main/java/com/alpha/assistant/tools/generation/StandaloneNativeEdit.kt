package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiDiagnostic
import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

internal data class NativeEditBase(
    val libraryId: String,
    val artifactId: String,
    val revision: Int,
    val stateVersion: Int,
    val requestId: String
)

internal data class NativeEditResult(val spec: String, val state: String)

internal enum class NativeEditKind(val wire: String, val field: String?, val capacity: Int) {
    DUE("due_items", "items", ToolValueSchema.LIST_MAX_ITEMS),
    IDEAS("idea_deck", "ideas", ToolValueSchema.ARRAY_MAX_ITEMS),
    FIGURE("numeric_figure", null, 0)
}

internal data class NativeEditablePart(val position: Int, val instanceId: String, val kind: NativeEditKind, val definition: String) {
    val alias get() = "p${position + 1}"
    fun stateKey(field: String) = "${instanceId}_$field"
}

internal data class NativePartEditMetadata(
    val version: Int = 0,
    val retiredSlots: Set<Int> = emptySet(),
    val showLabel: Boolean = false
) {
    fun actionTarget(target: String) = if (version == 0) target else "$target~$version"

    fun write(config: JSONObject) {
        if (version != 0) config.put(FIELD, JSONObject().put("version", version)
            .put("retiredSlots", jsonArray(retiredSlots.sorted())).put("showLabel", showLabel))
    }

    companion object {
        const val FIELD = "nativeEdit"

        fun read(config: JSONObject, slots: Int, path: String): NativePartEditMetadata {
            if (!config.has(FIELD)) return NativePartEditMetadata()
            val at = "$path.$FIELD"
            val row = config.optJSONObject(FIELD) ?: throw PartsContractFailure(at, "native edit metadata")
            row.requireKeys(at, setOf("version", "retiredSlots", "showLabel"))
            val version = boundedInt(row.opt("version"), 1..Int.MAX_VALUE, "$at.version")
            val retired = row.items("retiredSlots", 0..slots, "$at.retiredSlots").mapIndexed { index, slot ->
                boundedInt(slot, 0 until slots, "$at.retiredSlots[$index]")
            }
            requireParts(retired.zipWithNext().all { (a, b) -> a < b }, "$at.retiredSlots", "ordered unique slots")
            val showLabel = row.opt("showLabel") as? Boolean ?: throw PartsContractFailure("$at.showLabel", "a boolean")
            return NativePartEditMetadata(version, retired.toSet(), showLabel)
        }
    }
}

/** One process-local edit request. Only providerContext is suitable for provider input. */
internal class StandaloneNativeEdit private constructor(
    private val base: NativeEditBase,
    private val previous: ToolSpec,
    private val durable: String,
    private val clock: PartClockSample,
    private val parts: List<PartDefinition>,
    private val token: String
) {
    private class PartDefinition(val part: NativeEditablePart) {
        val config = JSONObject(part.definition)
        val items = part.kind.field?.let(config::getJSONArray) ?: JSONArray()
        val metadata = NativePartEditMetadata.read(config, items.length(), "$.parts.config")

        fun providerFields() = JSONObject().put("alias", part.alias).put("type", part.kind.wire)
            .put("label", config.getString("label")).apply {
            if (part.kind == NativeEditKind.FIGURE) {
                put("expression", config.getJSONObject("expression"))
                put("unit", config.getString("unit"))
                return@apply
            }
            put("slots", jsonArray((0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                JSONObject().put("alias", slotAlias(index)).put("name", item.getString("name"))
                    .put("retired", index in metadata.retiredSlots).apply {
                        if (part.kind == NativeEditKind.DUE) put("repeatDays", item.optInt("repeatDays", 0))
                        else put("minutes", item.getInt("minutes")).put("effort", item.getString("effort"))
                    }
            }))
            if (part.kind == NativeEditKind.DUE) put("windowDays", config.getInt("window"))
        }

        fun checkDurable(state: JSONObject) {
            if (part.kind == NativeEditKind.DUE) {
                val rows = state.getJSONArray(part.stateKey("items"))
                requireParts(rows.length() == items.length(), "$.state", "the declared due slots")
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val declared = items.getJSONObject(index)
                    requireParts(row.opt("id") == dueSlotId(index) && row.opt("name") == declared.opt("name") &&
                        sameToolJson(row.opt("repeatDays"), declared.optInt("repeatDays", 0)), "$.state", "the declared due slot identity")
                    planDate(row.opt("date"), "$.state.date")
                    val occurrence = row.opt("occurrence") as Number
                    requireParts(occurrence.toDouble() == occurrence.toLong().toDouble() && occurrence.toLong() >= 0,
                        "$.state.occurrence", "a nonnegative whole occurrence number")
                }
            } else if (part.kind == NativeEditKind.IDEAS) {
                val history = state.getJSONArray(part.stateKey("history"))
                val seen = mutableSetOf<Pair<Int, String>>()
                for (index in 0 until history.length()) {
                    val record = history.getJSONObject(index)
                    val item = boundedInt(record.opt("idea"), 0 until items.length(), "$.state.history.idea")
                    val day = planDate(record.opt("day"), "$.state.history.day").toString()
                    requireParts(seen.add(item to day), "$.state.history", "unique completions per slot and local day")
                }
            }
        }
    }

    private sealed interface Edit {
        data class App(val title: String?, val description: String?) : Edit
        data class Rename(val part: PartDefinition, val label: String) : Edit
        data class Window(val part: PartDefinition, val days: Int) : Edit
        data class Figure(val part: PartDefinition, val expression: NumberExpression) : Edit
        data class Due(val part: PartDefinition, val slot: Int?, val name: String?, val date: LocalDate?, val repeatDays: Int?) : Edit
        data class Idea(val part: PartDefinition, val slot: Int?, val name: String?, val minutes: Int?, val effort: IdeaEffort?) : Edit
        data class Lifecycle(val part: PartDefinition, val slot: Int, val retired: Boolean) : Edit
    }

    private class PartDraft(val original: PartDefinition, state: JSONObject) {
        val config = JSONObject(original.part.definition)
        val items = original.part.kind.field?.let(config::getJSONArray) ?: JSONArray()
        val retired = original.metadata.retiredSlots.toMutableSet()
        var showLabel = original.metadata.showLabel
        val dueRows = if (original.part.kind == NativeEditKind.DUE) state.getJSONArray(original.part.stateKey("items")) else null
        private val previousDue = dueRows?.toString()

        fun addItem(item: JSONObject) {
            requireParts(items.length() < original.part.kind.capacity, "$.operations", "room for a new slot")
            items.put(item)
        }

        fun changedConfig(): JSONObject? {
            val old = original.metadata
            if (sameToolJson(config, original.config) && previousDue == dueRows?.toString() &&
                retired == old.retiredSlots && showLabel == old.showLabel) return null
            if (original.part.kind == NativeEditKind.FIGURE) return config
            requireParts(old.version < Int.MAX_VALUE, "$.composition", "room for a new view version")
            NativePartEditMetadata(old.version + 1, retired, showLabel).write(config)
            return config
        }
    }

    fun providerContext(): String =
        JSONObject().put("token", token).put("parts", jsonArray(parts.map(PartDefinition::providerFields))).toString()

    fun apply(rawPlan: String, observer: ((ToolUiDiagnostic) -> Unit)? = null): NativeEditResult {
        val operations = parse(rawPlan)
        val state = previous.validateState(durable)
        val drafts = mutableMapOf<PartDefinition, PartDraft>()
        fun draft(part: PartDefinition) = drafts.getOrPut(part) { PartDraft(part, state) }
        var title = previous.title
        var description = previous.description
        operations.forEach { edit ->
            when (edit) {
                is Edit.App -> {
                    title = edit.title ?: title
                    description = edit.description ?: description
                }
                is Edit.Rename -> draft(edit.part).apply {
                    config.put("label", edit.label)
                    if (edit.part.part.kind != NativeEditKind.FIGURE) showLabel = true
                }
                is Edit.Window -> draft(edit.part).config.put("window", edit.days)
                is Edit.Figure -> draft(edit.part).config.apply {
                    val constants = getJSONObject("constants")
                    put("constants", JSONObject().apply {
                        edit.expression.references.filter(constants::has).forEach { put(it, constants.get(it)) }
                    })
                    put("expression", edit.expression.json)
                }
                is Edit.Due -> applyDue(draft(edit.part), edit)
                is Edit.Idea -> applyIdea(draft(edit.part), edit)
                is Edit.Lifecycle -> if (edit.retired) draft(edit.part).retired.add(edit.slot)
                    else draft(edit.part).retired.remove(edit.slot)
            }
        }
        val configs = drafts.values.mapNotNull { draft ->
            draft.changedConfig()?.let { draft.original.part.position to it }
        }.toMap()
        requireParts(configs.isNotEmpty() || title != previous.title || description != previous.description, "$.operations", "an actual change")
        val composition = checkNotNull(previous.composition)
        val next = composition.resealNativeEdit(previous, configs, title, description, observer)
        val nextState = next.validateState(state.toString())
        val display = checkNotNull(next.composition).projectState(nextState, clock)
        next.validateUi(next.ui, next.validateState(display.toString()))
        return NativeEditResult(next.json, nextState.toString())
    }

    /** Compare the authoritative base and prove the candidate is the exact result of the accepted plan. */
    fun validateCommit(rawPlan: String, candidate: NativeEditResult, currentBase: NativeEditBase, currentSpec: ToolSpec,
        currentState: String, currentClock: PartClockSample) {
        requireParts(currentBase == base && currentSpec.json == previous.json &&
            currentSpec.validateState(currentState).toString() == durable, "$.edit", "the original saved revision and data")
        requireParts(currentClock.localDate == clock.localDate, "$.edit", "the original local day")
        requireParts(candidate == apply(rawPlan), "$.edit", "only the requested definition and state changes")
    }

    private fun parse(raw: String): List<Edit> {
        val root = partsObject(raw, "$")
        root.requireKeys("$", setOf("token", "operations"))
        requireParts(root.opt("token") == token, "$.token", "this edit request's token")
        val targets = mutableSetOf<Pair<String, String>>()
        fun unique(part: String, field: String, path: String) =
            requireParts(targets.add(part to field), path, "one edit per target")
        return root.items("operations", 1..ToolValueSchema.ARRAY_MAX_ITEMS, "$.operations").mapIndexed { index, value ->
            val path = "$.operations[$index]"
            val row = value as? JSONObject ?: throw PartsContractFailure(path, "an edit operation")
            val op = row.opt("op") as? String ?: throw PartsContractFailure("$path.op", "a supported native edit operation")
            if (op == "update_app") {
                row.requireKeys(path, setOf("op"), setOf("title", "description"))
                unique("", "app", path)
                requireParts(row.has("title") || row.has("description"), path, "an explicit title or description")
                return@mapIndexed Edit.App(row.optional("title") { planText(it, "$path.title", 100) },
                    row.optional("description") { planText(it, "$path.description", 500, allowEmpty = true) })
            }
            val part = parts.firstOrNull { it.part.alias == row.opt("part") }
                ?: throw PartsContractFailure("$path.part", "an offered part alias")
            val required = setOf("op", "part")
            fun slot(allowRetired: Boolean): Int {
                val slot = (0 until part.items.length()).firstOrNull { slotAlias(it) == row.opt("slot") }
                    ?: throw PartsContractFailure("$path.slot", "an offered slot alias")
                requireParts(allowRetired || slot !in part.metadata.retiredSlots, "$path.slot", "an active slot")
                unique(part.part.alias, slotAlias(slot), path)
                return slot
            }
            when (op) {
                "rename_part" -> {
                    row.requireKeys(path, required + "label")
                    unique(part.part.alias, "label", path)
                    Edit.Rename(part, planText(row.opt("label"), "$path.label", 100))
                }
                "set_due_window" -> {
                    row.requireKeys(path, required + "days")
                    requireParts(part.part.kind == NativeEditKind.DUE, "$path.part", "a due part")
                    unique(part.part.alias, "window", path)
                    Edit.Window(part, boundedInt(row.opt("days"), 0..PLAN_NUMBER_LIMIT, "$path.days"))
                }
                "set_figure_expression" -> {
                    row.requireKeys(path, required + "expression")
                    requireParts(part.part.kind == NativeEditKind.FIGURE, "$path.part", "a numeric figure")
                    unique(part.part.alias, "expression", path)
                    val expression = NumberExpression.parse(row.opt("expression"), "$path.expression")
                    val old = NumberExpression.parse(part.config.getJSONObject("expression"), "$path.expression")
                    requireParts(old.references.containsAll(expression.references), "$path.expression", "only existing value references")
                    Edit.Figure(part, expression)
                }
                "update_due", "append_due" -> {
                    val updating = op == "update_due"
                    val dateFields = setOf("date", "offsetDays")
                    val fields = dateFields + setOf("name", "repeatDays")
                    if (updating) row.requireKeys(path, required + "slot", fields)
                    else row.requireKeys(path, required + "name", dateFields + "repeatDays")
                    requireParts(part.part.kind == NativeEditKind.DUE, "$path.part", "a due part")
                    requireParts(fields.any(row::has), path, "an explicit due item change")
                    val dateCount = dateFields.count(row::has)
                    requireParts(dateCount == 1 || (updating && dateCount == 0), path, "one due date form when a date is specified")
                    val date = row.optional("date") { planDate(it, "$path.date") } ?: row.optional("offsetDays") {
                        val at = "$path.offsetDays"
                        dueDate(clock.localDate, boundedInt(it, -PLAN_NUMBER_LIMIT..PLAN_NUMBER_LIMIT, at), at)
                    }
                    Edit.Due(part, if (updating) slot(false) else null,
                        row.optional("name") { planText(it, "$path.name", 100) },
                        date,
                        row.optional("repeatDays") { boundedInt(it, 0..PLAN_NUMBER_LIMIT, "$path.repeatDays") })
                }
                "update_idea", "append_idea" -> {
                    val updating = op == "update_idea"
                    val fields = setOf("name", "minutes", "effort")
                    if (updating) row.requireKeys(path, required + "slot", fields)
                    else row.requireKeys(path, required + fields)
                    requireParts(part.part.kind == NativeEditKind.IDEAS, "$path.part", "an idea part")
                    requireParts(fields.any(row::has), path, "an explicit idea change")
                    Edit.Idea(part, if (updating) slot(false) else null,
                        row.optional("name") { planText(it, "$path.name", 100) },
                        row.optional("minutes") { boundedInt(it, 1..PLAN_NUMBER_LIMIT, "$path.minutes") },
                        row.optional("effort") { rawEffort ->
                            IdeaEffort.entries.firstOrNull { it.wire == rawEffort }
                                ?: throw PartsContractFailure("$path.effort", "low, medium, or high")
                        })
                }
                "retire", "restore" -> {
                    row.requireKeys(path, required + "slot")
                    val retiring = op == "retire"
                    val slot = slot(!retiring)
                    requireParts(retiring || slot in part.metadata.retiredSlots, "$path.slot", "a retired slot")
                    Edit.Lifecycle(part, slot, retiring)
                }
                else -> throw PartsContractFailure("$path.op", "a supported native edit operation")
            }
        }
    }

    private fun applyDue(draft: PartDraft, edit: Edit.Due) {
        val rows = checkNotNull(draft.dueRows)
        if (edit.slot == null) {
            val name = checkNotNull(edit.name)
            val date = checkNotNull(edit.date).toString()
            val repeat = edit.repeatDays ?: 0
            val item = JSONObject().put("name", name).put("date", date)
            if (repeat > 0) item.put("repeatDays", repeat)
            draft.addItem(item)
            rows.put(initialDueSlot(rows.length(), name, date, repeat))
        } else {
            val item = draft.items.getJSONObject(edit.slot)
            val live = rows.getJSONObject(edit.slot)
            edit.name?.let { item.put("name", it); live.put("name", it) }
            edit.date?.toString()?.let { item.put("date", it); live.put("date", it) }
            edit.repeatDays?.let {
                if (it == 0) item.remove("repeatDays") else item.put("repeatDays", it)
                live.put("repeatDays", it)
            }
        }
    }

    private fun applyIdea(draft: PartDraft, edit: Edit.Idea) {
        val item = if (edit.slot == null) JSONObject() else draft.items.getJSONObject(edit.slot)
        edit.name?.let { item.put("name", it) }
        edit.minutes?.let { item.put("minutes", it) }
        edit.effort?.let { item.put("effort", it.wire) }
        if (edit.slot == null) draft.addItem(item)
    }

    companion object {
        val providerGuidance = """
            Return one JSON object with token and operations. Echo the supplied token.
            Use only the offered part aliases and slot aliases. Omit any edit that the user did not specify.
            If a target or required value is ambiguous, return an empty operations array.
            Each operation object has op and exactly the fields below.
            update_app: title and/or description.
            rename_part: part, label.
            set_figure_expression: part, expression. Use the current figure's numeric expression format and existing references only.
            set_due_window: part, days.
            update_due: part, slot, and one or more of name, date or offsetDays, and repeatDays.
            append_due: part, name, either date or offsetDays, and optional repeatDays.
            update_idea: part, slot, and one or more of name, minutes, effort.
            append_idea: part, name, minutes, effort.
            retire or restore: part, slot. Retirement keeps the slot and its history. Restore requires a retired slot.
            Use date for an explicit ISO calendar date. Use offsetDays for a whole number of days from today.
            For example, use 0 for today, 1 for tomorrow, and -1 for yesterday. The host resolves the date.
            Never supply both date and offsetDays. Do not infer dates from hidden data or from the current due date.
            Days and minutes are whole numbers. Repeat days of zero disable repetition.
            Effort is low, medium, or high. Supply at most one edit for an existing slot.
        """.trimIndent()

        fun begin(base: NativeEditBase, spec: ToolSpec, state: String, clock: PartClockSample): StandaloneNativeEdit {
            requireParts(spec.isSealedStandalone, "$.composition", "a sealed standalone composition")
            val original = ToolSpec.parsePersisted(spec.json)
            val composition = checkNotNull(original.composition)
            val parts = composition.editableParts().map(::PartDefinition)
            requireParts(parts.isNotEmpty(), "$.composition", "an editable native part")
            val durable = original.validateState(state)
            parts.forEach { it.checkDurable(durable) }
            return StandaloneNativeEdit(base, original, durable.toString(), clock, parts, UUID.randomUUID().toString())
        }
    }
}

private fun boundedInt(value: Any?, range: IntRange, path: String): Int {
    requireParts(value is Int && value in range, path, "an integer from ${range.first} to ${range.last}")
    return value as Int
}

private fun slotAlias(index: Int) = "s${index + 1}"

private fun <T> JSONObject.optional(field: String, read: (Any?) -> T): T? =
    if (has(field)) read(opt(field)) else null
