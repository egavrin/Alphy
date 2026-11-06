package com.alpha.assistant.courses

import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import org.json.JSONArray
import org.json.JSONObject

internal data class CourseExerciseOption(val id: String, val label: String)

internal data class CourseExercisePrompt(
    val kind: CourseSlideKind,
    val question: String,
    val options: List<CourseExerciseOption>,
)

/** The player receives the prompt. Only the controller reads the key from the immutable definition. */
internal class CourseExercise private constructor(
    val prompt: CourseExercisePrompt,
    val actionId: String?,
    private val key: List<String>,
) {
    private val options = prompt.options.associateBy { it.id }

    fun grade(answer: CourseAnswer): Boolean = validateCourseOutput(CourseWorkflowError.INVALID_ANSWER) {
        require(answer.kind == prompt.kind)
        answer.encode()
        when (answer) {
            is CourseAnswer.Choice -> {
                require(answer.optionId in options)
                answer.optionId == key.single()
            }
            is CourseAnswer.FillIn -> answer.text.trim() in key
            is CourseAnswer.Order -> {
                require(answer.optionIds.size == options.size && answer.optionIds.toSet() == options.keys)
                answer.optionIds == key
            }
        }
    }

    fun answerContext(answer: CourseAnswer): Any = validateCourseOutput {
        fun option(id: String): JSONObject {
            val item = options[id] ?: courseFailure(CourseWorkflowError.INVALID_OUTPUT)
            return JSONObject().put("id", item.id).put("label", item.label)
        }
        require(answer.kind == prompt.kind)
        when (answer) {
            is CourseAnswer.Choice -> option(answer.optionId)
            is CourseAnswer.FillIn -> answer.text
            is CourseAnswer.Order -> JSONArray(answer.optionIds.map(::option))
        }
    }

    fun action(ui: JSONObject, state: JSONObject, answer: CourseAnswer): JSONObject? {
        val id = actionId ?: return null
        val value = when (answer) {
            is CourseAnswer.Choice -> answer.optionId
            is CourseAnswer.FillIn -> answer.text
            is CourseAnswer.Order -> JSONArray(answer.optionIds)
        }
        validateCourseOutput(CourseWorkflowError.INVALID_ACTION) {
            val node = ToolUiCatalog.validateAction(ui, id, value, state)
            val type = when (prompt.kind) {
                CourseSlideKind.CHOICE -> "singleChoice"
                CourseSlideKind.FILL_IN -> "input"
                CourseSlideKind.ORDER -> "multiChoice"
                else -> error("The slide does not accept an answer.")
            }
            require(node.getString("type") == type && node.getString("action") == id)
            if (type == "input") {
                require(node.optString("keyboard", "text") != "password")
                require(key.all { it.length <= node.optInt("maxLength", 2048) })
            } else {
                val offered = node.getJSONArray("options")
                require(offered.length() == options.size)
                repeat(offered.length()) { index ->
                    val item = offered.getJSONObject(index)
                    require(options[item.get("value")]?.label == item.getString("text"))
                }
            }
        }
        return JSONObject().put("type", "action").put("actionId", id).put("value", value)
    }

    fun validateInitialAction(ui: JSONObject, state: JSONObject) {
        if (actionId == null) return
        val sample = when (prompt.kind) {
            CourseSlideKind.CHOICE -> CourseAnswer.Choice(key.single())
            CourseSlideKind.FILL_IN -> CourseAnswer.FillIn(key.first())
            CourseSlideKind.ORDER -> CourseAnswer.Order(key)
            else -> error("The slide does not accept an answer.")
        }
        action(ui, state, sample)
    }

    companion object {
        fun read(spec: ToolSpec, kind: CourseSlideKind): CourseExercise? = validateCourseOutput {
            val raw = JSONObject(spec.initialState).opt(EXERCISE_FIELD)
            if (!kind.requiresAnswer) {
                require(raw == null)
                return@validateCourseOutput null
            }
            require(raw is String && spec.schema.opt(EXERCISE_FIELD) == "string")
            val data = SandboxContract.objectFrom(raw)
            val kindFields = when (kind) {
                CourseSlideKind.CHOICE -> setOf("options", "correct")
                CourseSlideKind.FILL_IN -> setOf("accepted")
                CourseSlideKind.ORDER -> setOf("options", "correctOrder")
                else -> error("The slide does not accept an answer.")
            }
            val required = kindFields + setOf("kind", "question")
            val fields = data.keys().asSequence().toSet()
            require(fields == required || fields == required + "actionId")
            require(data.get("kind") == kind.name)
            val question = data.text("question", 512)
            val actionId = if (data.has("actionId")) data.text("actionId", 48).also {
                require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
            } else null
            val options = if (kind == CourseSlideKind.FILL_IN) emptyList() else {
                val offered = data.getJSONArray("options")
                require(offered.length() in 2..30)
                List(offered.length()) { index ->
                    val option = offered.getJSONObject(index)
                    require(option.keys().asSequence().toSet() == setOf("id", "label"))
                    CourseExerciseOption(option.text("id", 128), option.text("label", 100))
                }.also {
                    require(it.map { option -> option.id }.distinct().size == it.size)
                    require(it.map { option -> option.label }.distinct().size == it.size)
                }
            }
            val key = when (kind) {
                CourseSlideKind.CHOICE -> listOf(data.text("correct", 128)).also {
                    require(options.any { option -> option.id == it.single() })
                }
                CourseSlideKind.FILL_IN -> stringList(data.getJSONArray("accepted"), 1..20, 2048).also {
                    require(it.all { value -> value.trim() == value })
                }
                CourseSlideKind.ORDER -> stringList(data.getJSONArray("correctOrder"), options.size..options.size, 128).also {
                    require(it.toSet() == options.map { option -> option.id }.toSet())
                }
            }
            CourseExercise(CourseExercisePrompt(kind, question, options), actionId, key)
        }

        private fun JSONObject.text(name: String, maxLength: Int): String =
            (get(name) as? String ?: error("Expected a string.")).also { courseText(it, maxLength, name) }
    }
}
