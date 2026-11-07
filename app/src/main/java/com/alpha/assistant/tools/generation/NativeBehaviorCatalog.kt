package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.generation.GenerationBehavior.Binding
import com.alpha.assistant.tools.generation.GenerationBehavior.Kind
import com.alpha.assistant.tools.generation.NativeObservation.*
import org.json.JSONObject

internal enum class NativeObservation(val member: String, val description: String, val kind: Kind = Kind.VALUE) {
    VALUE("value", "displayed value"),
    COMPLETE("complete", "visible completion message"),
    RECORDS("records", "number of records", Kind.COLLECTION),
    TODAY("today", "formatted total for today"),
    COUNT("count", "formatted entry count for today"),
    YESTERDAY("yesterday", "formatted total for yesterday when visible"),
    HISTORY("history", "number of history rows", Kind.COLLECTION),
    BALANCE1("balance1", "first team balance"),
    BALANCE2("balance2", "second team balance"),
    DENIED("denied", "visible message when redemption is denied"),
    LATEST("latest", "latest reading"),
    STATUS("status", "status of latest reading"),
    END("end", "formatted end time"),
    REMAINING("remaining", "remaining minutes"),
    STOPS("stops", "number of stops", Kind.COLLECTION),
    OVERDUE("overdue", "number overdue"),
    UPCOMING("upcoming", "number upcoming"),
    ITEMS("items", "number of visible items", Kind.COLLECTION),
    AVAILABLE("available", "number of available ideas"),
    COMPLETED("completed", "number completed today"),
    IDEAS("ideas", "number of displayed ideas", Kind.COLLECTION),
    BODY("body", "visible body text"),
    CORRECT("correct", "visible correct answer message"),
    INCORRECT("incorrect", "visible incorrect answer message")
}

internal class NativeBehaviorTargets(
    val actions: Map<String, String>,
    val observations: Map<NativeObservation, List<Int>>,
    private val primary: String?
) {
    fun binding(slot: GenerationBehavior.Slot): Binding? {
        val member = slot.member
        if (slot.kind == Kind.ACTION) return (if (member == null) primary else actions[member])?.let(Binding::Id)
        return observations.entries.firstOrNull { (target) -> target.kind == slot.kind && target.member == member }
            ?.let { Binding.Ui(it.value, native = true) }
    }
}

internal object NativeBehaviorCatalog {
    private data class Family(
        val planType: String,
        val actions: List<String> = emptyList(),
        val observations: List<NativeObservation>,
        val detail: String = ""
    )

    private val input = Family("inputs", listOf("edit"), listOf(VALUE))
    private val families = mapOf(
        "string_input" to input, "number_input" to input, "boolean_input" to input,
        "counter" to Family("counter", listOf("add", "reset"), listOf(VALUE)),
        "numeric_figure" to Family("figure", observations = listOf(VALUE)),
        "notes" to Family("entries without fields", listOf("edit", "add", "remove"), listOf(RECORDS)),
        "record_log" to Family("entries with fields", listOf("edit_1", "edit_2", "edit_3", "edit_4", "add", "remove"),
            listOf(RECORDS), "Edit members follow the declared fields in order."),
        "quick_log" to Family("quick_log", listOf("add", "add_alternate", "undo", "remove"), listOf(TODAY, COUNT, YESTERDAY, HISTORY),
            "Use add_alternate only with alternateAmount."),
        "points_ledger" to Family("points_ledger", listOf("select", "post1", "post2", "post3", "undo"),
            listOf(BALANCE1, BALANCE2, DENIED, HISTORY), "Select team 1 or 2. post1 and post2 credit points. post3 redeems points."),
        "itinerary" to Family("itinerary", listOf("start", "visit"), listOf(END, STOPS),
            "For visit use a row payload with a stop key such as stop1 and a Boolean value."),
        "editable_itinerary" to Family("editable_itinerary", listOf("start", "name", "minutes", "add", "rename", "resize", "remove"),
            listOf(END, REMAINING, STOPS), "For rename, resize and remove use a row payload with a stop key such as stop1."),
        "reading_status" to Family("reading_status", listOf("edit", "add", "remove"), listOf(LATEST, STATUS, HISTORY)),
        "due_items" to Family("due_items", listOf("complete"), listOf(OVERDUE, UPCOMING, ITEMS),
            "Complete takes the current occurrence key. The initial key of the first item is due1@0."),
        "idea_deck" to Family("idea_deck", listOf("limit", "effort", "cycle", "complete"), listOf(AVAILABLE, COMPLETED, IDEAS)),
        "countdown" to Family("countdown", listOf("start", "pause", "reset"), listOf(VALUE, COMPLETE)),
        "stopwatch" to Family("stopwatch", listOf("start", "pause", "reset"), listOf(VALUE)),
        "information" to Family("content, explanation, example, summary", observations = listOf(BODY)),
        "choice_exercise" to Family("choice_exercise", listOf("select"), listOf(CORRECT, INCORRECT))
    )

    val guidance = families.values.distinct().joinToString("\n", prefix =
        """Native behavior members by plan type.
            For a row payload use value {"key":"current row key","value":scalar or null}.
        """.trimIndent() + "\n") { family ->
        buildList {
            add("${family.planType}:")
            if (family.actions.isNotEmpty()) add("actions ${family.actions.joinToString()}.")
            add(family.observations.joinToString { "${it.kind.wire} ${it.member} (${it.description})" } + ".")
            if (family.detail.isNotEmpty()) add(family.detail)
        }.joinToString(" ")
    }

    fun targets(part: PartCandidate, needId: String): NativeBehaviorTargets {
        // Course-player exercises have no generation-time behavior slots.
        val family = families[part.id] ?: return NativeBehaviorTargets(emptyMap(), emptyMap(), null)
        val instance = partInstanceId(needId)
        val actions = family.actions.mapNotNull { member ->
            val id = "${instance}_$member"
            id.takeIf(part.fragment.handlers::containsKey)?.let { member to it }
        }.toMap()
        check(actions.values.toSet() == part.fragment.handlers.keys)
        check(part.observationPaths.keys == family.observations.toSet())
        val primary = part.behaviorActionId ?: part.primaryActionId
        check(primary == null || primary in actions.values)
        return NativeBehaviorTargets(actions, part.observationPaths, primary)
    }
}

internal fun PartCandidate.behaviorTargets(needId: String): NativeBehaviorTargets = NativeBehaviorCatalog.targets(this, needId)

internal fun nativeObservationPaths(
    root: JSONObject,
    vararg observations: Pair<NativeObservation, JSONObject>
): Map<NativeObservation, List<Int>> {
    val paths = mutableMapOf<NativeObservation, List<Int>>()
    fun visit(node: JSONObject, path: List<Int>) {
        for ((target, observation) in observations) {
            if (node === observation) check(paths.put(target, path) == null)
        }
        val children = node.optJSONArray("children") ?: return
        for (index in 0 until children.length()) visit(children.getJSONObject(index), path + index)
    }
    visit(root, emptyList())
    check(paths.size == observations.size)
    return paths
}
