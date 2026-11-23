package com.alpha.assistant.tools

import org.json.JSONArray

/**
 * The narration of a stored template file (design 8.1, plan "one scene" decisions 2 and 3). [sentences] maps a step index from 1 to its
 * sentence, and [say] is the one sentence of a slider, a choice, or a scene with no control. [stepped] is true for a steps control and for
 * the parts control of a diagram (block 2 plan, G3).
 */
internal data class SceneNarration(val stepped: Boolean, val sentences: Map<Int, String>, val say: String?) {
    fun sentence(index: Int): String? = sentences[index]

    companion object {
        private val STEPS = Regex("""^control\s*=\s*(?:steps|parts)\(""", RegexOption.MULTILINE)
        private val LINE = Regex("""^say([1-9]\d*)? = (".*")[ \t]*$""", RegexOption.MULTILINE)

        /** The kit keeps each narration line as `sayK = "<JSON string>"`, so a JSON array around that literal reads it back. */
        fun of(appFile: String?): SceneNarration {
            val text = appFile.orEmpty()
            val lines = LINE.findAll(text).mapNotNull { match ->
                runCatching { JSONArray("[" + match.groupValues[2] + "]").getString(0) }.getOrNull()?.let { match.groupValues[1] to it }
            }.toList()
            return SceneNarration(STEPS.containsMatchIn(text), lines.filter { it.first.isNotEmpty() }.associate { it.first.toInt() to it.second },
                lines.firstOrNull { it.first.isEmpty() }?.second)
        }
    }
}
