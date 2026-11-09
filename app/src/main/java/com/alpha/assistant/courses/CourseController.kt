package com.alpha.assistant.courses

import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Source
import com.alpha.assistant.tools.ToolGenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Course operations never register slides as mini-apps. */
internal class CourseController(
    private val store: CourseStore,
    private val generation: CourseGeneration,
) {
    constructor(store: CourseStore, tools: ToolGenerationService) : this(store, CourseGeneration(tools))

    private val interactions = Mutex()

    fun sceneHost(page: CoursePage.Slide) = generation.sceneHost(store, page)

    fun exercise(courseId: String, expectedRevision: Long, slideId: String): CourseExercisePrompt? {
        val (_, step) = reached(courseId, expectedRevision, slideId)
        val slide = generated(courseId, slideId)
        return CourseExercise.read(parseCourseSpec(slide.content, step.slide.kind), step.slide.kind)?.prompt
    }

    /** Reopening an existing slide returns the saved revision without a provider request. */
    suspend fun generate(
        courseId: String,
        expectedRevision: Long,
        slideId: String,
        key: String,
        session: CloudSession,
        trustedSearchResults: List<Source> = emptyList(),
    ): CourseSnapshot {
        val (course, step) = reached(courseId, expectedRevision, slideId)
        if (store.slide(courseId, slideId) != null) return reached(courseId, expectedRevision, slideId).first
        checkActive(session)
        val input = generation.input(store, course, course.progress.completedSlides, trustedSearchResults)
        checkActive(session)
        val operation = store.beginGeneration(courseId, expectedRevision, slideId)
        val content = try {
            generation.generate(key, input, session, artifactId(courseId, slideId), step.slide.kind).also {
                checkActive(session)
            }
        } catch (cancel: CancellationException) {
            failIfCurrent(operation, CourseGenerationFailure.INTERRUPTED)
            throw cancel
        } catch (failure: CourseWorkflowException) {
            val reason = if (failure.reason == CourseWorkflowError.UNAVAILABLE) CourseGenerationFailure.UNAVAILABLE
                else CourseGenerationFailure.INVALID_OUTPUT
            failIfCurrent(operation, reason)
            throw failure
        }
        return store.commitGeneration(operation, content)
    }

    suspend fun prepareNext(request: CourseNextRequest, key: String, session: CloudSession): CourseSlideContent {
        val course = store.courseAt(request.courseId, request.revision)
        val target = course.nextTarget(request.visibleSlideId) ?: reject(CourseRejection.SLIDE_NOT_REACHED)
        checkActive(session)
        val input = generation.input(store, course, target.index, emptyList())
        store.courseAt(request.courseId, request.revision)
        val step = target.step.slide
        return generation.generate(key, input, session, artifactId(course.id, step.id), step.kind).also {
            checkActive(session)
            store.courseAt(request.courseId, request.revision)
        }
    }

    /** The player supplies an answer and a request ID. The saved definition supplies the grade. */
    suspend fun submitAnswer(
        courseId: String, expectedRevision: Long, slideId: String, operationId: String, answer: CourseAnswer,
    ): CourseSnapshot = interactions.withLock {
        val (_, step) = reached(courseId, expectedRevision, slideId)
        if (store.answerAttempts(courseId).any { it.operationId == operationId }) reject(CourseRejection.DUPLICATE_ANSWER)
        if (step.slide.kind != answer.kind) reject(CourseRejection.ANSWER_TYPE_MISMATCH)
        validateCourseOutput(CourseWorkflowError.INVALID_ANSWER) { courseText(operationId, 128, "Answer operation ID") }

        val submitted = if (answer is CourseAnswer.Order) answer.copy(optionIds = answer.optionIds.toList()) else answer
        val slide = generated(courseId, slideId)
        val spec = parseCourseSpec(slide.content)
        val exercise = checkNotNull(CourseExercise.read(spec, step.slide.kind))
        val correct = exercise.grade(submitted)
        val (ui, state) = checkedCourseUi(spec, slide.content.uiJson, slide.content.stateJson, exercise)
        val event = exercise.action(ui, state, submitted)
        val result = event?.let {
            // A stale action must not enter the sandbox after another writer changes the course.
            reached(courseId, expectedRevision, slideId)
            generation.evaluate(spec, artifactId(courseId, slideId), state.toString(), event, ui.toString())
        }
        val next = result?.let {
            checkedCourseUi(spec, it.ui, it.state, exercise).also { (nextUi, nextState) ->
                if (!correct) exercise.validateInitialAction(nextUi, nextState)
            }
        }
        currentCoroutineContext().ensureActive()
        store.commitAnswer(courseId, CourseAnswerSubmission(
            operationId = operationId,
            expectedRevision = expectedRevision,
            slideId = slideId,
            answer = submitted,
            correct = correct,
            resultingStateJson = next?.second?.toString() ?: slide.content.stateJson,
            resultingUiJson = next?.first?.toString(),
        ))
    }

    fun advance(courseId: String, expectedRevision: Long, slideId: String): CourseSnapshot {
        val (_, step) = reached(courseId, expectedRevision, slideId)
        if (step.slide.kind.requiresAnswer) reject(CourseRejection.ANSWER_REQUIRED)
        val slide = generated(courseId, slideId)
        val spec = parseCourseSpec(slide.content, step.slide.kind)
        CourseExercise.read(spec, step.slide.kind)
        checkedCourseUi(spec, slide.content.uiJson, slide.content.stateJson, null, step.slide.kind)
        return store.advance(courseId, expectedRevision, slideId)
    }

    private fun reached(courseId: String, expectedRevision: Long, slideId: String): Pair<CourseSnapshot, CourseStep> {
        val course = store.courseAt(courseId, expectedRevision)
        if (course.progress.currentSlideId != slideId) reject(CourseRejection.SLIDE_NOT_REACHED)
        return course to course.outline.steps()[course.progress.completedSlides]
    }

    private fun generated(courseId: String, slideId: String): CourseSlide =
        store.slide(courseId, slideId) ?: reject(CourseRejection.SLIDE_NOT_GENERATED)

    private fun failIfCurrent(operation: CourseGenerationOperation, failure: CourseGenerationFailure) {
        try {
            store.failGeneration(operation, failure)
        } catch (stale: CourseStoreException) {
            if (stale.reason != CourseRejection.STALE_GENERATION) throw stale
        }
    }

    private suspend fun checkActive(session: CloudSession) {
        currentCoroutineContext().ensureActive()
        try {
            session.check()
        } catch (_: CloudFailure) {
            throw CancellationException(CourseGenerationFailure.INTERRUPTED.name)
        }
    }

    private fun reject(reason: CourseRejection): Nothing = throw CourseStoreException(reason)

    private fun artifactId(courseId: String, slideId: String): String =
        UUID.nameUUIDFromBytes("$courseId\n$slideId".toByteArray(Charsets.UTF_8)).toString()
}
