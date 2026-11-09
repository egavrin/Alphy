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
    private val writer: Boolean,
    private val refinement: Boolean = false,
    private val protectedUi: Boolean = false,
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
        PROTECTED_UI("protected_ui", "The saved layout cannot be replaced or removed. Use only the layout edit described in the patch instructions."),
    }

    class Rejected(val problem: Problem) : IllegalArgumentException() {
        val feedback get() = "The previous patch could not apply (${problem.wire}). ${problem.guidance}"
    }

    data class Request(val mode: Mode, val repairNumber: Int, val instructions: String, val base: String?)
    data class Applied(val text: String, val changes: Int, val sourceEdits: Int)
    private data class Candidate(val text: String, val json: JsonObject)
    private data class SourceEdit(val start: Int, val end: Int, val replacement: String)

    private val maxBytes = if (writer) ToolSpec.MAX_DEFINITION_BYTES else SandboxContract.MAX_BYTES
    private var base: Candidate? = null
    private var prependBase: JsonObject? = null
    var prependedText: String? = null
        private set
    private val seen = mutableListOf<JsonObject>()
    private var rewritten = false

    fun retain(raw: String) {
        val json = strictObject(raw, maxBytes) ?: return
        if (!writer && json.keys.none { it in editableFields }) return
        if (writer) {
            if (targetField == null && ("fill" in json || "holes" in json)) return
            if (targetField != null && (json.keys.any { it !in mixedKeys } ||
                    json["holes"] != expectedHoles?.let { JsonArray(it.map(::JsonPrimitive)) })) return
            val definition = if (targetField == null) json else json[targetField] as? JsonObject ?: return
            if (string(definition["source"]).isNullOrBlank()) return
        }
        base = Candidate(raw, json)
        seen += json
    }

    fun firstRefinement(): Request = patch(0, "Apply the requested edit to the current saved definition.", checkNotNull(base))

    fun testPatch(number: Int, feedback: String): Request? = base?.let { patch(number, feedback, it) }

    fun next(feedback: String, rewriteInstructions: String, claim: () -> Int?): Request? {
        val candidate = base
        if (candidate == null && (rewritten || protectedUi)) return null
        val number = claim() ?: return null
        if (candidate == null || refinement && !protectedUi && number == 3) {
            rewritten = true
            return Request(Mode.REWRITE, number, rewriteInstructions, null)
        }
        return patch(number, feedback, candidate)
    }

    private fun patch(number: Int, feedback: String, candidate: Candidate): Request {
        val allowedFields = if (prependBase != null) editableFields + "prependText" else editableFields
        val structure = "Use fields, a JSON object, to set named fields in ${targetField ?: "the candidate root"}. " +
            "Allowed fields: ${allowedFields.joinToString()}. " +
            "Use removeFields, an array of strings, to delete existing fields. Do not set and remove the same field."
        val source = if (writer) {
            " Use sourceEdits, an array of objects, for JavaScript. Each object has exactly search and replace strings. " +
                "Copy a short, unique search fragment exactly from the decoded source. Use nonoverlapping edits. " +
                "Keep source outside fields and removeFields. " +
                "Preserve native ownership and hole order."
        } else ""
        val layout = when {
            prependBase != null ->
                " The host keeps the existing UI private. To add one line at the top, put prependText inside fields, as in {\"fields\":{\"prependText\":\"Status ready\"}}. " +
                    "Use the exact visible text from the request (1 to 300 characters). " +
                    "The host inserts one text node and keeps every existing control and section. Omit ui from fields and removeFields. " +
                    "If JavaScript returns its own UI, use sourceEdits to insert the same text as the first child of the root column or its first section. Keep all existing children."
            protectedUi -> " The host keeps the existing UI private. Layout edits are unavailable for this app. Omit ui from fields and removeFields."
            else -> ""
        }
        return Request(Mode.PATCH, number,
            "$feedback\nReturn one JSON patch object only. This replaces the earlier full-output instruction. " +
                "$structure$source$layout Make at least one change. Do not return the complete candidate.",
            candidate.text)
    }

    fun apply(raw: String, restrictRemovals: Boolean = false): Applied {
        if (byteSize(raw) > maxBytes) reject(Problem.SIZE)
        val patch = strictObject(raw, maxBytes) ?: reject()
        val sections = buildSet {
            addAll(planPatchKeys)
            if (writer) add("sourceEdits")
        }
        if (patch.keys.any { it !in sections }) reject()
        val values = patch["fields"]?.let { it as? JsonObject ?: reject() }.orEmpty()
        val removals = patch["removeFields"]?.let { value ->
            (value as? JsonArray ?: reject()).map { string(it) ?: reject() }
        }.orEmpty()
        val edits = patch["sourceEdits"]?.let { it as? JsonArray ?: reject() }.orEmpty()
        if (protectedUi && ("ui" in values || "ui" in removals)) reject(Problem.PROTECTED_UI)
        val topText = values["prependText"]?.let { string(it)?.takeIf(String::isNotBlank) ?: reject() }
        val layout = topText?.let(::prependText)
        val fields = if (layout == null) values else values - "prependText"
        val changes = values.size + removals.size + edits.size
        if (changes !in 1..32 || edits.size > 16 || fields.keys.any { it !in editableFields } ||
            removals.toSet().size != removals.size || removals.any {
                it in fields || writer && it == "source" || restrictRemovals && it !in editableFields
            }) reject()

        val previous = checkNotNull(base).json
        val original = if (targetField == null) previous else previous.getValue(targetField) as JsonObject
        if (removals.any { it !in original }) reject()
        val definition = (original - removals.toSet() + fields).toMutableMap()
        if (layout != null) definition["ui"] = layout
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
        if (topText != null) prependedText = topText
        seen += json
        return Applied(text, changes, edits.size)
    }

    private fun prependText(text: String): JsonObject {
        val original = prependBase ?: reject(Problem.PROTECTED_UI)
        val node = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))
        // Use the saved layout so that a retry replaces its own addition.
        val children = original.getValue("children") as JsonArray
        return JsonObject(original + ("children" to JsonArray(listOf(node) + children)))
    }

    private fun editSource(source: String, edits: List<JsonElement>): String {
        val resolved = edits.map { raw ->
            val edit = raw as? JsonObject ?: reject()
            if (edit.keys != sourceEditKeys) reject()
            val search = string(edit["search"])?.takeIf { it.isNotEmpty() } ?: reject()
            val replace = string(edit["replace"]) ?: reject()
            val start = source.indexOf(search)
            if (start < 0) reject(Problem.MISSING_MATCH)
            if (source.indexOf(search, start + 1) >= 0) reject(Problem.AMBIGUOUS_MATCH)
            SourceEdit(start, start + search.length, replace)
        }.sortedBy { it.start }
        if (resolved.zipWithNext().any { (first, second) -> first.end > second.start }) reject(Problem.OVERLAP)
        return StringBuilder(source).apply {
            for (edit in resolved.asReversed()) replace(edit.start, edit.end, edit.replacement)
        }.toString()
    }

    companion object {
        const val FORMAT_OVERRIDE = "\n\nFor this repair, use the JSON patch format described in the final user turn. " +
            "It replaces the complete output schema above. Keep the original task and ownership rules."

        private val planPatchKeys = setOf("fields", "removeFields")
        private val protectedFields = setOf("source")
        private val sourceEditKeys = setOf("search", "replace")
        private val mixedKeys = setOf("holes", "fill")
        private val planFields = setOf("wholeScreen", "title", "description", "language", "inputs", "constants", "needs", "questions", "literals", "iconId")

        fun plan() = GenerationRepair(planFields, null, null, false)

        fun writer(fields: Set<String>, holes: List<String>?) =
            GenerationRepair(fields - protectedFields, "fill".takeIf { holes != null }, holes, true)

        fun refinement(fields: Set<String>, mixed: Boolean, saved: String,
            protectedUi: Boolean = false, allowPrepend: Boolean = false) =
            GenerationRepair(fields - protectedFields - if (protectedUi) setOf("ui") else emptySet(),
                "fill".takeIf { mixed }, null, true, refinement = true, protectedUi = protectedUi).apply {
                retain(saved)
                if (protectedUi && allowPrepend && !mixed) {
                    prependBase = (base?.json?.get("ui") as? JsonObject)?.takeIf {
                        string(it["type"]) == "column" && it["children"] is JsonArray
                    }
                }
            }

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

        private fun reject(problem: Problem = Problem.SHAPE): Nothing = throw Rejected(problem)
    }
}
