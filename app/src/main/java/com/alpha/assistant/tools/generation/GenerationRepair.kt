package com.alpha.assistant.tools.generation

import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A repair session for one generation stage. Only a complete local application changes its base. */
internal class GenerationRepair private constructor(
    private val editableFields: Set<String>,
    private val targetField: String?,
    private val expectedHoles: List<String>?,
) {
    enum class Mode(val wire: String) { PATCH("patch"), REWRITE("rewrite") }

    enum class Problem(val wire: String, val guidance: String) {
        SHAPE("shape", "Use only the declared patch fields and value types."),
        MISSING_MATCH("missing_match", "Copy the source search text exactly from the candidate."),
        AMBIGUOUS_MATCH("ambiguous_match", "Include enough context for each source search to match once."),
        OVERLAP("overlap", "Use separate, nonoverlapping source edits."),
        SIZE("size", "Return a smaller patch within the output limit."),
        NO_CHANGE("no_change", "Make a change that addresses the failure."),
        REPEATED_CANDIDATE("repeated_candidate", "The result repeats a previously rejected candidate."),
    }

    class Rejected(val problem: Problem, val path: String? = null) : IllegalArgumentException() {
        val feedback get() = "The previous patch could not apply (${problem.wire}). " +
            (path?.let { "Path: $it. " } ?: "") + problem.guidance
    }

    data class Request(val mode: Mode, val repairNumber: Int, val instructions: String, val base: String?, val bindingsOnly: Boolean = false, val baseVersion: Int = 0)
    data class Applied(val text: String, val changes: Int, val sourceEdits: Int, val fields: List<String>)
    private data class Candidate(val text: String, val json: JsonObject)
    private data class SourceEdit(val start: Int, val end: Int, val replacement: String)

    private val maxBytes = ToolSpec.MAX_DEFINITION_BYTES
    private var base: Candidate? = null
    private val seen = mutableListOf<JsonObject>()
    private var rewritten = false
    private data class Turn(val result: String, var validation: String? = null)
    private val history = mutableListOf<Turn>()
    private val rejectedPatches = mutableSetOf<Pair<Int, JsonObject>>()
    private var repeatedPatch = false

    fun initial(raw: String) {
        retain(raw)
        history += Turn(if (base == null) "Initial response has no usable base." else "Initial response is candidate ${seen.size}.")
    }

    fun record(request: Request, raw: String?, result: String, applied: Applied?, rejected: Rejected?) {
        val outcome = when {
            applied != null -> "applied to candidate ${seen.size}, fields ${applied.fields.joinToString()}, source edits ${applied.sourceEdits}."
            rejected != null -> rejected.feedback
            result == "received" && request.baseVersion != seen.size -> "received candidate ${seen.size}."
            else -> "$result."
        }
        history += Turn("Repair ${request.repairNumber} (${request.mode.wire}) on candidate ${request.baseVersion}: $outcome")
        if (request.mode == Mode.PATCH && rejected != null && raw != null) {
            repeatedPatch = strictObject(raw, maxBytes)?.let { !rejectedPatches.add(request.baseVersion to it) } ?: false
        }
    }

    private fun historyContext() = if (history.isEmpty()) "" else history.joinToString(
        separator = "\n", prefix = "\nRepair history (host results):\n",
    ) { it.result + (it.validation?.let { failure -> " Validation: $failure" } ?: "") }

    fun retain(raw: String) {
        val json = strictObject(raw, maxBytes) ?: return
        if (targetField == null && ("fill" in json || "holes" in json)) return
        if (targetField != null && (json.keys.any { it !in mixedKeys } ||
                json["holes"] != expectedHoles?.let { JsonArray(it.map(::JsonPrimitive)) })) return
        val definition = if (targetField == null) json else json[targetField] as? JsonObject ?: return
        if (string(definition["source"]).isNullOrBlank()) return
        base = Candidate(raw, json)
        seen += json
    }

    fun next(feedback: String, rewriteInstructions: String, patchRejected: Boolean = false, claim: () -> Int?): Request? {
        if (!patchRejected) history.lastOrNull()?.validation = feedback
        if (repeatedPatch) return null
        val candidate = base
        if (candidate == null && rewritten) return null
        val number = claim() ?: return null
        if (candidate == null) {
            rewritten = true
            return Request(Mode.REWRITE, number, rewriteInstructions + historyContext(), null, baseVersion = seen.size)
        }
        return patch(number, feedback, candidate)
    }

    fun correctBindings(feedback: String, claim: () -> Int?): Request? {
        val candidate = base ?: return null
        history.lastOrNull()?.validation = feedback
        val number = claim() ?: return null
        return Request(Mode.PATCH, number, "$feedback${historyContext()}\nReturn one JSON patch object only. " +
            "Set only behaviorBindings inside fields. Keep source, controls, state, and the planned check unchanged.",
            candidate.text, bindingsOnly = true, baseVersion = seen.size)
    }

    private fun patch(number: Int, feedback: String, candidate: Candidate): Request {
        val structure = "Use fields, a JSON object, to set named fields in ${targetField ?: "the candidate root"}. " +
            "Allowed fields: ${editableFields.joinToString()}. " +
            "Use removeFields, an array of strings, to delete existing fields. Do not set and remove the same field."
        val source = " Use sourceEdits, an array of objects, for JavaScript. Each object has exactly search and replace strings. " +
            "Copy a short, unique search fragment exactly from the decoded source. Use nonoverlapping edits. " +
            "Keep source outside fields and removeFields. " +
            "Preserve native ownership and hole order."
        return Request(Mode.PATCH, number,
            "$feedback${historyContext()}\nReturn one JSON patch object only. This replaces the earlier full-output instruction. " +
                "$structure$source Make at least one change. Do not return the complete candidate.",
            candidate.text, baseVersion = seen.size)
    }

    fun apply(raw: String, bindingsOnly: Boolean = false): Applied {
        if (byteSize(raw) > maxBytes) reject(Problem.SIZE)
        val patch = strictObject(raw, maxBytes) ?: reject()
        if (patch.keys.any { it !in patchKeys }) reject(path = "$")
        val fields = patch["fields"]?.let { it as? JsonObject ?: reject(path = "$.fields") }.orEmpty()
        if (bindingsOnly && (patch.keys != setOf("fields") || fields.keys != setOf("behaviorBindings") ||
                fields["behaviorBindings"] !is JsonObject)) reject()
        val removals = patch["removeFields"]?.let { value ->
            (value as? JsonArray ?: reject()).map { string(it) ?: reject() }
        }.orEmpty()
        val edits = patch["sourceEdits"]?.let { it as? JsonArray ?: reject(path = "$.sourceEdits") }.orEmpty()
        val changes = fields.size + removals.size + edits.size
        if (fields.keys.any { it !in editableFields }) reject(path = "$.fields")
        if (changes !in 1..32 || edits.size > 16 ||
            removals.toSet().size != removals.size || removals.any { it in fields || it == "source" }) reject()

        val previous = checkNotNull(base).json
        val original = if (targetField == null) previous else previous.getValue(targetField) as JsonObject
        if (removals.any { it !in original }) reject()
        val definition = (original - removals.toSet() + fields).toMutableMap()
        if (edits.isNotEmpty()) {
            definition["source"] = JsonPrimitive(editSource(checkNotNull(string(original["source"])), edits))
        }
        val result = if (targetField == null) definition else previous.toMutableMap().apply {
            put(targetField, JsonObject(definition))
        }
        val json = JsonObject(result)
        val text = json.toString()
        if (byteSize(text) > maxBytes) reject(Problem.SIZE)
        if (json == previous) reject(Problem.NO_CHANGE)
        if (json in seen) reject(Problem.REPEATED_CANDIDATE)
        base = Candidate(text, json)
        seen += json
        return Applied(text, changes, edits.size, fields.keys.toList() + removals.filter { it in editableFields })
    }

    private fun editSource(source: String, edits: List<JsonElement>): String {
        val resolved = edits.mapIndexed { index, raw ->
            val edit = raw as? JsonObject ?: reject()
            if (edit.keys != sourceEditKeys) reject()
            val search = string(edit["search"])?.takeIf { it.isNotEmpty() } ?: reject()
            val replace = string(edit["replace"]) ?: reject()
            val start = source.indexOf(search)
            if (start < 0) reject(Problem.MISSING_MATCH, "$.sourceEdits[$index].search")
            if (source.indexOf(search, start + 1) >= 0) reject(Problem.AMBIGUOUS_MATCH, "$.sourceEdits[$index].search")
            SourceEdit(start, start + search.length, replace)
        }.sortedBy { it.start }
        if (resolved.zipWithNext().any { (first, second) -> first.end > second.start }) reject(Problem.OVERLAP)
        return StringBuilder(source).apply {
            for (edit in resolved.asReversed()) replace(edit.start, edit.end, edit.replacement)
        }.toString()
    }

    companion object {
        private val patchKeys = setOf("fields", "removeFields", "sourceEdits")
        private val protectedFields = setOf("source")
        private val sourceEditKeys = setOf("search", "replace")
        private val mixedKeys = setOf("holes", "fill")

        fun writer(fields: Set<String>, holes: List<String>?) =
            GenerationRepair(fields - protectedFields, "fill".takeIf { holes != null }, holes)

        fun byteSize(text: String) = text.toByteArray(Charsets.UTF_8).size

        private fun string(value: JsonElement?) =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun strictObject(raw: String, maxBytes: Int): JsonObject? = try {
            SandboxContract.objectFrom(raw, maxBytes)
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }

        private fun reject(problem: Problem = Problem.SHAPE, path: String? = null): Nothing = throw Rejected(problem, path)
    }
}
