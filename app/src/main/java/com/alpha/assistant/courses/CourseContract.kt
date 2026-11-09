package com.alpha.assistant.courses

import com.alpha.assistant.sandbox.SandboxContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

/** A conversation reference can outlive the conversation. The course owns its learning data. */
data class CourseSetup(
    val topic: String,
    val level: String,
    val goal: String,
    val originatingConversationId: String? = null,
)

data class CourseOutline(val lessons: List<CourseLessonPlan>)

data class CourseLessonPlan(val id: String, val title: String, val slides: List<CourseSlidePlan>)

data class CourseSlidePlan(val id: String, val kind: CourseSlideKind, val goal: String)

enum class CourseSlideKind {
    EXPLANATION, EXAMPLE, CHOICE, FILL_IN, ORDER, CHECK, SUMMARY;

    internal val allowsScene: Boolean get() = this == EXPLANATION || this == EXAMPLE

    internal val requiresAnswer: Boolean get() = this == CHOICE || this == FILL_IN || this == ORDER
}

data class CourseProgress(
    val completedSlides: Int,
    val totalSlides: Int,
    val currentLessonId: String?,
    val currentSlideId: String?,
) {
    val isComplete: Boolean get() = completedSlides == totalSlides
}

data class CourseSnapshot(
    val id: String,
    val setup: CourseSetup,
    val revision: Long,
    val outlineRevision: Long,
    val outline: CourseOutline,
    val progress: CourseProgress,
    val generation: CourseGenerationOperation?,
    val generationFailure: CourseGenerationFailure?,
)

/** The expected revision identifies the course state after the store accepts this operation. */
data class CourseGenerationOperation(
    val id: String,
    val courseId: String,
    val slideId: String,
    val expectedRevision: Long,
)

enum class CourseGenerationFailure { UNAVAILABLE, INVALID_OUTPUT, INTERRUPTED }

data class CourseSourceReference(val title: String, val url: String)

/** The generation boundary supplies the ToolSpec, renderer data, and references to sources it used. */
data class CourseSlideContent(
    val definitionJson: String,
    val uiJson: String,
    val stateJson: String,
    val sources: List<CourseSourceReference> = emptyList(),
    val adaptationNote: String? = null,
    val narration: CourseNarration? = null,
)

data class CourseSlide(val id: String, val outlineRevision: Long, val content: CourseSlideContent)

sealed interface CourseAnswer {
    data class Choice(val optionId: String) : CourseAnswer
    data class FillIn(val text: String) : CourseAnswer
    data class Order(val optionIds: List<String>) : CourseAnswer
}

/** The player supplies the graded result, validated state, and any UI update from one exercise action. */
data class CourseAnswerSubmission(
    val operationId: String,
    val expectedRevision: Long,
    val slideId: String,
    val answer: CourseAnswer,
    val correct: Boolean,
    val resultingStateJson: String,
    val resultingUiJson: String? = null,
)

/** Sequence is a storage cursor for ordered attempts. Pass it to answers() to read the next page. */
data class CourseAnswerAttempt(
    val sequence: Long,
    val operationId: String,
    val slideId: String,
    val answer: CourseAnswer,
    val correct: Boolean,
)

enum class CourseRejection {
    COURSE_NOT_FOUND,
    STALE_REVISION,
    STALE_GENERATION,
    SLIDE_NOT_REACHED,
    SLIDE_NOT_GENERATED,
    SLIDE_ALREADY_GENERATED,
    DUPLICATE_ANSWER,
    ANSWER_REQUIRED,
    ANSWER_TYPE_MISMATCH,
    PROTECTED_PROGRESS,
}

class CourseStoreException(val reason: CourseRejection) : IllegalStateException(reason.name)

internal data class CourseStep(val lessonId: String, val lessonTitle: String, val slide: CourseSlidePlan)

internal data class CourseNextRequest(val courseId: String, val revision: Long, val visibleSlideId: String)

internal data class CourseNextTarget(val index: Int, val step: CourseStep)

internal fun CourseSnapshot.nextTarget(visibleSlideId: String): CourseNextTarget? {
    val steps = outline.steps()
    val position = progress.completedSlides
    val reached = steps.getOrNull(position)
    val previous = steps.getOrNull(position - 1)
    val index = when {
        reached?.slide?.id == visibleSlideId && !reached.slide.kind.requiresAnswer -> position + 1
        previous?.slide?.id == visibleSlideId && previous.slide.kind.requiresAnswer -> position
        else -> throw CourseStoreException(CourseRejection.SLIDE_NOT_REACHED)
    }
    return steps.getOrNull(index)?.let { CourseNextTarget(index, it) }
}

internal fun CourseOutline.steps(): List<CourseStep> = lessons.flatMap { lesson ->
    lesson.slides.map { CourseStep(lesson.id, lesson.title, it) }
}

internal fun CourseSetup.validate() {
    courseText(topic, 256, "Topic")
    courseText(level, 128, "Level")
    courseText(goal, 1024, "Course goal")
    originatingConversationId?.let { courseText(it, 128, "Conversation ID") }
}

internal fun CourseOutline.encode(): String {
    require(lessons.size in 1..50) { "A course needs 1 to 50 lessons." }
    val lessonIds = mutableSetOf<String>()
    val slideIds = mutableSetOf<String>()
    val encoded = JSONArray(lessons.map { lesson ->
        courseText(lesson.id, 128, "Lesson ID")
        require(lessonIds.add(lesson.id)) { "Lesson IDs must be unique within a course." }
        courseText(lesson.title, 256, "Lesson title")
        require(lesson.slides.size in 1..20) { "A lesson needs 1 to 20 slides." }
        val slides = JSONArray(lesson.slides.map { slide ->
            courseText(slide.id, 128, "Slide ID")
            require(slideIds.add(slide.id)) { "Slide IDs must be unique within a course." }
            courseText(slide.goal, 512, "Slide goal")
            JSONObject().put("id", slide.id).put("kind", slide.kind.name).put("goal", slide.goal)
        })
        JSONObject().put("id", lesson.id).put("title", lesson.title).put("slides", slides)
    }).toString()
    require(encoded.toByteArray().size <= 192 * 1024) { "The outline exceeds 192 KiB." }
    return encoded
}

internal fun decodeCourseOutline(encoded: String): CourseOutline = CourseOutline(JSONArray(encoded).mapObjects { lesson ->
    CourseLessonPlan(lesson.getString("id"), lesson.getString("title"), lesson.getJSONArray("slides").mapObjects { slide ->
        CourseSlidePlan(slide.getString("id"), CourseSlideKind.valueOf(slide.getString("kind")), slide.getString("goal"))
    })
})

internal fun CourseSlideContent.encodeSources(): String {
    validateCourseJson(definitionJson, 65536, "Slide definition")
    validateCourseUi(uiJson)
    validateCourseState(stateJson)
    adaptationNote?.let { courseText(it, 1024, "Adaptation note") }
    require(sources.size <= 20) { "A slide supports at most 20 source references." }
    return JSONArray(sources.map { reference ->
        courseText(reference.title, 512, "Source title")
        courseText(reference.url, 2048, "Source URL")
        val uri = try {
            URI(reference.url)
        } catch (failure: URISyntaxException) {
            throw IllegalArgumentException("Source URL is invalid.", failure)
        }
        require(uri.scheme?.lowercase() in setOf("https", "http") && uri.host != null && uri.userInfo == null) {
            "A source needs an HTTP or HTTPS URL without user information."
        }
        JSONObject().put("title", reference.title).put("url", reference.url)
    }).toString()
}

internal fun decodeCourseSources(encoded: String): List<CourseSourceReference> = JSONArray(encoded).mapObjects {
    CourseSourceReference(it.getString("title"), it.getString("url"))
}

internal val CourseAnswer.kind: CourseSlideKind get() = when (this) {
    is CourseAnswer.Choice -> CourseSlideKind.CHOICE
    is CourseAnswer.FillIn -> CourseSlideKind.FILL_IN
    is CourseAnswer.Order -> CourseSlideKind.ORDER
}

internal fun CourseAnswer.encode(): String = when (this) {
    is CourseAnswer.Choice -> optionId.also { courseText(it, 128, "Answer option ID") }
    is CourseAnswer.FillIn -> text.also { require(it.length <= 4096) { "An answer supports at most 4096 characters." } }
    is CourseAnswer.Order -> {
        require(optionIds.size in 1..50 && optionIds.toSet().size == optionIds.size) {
            "An order answer needs 1 to 50 distinct option IDs."
        }
        optionIds.forEach { courseText(it, 128, "Answer option ID") }
        JSONArray(optionIds).toString()
    }
}

internal fun decodeCourseAnswer(kind: CourseSlideKind, encoded: String): CourseAnswer = when (kind) {
    CourseSlideKind.CHOICE -> CourseAnswer.Choice(encoded)
    CourseSlideKind.FILL_IN -> CourseAnswer.FillIn(encoded)
    CourseSlideKind.ORDER -> {
        val options = JSONArray(encoded)
        CourseAnswer.Order(List(options.length()) { options.getString(it) })
    }
    else -> error("Unknown course answer type.")
}

internal fun validateCourseState(json: String) = validateCourseJson(json, 49152, "Slide state")
internal fun validateCourseUi(json: String) = validateCourseJson(json, 32768, "Slide UI")

internal fun courseText(value: String, maxLength: Int, field: String) {
    require(value.isNotBlank() && value.length <= maxLength) { "$field needs 1 to $maxLength characters." }
}

private fun validateCourseJson(json: String, maxBytes: Int, field: String) {
    require(json.toByteArray().size <= maxBytes) { "$field exceeds $maxBytes bytes." }
    SandboxContract.objectFrom(json)
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }
