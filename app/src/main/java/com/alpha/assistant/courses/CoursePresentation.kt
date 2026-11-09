package com.alpha.assistant.courses

import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

internal sealed interface CoursePage {
    data class Library(val courses: List<CourseSnapshot>) : CoursePage
    data class Outline(val course: CourseSnapshot) : CoursePage
    data object LoadFailed : CoursePage

    data class Slide(
        val course: CourseSnapshot,
        val step: CourseStep,
        val number: Int,
        val ui: String,
        val state: String,
        val prompt: CourseExercisePrompt?,
        val sources: List<CourseSourceReference>,
        val adaptationNote: String?,
        val feedback: CourseAnswerFeedback?,
        val scene: ToolSpec? = null,
        val sceneRevision: Long = course.outlineRevision,
    ) : CoursePage

    data class Unavailable(
        val course: CourseSnapshot,
        val issue: CourseSlideIssue,
        val step: CourseStep,
        val sceneFailure: String? = null,
    ) : CoursePage {
        val generationRequest: CourseSlideGenerationRequest?
            get() = if (issue != CourseSlideIssue.INVALID && step.slide.id == course.progress.currentSlideId) {
                CourseSlideGenerationRequest(course.id, course.revision, step.slide.id)
            } else null
    }
    data class Complete(val course: CourseSnapshot) : CoursePage
}

internal enum class CourseSlideIssue { MISSING, GENERATION_FAILED, GENERATION_INTERRUPTED, GENERATION_BUSY, INVALID }

/** The host owns consent and calls CourseController.generate. The player reads the committed result. */
internal fun interface CourseSlideGenerationHost {
    suspend fun generate(request: CourseSlideGenerationRequest)
}

/** The host searches the submitted query and prepares the same reached slide. */
internal fun interface CourseSourcedSlideGenerationHost {
    suspend fun generate(request: CourseSlideGenerationRequest, query: String)
}

internal data class CourseSlideGenerationRequest(val courseId: String, val expectedRevision: Long, val slideId: String)

internal fun interface CourseNextGenerationHost {
    suspend fun generate(request: CourseNextRequest): CourseSlideContent
}

internal sealed interface CourseNextPreparation {
    data class Ready(val request: CourseNextRequest, val page: CoursePage.Slide, val draft: CourseSlideContent?) : CourseNextPreparation
    data object Failed : CourseNextPreparation
    data class SceneFailed(val detail: String) : CourseNextPreparation
    data object Unavailable : CourseNextPreparation
    data object Stale : CourseNextPreparation
}

internal enum class CourseSourceSearchIssue { QUERY, EMPTY, UNAVAILABLE, FAILED }

internal data class CourseSlideGenerationUpdate(
    val page: CoursePage,
    val reloaded: Boolean = false,
    val searchIssue: CourseSourceSearchIssue? = null,
)

internal data class CourseAnswerFeedback(val answer: CourseAnswer, val correct: Boolean)

internal sealed interface CoursePlayerUpdate {
    data class Open(val page: CoursePage, val reloaded: Boolean = false) : CoursePlayerUpdate
    data object SaveFailed : CoursePlayerUpdate
}

/** The saved-course player uses only local content and host-graded exercises. */
internal class CoursePresentation(
    private val store: CourseStore,
    private val controller: CourseController,
) {
    fun library(): CoursePage = read { CoursePage.Library(store.courses()) }

    fun outline(courseId: String): CoursePage = read { CoursePage.Outline(requireCourse(courseId)) }

    fun unsavedReach(course: CourseSnapshot): CoursePage.Unavailable? =
        (player(course.id) as? CoursePage.Unavailable)?.takeIf {
            it.course.revision == course.revision && it.generationRequest != null
        }

    fun player(courseId: String, completedAnswerId: String? = null): CoursePage = read {
        val course = requireCourse(courseId)
        val steps = course.outline.steps()
        val position = course.progress.completedSlides

        // Restore held feedback only when this request completed the immediately previous slide.
        if (completedAnswerId != null && position > 0) {
            val previous = steps[position - 1]
            val attempt = store.answerAttempts(courseId).firstOrNull {
                it.operationId == completedAnswerId && it.slideId == previous.slide.id && it.correct
            }
            if (attempt != null) return@read slide(course, previous, position, attempt)
        }

        val step = steps.getOrNull(position) ?: return@read CoursePage.Complete(course)
        val attempt = if (step.slide.kind.requiresAnswer) {
            store.answerAttempts(courseId).lastOrNull { it.slideId == step.slide.id }
        } else null
        slide(course, step, position + 1, attempt)
    }

    suspend fun submit(slide: CoursePage.Slide, operationId: String, answer: CourseAnswer): CoursePlayerUpdate {
        if (slide.prompt == null || slide.feedback?.correct == true) return CoursePlayerUpdate.SaveFailed
        return update(slide.course.id) {
            val updated = controller.submitAnswer(slide.course.id, slide.course.revision, slide.step.slide.id, operationId, answer)
            val correct = updated.progress.completedSlides > slide.course.progress.completedSlides
            slide.copy(course = updated, feedback = CourseAnswerFeedback(answer, correct))
        }
    }

    fun next(slide: CoursePage.Slide, prepared: CourseNextPreparation.Ready? = null): CoursePlayerUpdate {
        val courseId = slide.course.id
        if (prepared != null) return update(courseId) {
            check(prepared.request == slide.nextRequest)
            val updated = store.adoptNext(prepared.request, prepared.draft)
            prepared.page.copy(course = updated)
        }
        if (slide.feedback?.correct == true) return CoursePlayerUpdate.Open(player(courseId))
        return update(courseId) {
            controller.advance(courseId, slide.course.revision, slide.step.slide.id)
            player(courseId)
        }
    }

    suspend fun prepareNext(request: CourseNextRequest, host: CourseNextGenerationHost?): CourseNextPreparation {
        val slot = GenerationSlot(prefetch = true)
        check(acquireGeneration(request.courseId, slot))
        try {
            val course = store.courseAt(request.courseId, request.revision)
            val target = course.nextTarget(request.visibleSlideId) ?: return CourseNextPreparation.Stale
            val saved = store.slide(course.id, target.step.slide.id)?.content
            val content = saved ?: host?.generate(request) ?: return CourseNextPreparation.Unavailable
            currentCoroutineContext().ensureActive()
            store.courseAt(request.courseId, request.revision)
            val attempt = if (saved != null && target.step.slide.kind.requiresAnswer) {
                store.answerAttempts(course.id).lastOrNull { it.slideId == target.step.slide.id }
            } else null
            val page = checkedSlide(course, target.step, target.index + 1, attempt, content)
            return CourseNextPreparation.Ready(request, page, content.takeIf { saved == null })
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            return CourseNextPreparation.Failed
        } catch (failure: CourseWorkflowException) {
            return if (failure.reason == CourseWorkflowError.INVALID_SCENE) CourseNextPreparation.SceneFailed(checkNotNull(failure.message))
            else CourseNextPreparation.Failed
        } catch (failure: CourseStoreException) {
            return when (failure.reason) {
                CourseRejection.STALE_REVISION, CourseRejection.COURSE_NOT_FOUND, CourseRejection.SLIDE_NOT_REACHED ->
                    CourseNextPreparation.Stale
                else -> CourseNextPreparation.Failed
            }
        } catch (_: Exception) {
            return CourseNextPreparation.Failed
        } finally {
            releaseGeneration(request.courseId, slot)
        }
    }

    suspend fun generate(
        page: CoursePage.Unavailable,
        host: CourseSlideGenerationHost,
        retry: Boolean,
    ): CourseSlideGenerationUpdate = prepare(page, host, if (retry) GenerationMode.RETRY else GenerationMode.AUTO)

    suspend fun generateFromOutline(
        page: CoursePage.Unavailable,
        host: CourseSlideGenerationHost,
        sourced: Boolean,
    ): CourseSlideGenerationUpdate = prepare(page, host, if (sourced) GenerationMode.SOURCED else GenerationMode.OUTLINE)

    private enum class GenerationMode { AUTO, RETRY, OUTLINE, SOURCED }

    private suspend fun prepare(
        page: CoursePage.Unavailable,
        host: CourseSlideGenerationHost,
        mode: GenerationMode,
    ): CourseSlideGenerationUpdate {
        val request = page.generationRequest ?: return CourseSlideGenerationUpdate(page)
        val slot = GenerationSlot(prefetch = false)
        if (!acquireGeneration(request.courseId, slot)) {
            return CourseSlideGenerationUpdate(page.copy(issue = CourseSlideIssue.GENERATION_BUSY))
        }
        try {
            val latest = player(request.courseId)
            val pending = latest as? CoursePage.Unavailable
            val current = pending?.generationRequest
            if (current == null || current.slideId != request.slideId ||
                pending.course.outlineRevision != page.course.outlineRevision ||
                (mode != GenerationMode.RETRY && current != request) ||
                (mode == GenerationMode.AUTO && pending.issue != CourseSlideIssue.MISSING)
            ) {
                return CourseSlideGenerationUpdate(latest, reloaded = true)
            }

            currentCoroutineContext().ensureActive()
            var issue = CourseSlideIssue.GENERATION_FAILED
            var reloaded = false
            var searchIssue: CourseSourceSearchIssue? = null
            var sceneFailure: String? = null
            try {
                host.generate(current)
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
                issue = CourseSlideIssue.GENERATION_INTERRUPTED
            } catch (failure: CourseWorkflowException) {
                if (failure.reason == CourseWorkflowError.INVALID_SCENE) sceneFailure = failure.message
            } catch (failure: CourseStoreException) {
                reloaded = when (failure.reason) {
                    CourseRejection.STALE_REVISION, CourseRejection.STALE_GENERATION, CourseRejection.SLIDE_NOT_REACHED,
                    CourseRejection.SLIDE_ALREADY_GENERATED, CourseRejection.COURSE_NOT_FOUND -> true
                    else -> false
                }
            } catch (failure: CloudFailure) {
                if (mode == GenerationMode.SOURCED) {
                    when (failure.code) {
                        "search_query" -> searchIssue = CourseSourceSearchIssue.QUERY
                        "search_empty" -> searchIssue = CourseSourceSearchIssue.EMPTY
                        "search_unavailable" -> searchIssue = CourseSourceSearchIssue.UNAVAILABLE
                        "cancelled" -> issue = CourseSlideIssue.GENERATION_INTERRUPTED
                        else -> searchIssue = CourseSourceSearchIssue.FAILED
                    }
                }
            } catch (_: Exception) {
                // The page reports a fixed error. The host can retain its own diagnostic.
            }

            currentCoroutineContext().ensureActive()
            val saved = player(request.courseId)
            return when (saved) {
                is CoursePage.Unavailable -> {
                    val sameReach = saved.step == pending.step && saved.course.outlineRevision == pending.course.outlineRevision
                    val failure = if (sameReach && saved.issue == CourseSlideIssue.MISSING && !reloaded) {
                        saved.copy(issue = issue)
                    } else saved
                    CourseSlideGenerationUpdate(failure.copy(sceneFailure = sceneFailure.takeIf { sameReach && !reloaded }),
                        reloaded || !sameReach, searchIssue)
                }
                is CoursePage.Slide -> CourseSlideGenerationUpdate(saved, reloaded || saved.step != pending.step ||
                    saved.course.outlineRevision != pending.course.outlineRevision)
                else -> CourseSlideGenerationUpdate(saved, reloaded)
            }
        } finally {
            releaseGeneration(request.courseId, slot)
        }
    }

    private fun slide(
        course: CourseSnapshot,
        step: CourseStep,
        number: Int,
        attempt: CourseAnswerAttempt?,
    ): CoursePage {
        val content = store.slide(course.id, step.slide.id)?.content
        if (content == null) {
            val issue = when {
                course.generation != null || course.generationFailure == CourseGenerationFailure.INTERRUPTED ->
                    CourseSlideIssue.GENERATION_INTERRUPTED
                course.generationFailure != null -> CourseSlideIssue.GENERATION_FAILED
                else -> CourseSlideIssue.MISSING
            }
            return CoursePage.Unavailable(course, issue, step)
        }
        return try {
            checkedSlide(course, step, number, attempt, content)
        } catch (_: CourseWorkflowException) {
            CoursePage.Unavailable(course, CourseSlideIssue.INVALID, step)
        }
    }

    private fun checkedSlide(
        course: CourseSnapshot, step: CourseStep, number: Int, attempt: CourseAnswerAttempt?, content: CourseSlideContent,
    ): CoursePage.Slide = validateCourseOutput {
        content.encodeSources()
        val spec = parseCourseSpec(content, step.slide.kind)
        val scene = spec.takeIf { step.slide.kind.allowsScene && it.surfaces.isNotEmpty() }
        require(spec.composition == null || scene != null)
        val exercise = CourseExercise.read(spec, step.slide.kind)
        require(exercise?.actionId == null)
        checkedCourseUi(spec, spec.ui.toString(), spec.initialState, exercise, step.slide.kind)
        val (ui, state) = checkedCourseUi(spec, content.uiJson, content.stateJson, exercise, step.slide.kind)
        val feedback = attempt?.let { CourseAnswerFeedback(it.answer, it.correct) }
        CoursePage.Slide(course, step, number, ui.toString(), state.toString(), exercise?.prompt,
            content.sources, content.adaptationNote, feedback, scene)
    }

    private fun requireCourse(id: String): CourseSnapshot =
        store.course(id) ?: throw CourseStoreException(CourseRejection.COURSE_NOT_FOUND)

    private inline fun read(block: () -> CoursePage): CoursePage = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        CoursePage.LoadFailed
    }

    private inline fun update(courseId: String, block: () -> CoursePage): CoursePlayerUpdate = try {
        CoursePlayerUpdate.Open(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: CourseStoreException) {
        when (failure.reason) {
            CourseRejection.STALE_REVISION, CourseRejection.DUPLICATE_ANSWER,
            CourseRejection.SLIDE_NOT_REACHED, CourseRejection.SLIDE_NOT_GENERATED, CourseRejection.SLIDE_ALREADY_GENERATED,
            CourseRejection.COURSE_NOT_FOUND -> CoursePlayerUpdate.Open(player(courseId), reloaded = true)
            else -> CoursePlayerUpdate.SaveFailed
        }
    } catch (_: Exception) {
        CoursePlayerUpdate.SaveFailed
    }

    private class GenerationSlot(val prefetch: Boolean) {
        val finished = CompletableDeferred<Unit>()
    }

    private companion object {
        val activeGenerations = ConcurrentHashMap<String, GenerationSlot>()

        suspend fun acquireGeneration(courseId: String, slot: GenerationSlot): Boolean {
            while (true) {
                currentCoroutineContext().ensureActive()
                val active = activeGenerations.putIfAbsent(courseId, slot) ?: return true
                if (!slot.prefetch && !active.prefetch) return false
                active.finished.await()
            }
        }

        fun releaseGeneration(courseId: String, slot: GenerationSlot) {
            activeGenerations.remove(courseId, slot)
            slot.finished.complete(Unit)
        }
    }
}

internal val CoursePage.Slide.nextRequest: CourseNextRequest?
    get() = if (number < course.progress.totalSlides && (prompt == null || feedback?.correct == true)) {
        CourseNextRequest(course.id, course.revision, step.slide.id)
    } else null
