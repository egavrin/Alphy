package com.alpha.assistant.courses

import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.agent.AppLessonTarget
import org.json.JSONObject

/** The course rules for one slide, in the form that the app agent loop runs on each version. */
internal fun courseLessonTarget(
    kind: CourseSlideKind, sourceIds: Set<String>, policy: ToolExecutionPolicy, languageHint: String?,
): AppLessonTarget = AppLessonTarget(
    guide = lessonGuide(kind),
    policy = policy,
    checkSlide = { spec, preview ->
        // The exercise format has no actionId, so name that field before the generic format reason.
        val raw = JSONObject(spec.initialState).opt(EXERCISE_FIELD) as? String
        if (raw != null && runCatching { SandboxContract.objectFrom(raw).has("actionId") }.getOrDefault(false)) {
            throw IllegalArgumentException("The host shows the answer controls. Remove actionId from $EXERCISE_FIELD and remove the answer control.")
        }
        val exercise = try {
            CourseExercise.read(spec, kind)
        } catch (_: CourseWorkflowException) {
            throw IllegalArgumentException(if (kind.requiresAnswer)
                "initialState.$EXERCISE_FIELD does not match the $kind exercise format in the course slide rules."
                else "Only CHOICE, FILL_IN, and ORDER slides have $EXERCISE_FIELD. Remove it.")
        }
        try {
            checkedCourseUi(spec, preview.ui, preview.state, exercise, kind)
        } catch (failure: CourseWorkflowException) {
            throw IllegalArgumentException("The slide screen breaks the course rules: ${failure.message}")
        }
    },
    allowedSourceIds = sourceIds,
    scene = kind.allowsScene,
    languageHint = languageHint,
)

private fun lessonGuide(kind: CourseSlideKind): String = buildString {
    append("""
        Course slide rules:
        - Build one slide of a course. The course player shows it, grades answers, and moves between slides.
        - This slide is $kind.
        - Use no capabilities, no collections, no device controls (calendar, medicine, navigation, image, video), and no immersive presentation.
        - Use no actions, row actions, or interactions in the slide, except live inputs on a scene slide.
        - For a picture of one object (a fruit, an animal, the sun, a cloud), put its emoji in a text node with "style":{"emphasis":"headline"} next to its name.
    """.trimIndent())
    append('\n')
    if (!kind.requiresAnswer) append("- Show the words, letters, numbers, and facts that the slide goal names as visible text in the first screen. No input may hide them.\n")
    append(if (kind.allowsScene) "- When the goal needs a diagram or a live model, this slide can use a scene: a canvas with declared surfaces. " +
        "It must draw visible content in the first screen. " +
        "Live inputs (slider, numberInput, toggle, checkbox, button, select, segmented) can change it. Use no answer control on a scene slide."
        else "- This slide cannot use a canvas or surfaces.")
    if (!kind.requiresAnswer) {
        append("\n- Do not add $EXERCISE_FIELD.")
        return@buildString
    }
    val format = when (kind) {
        CourseSlideKind.CHOICE -> """{"kind":"CHOICE","question":"...","options":[{"id":"a","label":"..."},{"id":"b","label":"..."}],"correct":"a"}"""
        CourseSlideKind.FILL_IN -> """{"kind":"FILL_IN","question":"...","accepted":["answer","other accepted spelling"]}"""
        else -> """{"kind":"ORDER","question":"...","options":[{"id":"a","label":"..."},{"id":"b","label":"..."}],"correctOrder":["b","a"]}"""
    }
    append("\n- Put the exercise in initialState.$EXERCISE_FIELD as one JSON string, and declare \"$EXERCISE_FIELD\":\"string\" in stateSchema.")
    append("\n  Exercise format: ").append(format)
    append("\n  Optional fields: \"wrongHint\" (a concrete retry hint) and \"correctFeedback\" (a short explanation), ")
        .append("each at most $COURSE_EXERCISE_FEEDBACK_LIMIT characters.")
    append("\n  The question has at most 512 characters. Options: 2 to 30, with distinct IDs and distinct labels of at most 100 characters.")
    append("\n  Accepted answers: 1 to 20, without spaces at the start or end.")
    append("\n- The host adds the answer control and the Check button. Do not add actionId, an answer control, or a node bound to $EXERCISE_FIELD.")
    append("\n  Show only the instruction and the content that the learner needs to answer. ")
        .append("Do not repeat the option labels or the answer, and do not say how to enter the answer: the host control shows it. ")
        .append("Put a hint in wrongHint, not in the slide: the learner sees it after a wrong answer.")
}
