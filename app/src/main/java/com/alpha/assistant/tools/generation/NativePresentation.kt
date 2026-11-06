package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONArray
import org.json.JSONObject

internal enum class NativePresentationProfile(val wire: String, val guidance: String) {
    FOCUS("focus", "Lead with the primary value or action. Place settings after it."),
    WORKSPACE("workspace", "Lead with the key summary. Group related controls and use compact rows for records."),
    READING("reading", "Lead with the content. Use short section headings in reading order and keep navigation close to the content.")
}

internal enum class NativeCompactSummary(val wire: String) { FOCAL("focal") }

/** A host-owned description of one focal slot in a native column. */
internal class NativePresentation private constructor(
    val profile: NativePresentationProfile,
    val preset: String,
    val focalPosition: Int,
    val primaryActionId: String?,
    val compactSummary: NativeCompactSummary,
    private val childCount: Int,
    private val focalShape: JSONObject,
    private val anchored: Boolean
) {
    fun focalNode(root: JSONObject): JSONObject {
        val children = columnChildren(root)
        requireParts(children.length() == childCount, "$.ui.children", "the existing presentation slots")
        val focal = focalChild(children, focalPosition)
        requireParts(sameToolJson(focalShape, slotShape(focal, anchored)), "$.ui.children[$focalPosition]",
            "the existing focal slot structure")
        requirePrimary(focal, primaryActionId)
        return focal
    }

    fun json(): JSONObject = json(profile, preset, focalPosition, primaryActionId)

    fun sameSelection(other: NativePresentation?): Boolean =
        other != null && profile == other.profile && preset == other.preset &&
            focalPosition == other.focalPosition && primaryActionId == other.primaryActionId &&
            compactSummary == other.compactSummary

    companion object {
        const val FIELD = "nativePresentation"
        private const val PATH = "$.nativePresentation"

        fun json(profile: NativePresentationProfile, preset: String, focalPosition: Int, primaryActionId: String?): JSONObject =
            JSONObject().put("profile", profile.wire).put("preset", preset).put("focalPosition", focalPosition)
                .put("compactSummary", NativeCompactSummary.FOCAL.wire).apply {
                    if (primaryActionId != null) put("primaryActionId", primaryActionId)
                }

        fun parse(recipe: JSONObject, root: JSONObject): NativePresentation {
            recipe.requireKeys(PATH, setOf("profile", "preset", "focalPosition", "compactSummary"), setOf("primaryActionId"))
            val profile = NativePresentationProfile.entries.firstOrNull { it.wire == recipe.opt("profile") }
                ?: throw PartsContractFailure("$PATH.profile", "a supported presentation profile")
            val preset = recipe.opt("preset") as? String
            requireParts(preset in ToolUiCatalog.presets, "$PATH.preset", "a supported native preset")
            val position = recipe.opt("focalPosition")
            val children = columnChildren(root)
            requireParts(position is Int && position in 0 until children.length(), "$PATH.focalPosition", "a top-level UI position")
            val action = if (recipe.has("primaryActionId")) {
                (recipe.opt("primaryActionId") as? String)?.takeIf(ToolValueSchema::name)
                    ?: throw PartsContractFailure("$PATH.primaryActionId", "a valid action ID")
            } else null
            val summary = NativeCompactSummary.FOCAL
            requireParts(recipe.opt("compactSummary") == summary.wire, "$PATH.compactSummary", "the focal summary policy")
            val focal = focalChild(children, position as Int)
            requirePrimary(focal, action)
            val anchored = hasIdentity(focal)
            return NativePresentation(profile, checkNotNull(preset), position, action, summary, children.length(),
                slotShape(focal, anchored), anchored)
        }

        private fun columnChildren(root: JSONObject): JSONArray {
            val children = root.optJSONArray("children")
            requireParts(root.opt("type") == "column" && children != null && children.length() in 1..50,
                "$.ui", "a native column with top-level presentation slots")
            return checkNotNull(children)
        }

        private fun focalChild(children: JSONArray, position: Int): JSONObject =
            children.optJSONObject(position) ?: throw PartsContractFailure("$PATH.focalPosition", "a native UI node")

        private fun requirePrimary(focal: JSONObject, action: String?) {
            if (action == null) return
            fun contains(node: JSONObject): Boolean {
                if (node.opt("type") == "button" && node.opt("action") == action) return true
                val children = node.optJSONArray("children") ?: return false
                return (0 until children.length()).any { index -> children.optJSONObject(index)?.let(::contains) == true }
            }
            requireParts(contains(focal), "$PATH.primaryActionId", "a button action in the focal slot")
        }

        private fun hasIdentity(node: JSONObject): Boolean {
            if (node.has("partInstanceId") || node.has("bind") || node.has("action")) return true
            val children = node.optJSONArray("children") ?: return false
            return (0 until children.length()).any { index -> children.optJSONObject(index)?.let(::hasIdentity) == true }
        }

        private val visualFields = setOf("visible", "enabled", "style", "layout", "semantics")
        private val copyFields = setOf("text", "emptyText")

        private fun slotShape(node: JSONObject, anchored: Boolean): JSONObject {
            val ignored = if (anchored) visualFields + copyFields else visualFields
            fun normalize(value: Any): Any = when (value) {
                is JSONObject -> JSONObject().apply {
                    value.keys().forEach { key -> if (key !in ignored) put(key, normalize(value.get(key))) }
                }
                is JSONArray -> JSONArray().apply { repeat(value.length()) { put(normalize(value.get(it))) } }
                else -> value
            }
            return normalize(node) as JSONObject
        }
    }
}

internal fun sameToolJson(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject -> ToolValueSchema.keys(a) == ToolValueSchema.keys(b) &&
        a.keys().asSequence().all { sameToolJson(a.opt(it), b.opt(it)) }
    a is JSONArray && b is JSONArray -> a.length() == b.length() &&
        (0 until a.length()).all { sameToolJson(a.opt(it), b.opt(it)) }
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    else -> a == b
}
