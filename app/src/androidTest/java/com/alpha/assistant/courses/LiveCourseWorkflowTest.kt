package com.alpha.assistant.courses

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.tools.OneUseCredential
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LiveCourseWorkflowTest {
    @Test fun generateAndPlay() {
        val args = InstrumentationRegistry.getArguments()
        val runId = args.getString("courseRunId")
        assumeTrue(runId != null)
        requireNotNull(runId)
        require(runId.matches(Regex("course-[a-f0-9]{12}")))
        val case = requireNotNull(args.getString("courseScenario"))
        val baseline = args.getString("courseBaseline") == "true"
        val robot = ShellRobot(runId)
        val fixture = ShellFixture(runId)
        val cases = robot.instrumentation.context.assets.open("course-scenarios.json").bufferedReader().use { it.readText() }
        val input = JSONObject(cases).getJSONObject(case)
        val directory = File(fixture.context.getExternalFilesDir(null), "course-acceptance/$runId")
        check(directory.mkdirs())
        val events = JSONArray()
        val report = JSONObject().put("scenario", case).put("baseline", baseline).put("events", events)
        val reportFile = File(directory, "report.json")
        fun save(phase: String) = synchronized(report) {
            report.put("phase", phase)
            File(directory, "report.tmp").apply { writeText(report.toString(2)); check(renameTo(reportFile)) }
        }
        fun event(row: JSONObject) = synchronized(report) {
            events.put(row)
            save(report.getString("phase"))
        }
        fun click(id: Int) = robot.click(robot.text(id))
        fun screenshot(name: String) {
            val shot = checkNotNull(robot.automation.takeScreenshot())
            File(directory, "$name.png").outputStream().use { check(shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            shot.recycle()
        }
        fun reveal(text: String) {
            repeat(5) {
                if (robot.find(text)?.isVisibleToUser == true) return
                if (!robot.scrollForward()) return@repeat
            }
            robot.waitFor(text)
        }

        val calls = AtomicInteger()
        var checkpoint = "credential"
        var activity: ActivityScenario<CloudTestActivity>? = null
        try {
            val credential = OneUseCredential.receive(runId) { save("credentialReady") }
            save("running")
            val live = DeepSeekProvider("https://api.deepseek.com", onHttpCall = { calls.incrementAndGet() }) {}
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    check(key == "course-fixture")
                    val stage = request.generationStage?.name ?: if (request.chatDecision) "DECISION" else "OUTLINE"
                    val row = JSONObject().put("stage", stage).put("started_ms", SystemClock.elapsedRealtime())
                    if (stage == "SELECTOR") {
                        val body = JSONObject(request.turns.first().text)
                        row.put("batch", body.getString("stage"))
                    }
                    val fragments = StringBuilder()
                    var completed: String? = null
                    try {
                        live.respond(credential, request, session) { response ->
                            when (response) {
                                is CloudEvent.Text -> fragments.append(response.value)
                                is CloudEvent.RoundComplete -> completed = response.text
                                else -> Unit
                            }
                            emit(response)
                        }
                        row.put("result", "received")
                    } catch (failure: CloudFailure) {
                        row.put("result", failure.code)
                        throw failure
                    } finally {
                        val output = completed ?: fragments.toString()
                        if (output.isNotEmpty()) row.put("response", output)
                        event(row.put("ended_ms", SystemClock.elapsedRealtime()))
                    }
                }
            }
            fixture.seedChat("course", "Course acceptance", "Ready", selected = true)
            check(fixture.keys.save("course-fixture").isSuccess)
            CloudTestActivity.factory = { app ->
                ShellViewModel(app, provider, fixture.store, fixture.keys).also { fixture.model = it }
            }
            fixture.launch().also { activity = it }.let {
                val model = checkNotNull(fixture.model)
                val store = model.courseStore
                checkpoint = "outline"
                robot.edit(robot.text(R.string.composer_label), input.getString("request"))
                click(R.string.composer_send)
                robot.waitUntil(240_000) {
                    synchronized(report) {
                        val decision = (0 until events.length()).map { events.getJSONObject(it) }
                            .firstOrNull { it.optString("stage") == "DECISION" }
                        val kind = decision?.optString("response")?.let { runCatching { JSONObject(it).optString("kind") }.getOrNull() }
                        check(kind == null || kind == "course") { "Chat did not authorize a course: $kind" }
                    }
                    robot.find(robot.text(R.string.shell_course_start)) != null ||
                        robot.find(robot.text(R.string.shell_course_plan_failed)) != null
                }
                check(robot.find(robot.text(R.string.shell_course_start)) != null) { "Outline unavailable" }
                screenshot("outline")
                val started = SystemClock.elapsedRealtime()
                checkpoint = "start"
                click(R.string.shell_course_start)
                robot.waitUntil { store.courses().size == 1 }
                val course = store.courses().single()
                val steps = course.outline.steps()
                val cards = input.getJSONArray("cards")
                val kind = CourseSlideKind.valueOf(input.getString("kind"))
                val cardCount = if (cards.length() == 0) 1 else cards.length()
                val exercises = steps.map { it.slide.kind }.filter { it.requiresAnswer }
                if (!baseline) check(steps.size == cardCount + 2 && exercises == listOf(kind)) {
                    "Unexpected outline slide count or exercise kinds: ${steps.map { it.slide.kind }}"
                }
                var nextTappedAt: Long? = null
                val next = robot.text(R.string.course_next)
                fun waitForNext() {
                    robot.waitUntil(240_000) {
                        check(robot.find(robot.text(R.string.course_next_failed)) == null) { "Next slide preparation failed." }
                        robot.find(next)?.isEnabled == true
                    }
                }
                for ((index, step) in steps.withIndex()) {
                    checkpoint = "slide-${index + 1}-${step.slide.kind}"
                    robot.waitUntil(240_000) {
                        store.slide(course.id, step.slide.id) != null ||
                            robot.find(robot.text(R.string.course_generation_retry)) != null
                    }
                    val slide = checkNotNull(store.slide(course.id, step.slide.id)) {
                        "Slide generation failed: ${store.course(course.id)?.generationFailure}"
                    }
                    val exercise = CourseExercise.read(parseCourseSpec(slide.content), step.slide.kind)
                    val position = robot.text(R.string.course_slide_position, index + 1, steps.size)
                    robot.waitUntil(240_000) { robot.find(position)?.isVisibleToUser == true }
                    val visibleAt = SystemClock.elapsedRealtime()
                    if (index == 0) event(JSONObject().put("first_visible_ms", visibleAt - started))
                    nextTappedAt?.let { event(JSONObject().put("visible_slide", index + 1).put("tap_to_visible_ms", visibleAt - it)) }
                    fun recordReadiness(eligibleAt: Long) {
                        waitForNext()
                        event(JSONObject().put("from_slide", index + 1)
                            .put("until_next_ready_ms", SystemClock.elapsedRealtime() - eligibleAt))
                    }
                    if (!baseline && index != 0 && exercise == null) recordReadiness(visibleAt)
                    screenshot("slide-${index + 1}")
                    if (!baseline && index < cards.length()) {
                        val expected = cards.getJSONArray(index)
                        val label = expected.getString(0)
                        if (label.isNotEmpty()) {
                            val (upper, lower) = label.split("/")
                            val pair = Regex("""(?<!\p{L})${Regex.escape(upper)}[\s/·—–,]*${Regex.escape(lower)}(?!\p{L})""")
                            robot.waitUntil { robot.texts().any(pair::containsMatchIn) }
                        }
                        robot.waitUntil { robot.texts().any { it.contains(expected.getString(1), ignoreCase = true) } }
                        val choices = expected.getJSONArray(2)
                        robot.waitUntil { (0 until choices.length()).any { robot.find(choices.getString(it))?.isVisibleToUser == true } }
                    }
                    event(JSONObject().put("course", course.id).put("slide", step.slide.id).put("kind", step.slide.kind.name))
                    if (index == 0 && !baseline) {
                        robot.back()
                        click(R.string.shell_course_continue)
                        robot.waitFor(position)
                        if (exercise == null) recordReadiness(SystemClock.elapsedRealtime())
                        check(store.course(course.id)?.progress?.currentSlideId == step.slide.id)
                        screenshot("resumed")
                    }
                    if (baseline) break
                    if (exercise != null) {
                        check(exercise.prompt.kind == kind)
                        for ((answer, feedback) in listOf("wrong" to R.string.course_answer_wrong, "right" to R.string.course_answer_correct)) {
                            val values = input.getJSONArray(answer)
                            when (kind) {
                                CourseSlideKind.CHOICE -> robot.click(values.getString(0))
                                CourseSlideKind.FILL_IN -> robot.edit(robot.text(R.string.course_answer_label), values.getString(0))
                                CourseSlideKind.ORDER -> {
                                    val reset = robot.text(R.string.course_order_reset)
                                    if (robot.find(reset) != null) {
                                        robot.click(reset)
                                        robot.waitGone(reset)
                                    }
                                    repeat(values.length()) { index ->
                                        val option = values.getString(index)
                                        reveal(option)
                                        robot.click(option)
                                        reveal(robot.text(R.string.course_order_position, index + 1, option))
                                    }
                                }
                                else -> error("Unexpected exercise")
                            }
                            reveal(robot.text(R.string.course_check))
                            click(R.string.course_check)
                            reveal(robot.text(feedback))
                            if (answer == "right") recordReadiness(SystemClock.elapsedRealtime())
                            screenshot(answer)
                        }
                    }
                    val action = generateSequence(robot.waitFor(next)) { it.parent }.first { it.isClickable }
                    nextTappedAt = SystemClock.elapsedRealtime()
                    check(robot.diagnostics.click(next, action))
                }
                if (!baseline) {
                    robot.waitFor(robot.text(R.string.course_complete))
                    check(store.course(course.id)?.progress?.isComplete == true)
                    screenshot("complete")
                }
            }
            save("complete")
        } catch (failure: Throwable) {
            report.put("checkpoint", checkpoint)
            report.put("failure", failure.message)
            if (activity != null) runCatching { screenshot("failure") }
            save("failed")
            throw failure
        } finally {
            report.put("provider_calls", calls.get())
            save(report.optString("phase", "failed"))
            activity?.close()
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-courses.db")
            fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        }
    }
}
