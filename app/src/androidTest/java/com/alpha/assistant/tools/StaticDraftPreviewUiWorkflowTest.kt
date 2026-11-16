package com.alpha.assistant.tools

import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StaticDraftPreviewUiWorkflowTest {
    private val robot = ShellRobot("static-draft-preview-ui")

    @Test fun provisionalCardUsesOneCheckpointAndClearsOnCorrectionOrCancel(): Unit = runBlocking {
        withFixture(Provider()) { fixture, _ ->
            val draft = ToolDraft("draft", null, 0, 0, "Drawing in chat", null, null, null, "chat")
            val previews = ToolControllerDraftPreviews(this) { draft }
            val request = previews.begin(draft.id, "request")
            val first = snapshot(draft.id, "request", 1, "First")
            val corrected = snapshot(draft.id, "request", 2, "Second")
            fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Box(Modifier.safeDrawingPadding()) { GeneratedToolPreview(draft, previews.previewFor(draft.id)) }
                        }
                    }
                }
                val before = SurfaceFrameMetrics.snapshot()?.generation
                request.onPreview(first)
                val firstDrawing = canvasLabel("First first. First second.")
                robot.waitFor(robot.text(R.string.tool_preview_provisional))
                assertDisabledAction("First action", inFocusedPreview = false)
                assertPaintOrderAndClipping(firstDrawing)
                assertEquals(before, SurfaceFrameMetrics.snapshot()?.generation)
                robot.diagnostics.capture("provisional-checkpoint")

                request.onClear(first.identity)
                robot.waitGone(firstDrawing)
                request.onPreview(first)
                request.onPreview(corrected)
                val secondDrawing = canvasLabel("Second first. Second second.")
                robot.waitFor(secondDrawing)
                robot.waitGone("First action")

                previews.clear()
                robot.waitGone(secondDrawing)
                request.onPreview(corrected)
                robot.waitGone("Second action")
                assertEquals(before, SurfaceFrameMetrics.snapshot()?.generation)
            }
        }
    }

    private fun assertPaintOrderAndClipping(label: String) {
        val bounds = robot.bounds(label)
        val image = checkNotNull(robot.automation.takeScreenshot())
        try {
            val y = bounds.top + bounds.height() * 3 / 4
            assertEquals(Color.MAGENTA, image.getPixel(bounds.left + bounds.width() / 8, y))
            assertEquals(Color.GREEN, image.getPixel(bounds.left + bounds.width() / 2, y))
        } finally {
            image.recycle()
        }
    }

    private fun canvasLabel(commands: String) = "${robot.text(R.string.tool_preview_drawing)}. $commands"

    private fun reveal(label: String): AccessibilityNodeInfo {
        robot.diagnostics.target(label)
        val shown = robot.find(label)
        // Compose can accept Show on screen for a visible node in a viewport that cannot scroll.
        if (shown?.isVisibleToUser == true && generateSequence(shown) { it.parent }.none { it.isScrollable }) return shown
        return robot.reveal(label).also { assertTrue("Visible: $label", it.isVisibleToUser) }
    }

    private fun action(label: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        generateSequence(label) { it.parent }.firstOrNull { it.isClickable }

    private fun assertDisabledAction(label: String, inFocusedPreview: Boolean = true) {
        val shown = if (inFocusedPreview) revealPreviewAction(label) else reveal(label)
        val control = checkNotNull(action(shown)) { "No control for $label" }
        assertTrue("Control is visible: $label", control.isVisibleToUser)
        assertFalse("Control is disabled: $label", control.isEnabled)
        assertFalse("Control omits the click action: $label", supportsClick(control))
    }

    private fun revealPreviewAction(label: String): AccessibilityNodeInfo {
        val viewportLabel = robot.text(R.string.tool_content)
        val viewport = robot.waitFor(viewportLabel)
        robot.diagnostics.target(label)
        fun matching(root: AccessibilityNodeInfo?, visibleOnly: Boolean): AccessibilityNodeInfo? =
            root?.let(::descendants)?.firstOrNull {
                (!visibleOnly || it.isVisibleToUser) && it.text?.toString()?.trimEnd() == label
            }
        matching(viewport, visibleOnly = true)?.let { return it }
        if (viewport.isScrollable) {
            val show = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            val target = matching(viewport, visibleOnly = false)
            val requested = target?.actionList?.any { it.id == show } == true && target.performAction(show)
            if (!requested && viewport.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                assertTrue("The preview scrolls toward $label", viewport.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            }
        }
        var shown: AccessibilityNodeInfo? = null
        robot.waitUntil { shown = matching(robot.find(viewportLabel), visibleOnly = true); shown != null }
        return checkNotNull(shown)
    }

    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        repeat(node.childCount) { index -> node.getChild(index)?.let { yieldAll(descendants(it)) } }
    }

    private fun supportsClick(control: AccessibilityNodeInfo): Boolean =
        control.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private inline fun withFixture(provider: Provider, block: (ShellFixture, ToolStore) -> Unit) {
        val fixture = ShellFixture("static-drawing")
        val store = ToolStore(fixture.context, "${fixture.store.databaseName}-tools.db")
        try {
            fixture.keys.save("fixture-key").getOrThrow()
            CloudTestActivity.factory = { app -> ShellViewModel(app, provider, fixture.store, fixture.keys).also { fixture.model = it } }
            block(fixture, store)
        } finally {
            store.close()
            fixture.remove()
        }
    }

    private fun snapshot(draftId: String, requestId: String, attempt: Int, title: String): ToolDraftPreview {
        val spec = ToolSpec.parse(definition(title, mixed = false))
        return ToolDraftPreview(ToolDraftPreviewIdentity(draftId, requestId, attempt), spec.json,
            spec.initialState, spec.ui.toString(), surfaces(title, "first", "second"))
    }

    private inner class Provider(vararg titles: String, private val mixed: Boolean = false) : ChatProvider {
        private val responses = ArrayDeque(titles.toList())
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            if (!mixed && request.replyWithFixturePlan(emit, fixtureScene())) return
            val response = when (request.generationStage) {
                CloudGenerationStage.PLAN -> """{"title":"Mixed","description":"","language":"en","inputs":[],"constants":[],"questions":[],"needs":[
                    {"id":"count","type":"counter","label":"Count","description":"Increase and reset"},
                    {"id":"scene","type":"scene","label":"Dot","description":"Draw a dot"}],"scene":${fixtureScene()}}"""
                CloudGenerationStage.SELECTOR -> {
                    val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
                    val answers = JSONArray()
                    repeat(questions.length()) { index ->
                        val question = questions.getJSONObject(index)
                        val answer = JSONObject().put("id", question.getString("id"))
                        if (question.getString("type") == "noul") {
                            answer.put("probability", 0)
                        } else {
                            val choices = question.getJSONObject("choices")
                            check(choices.has("A"))
                            val probabilities = JSONObject()
                            choices.keys().forEach { letter -> probabilities.put(letter, if (letter == "A") 1 else 0) }
                            answer.put("choice", "A").put("confidence", 1).put("probabilities", probabilities)
                        }
                        answers.put(answer)
                    }
                    JSONObject().put("answers", answers).toString()
                }
                CloudGenerationStage.COMPLETE -> JSONObject(definition(responses.removeFirst(), mixed)).also {
                    if (mixed) it.getJSONObject("fill").withFixtureDrawingBinding("fill_first") else it.withFixtureDrawingBinding("first")
                }.toString()
                CloudGenerationStage.REFINE -> {
                    check(!mixed) { "The fixture only refines a full definition" }
                    request.patchSavedFixture(definition(responses.removeFirst(), mixed = false))
                }
                else -> error("Unexpected provider stage: ${request.generationStage}")
            }
            emit(CloudEvent.Text(request.withFixtureCheckEvidence(response)))
        }
    }

    private fun definition(title: String, mixed: Boolean): String {
        val field = if (mixed) "fill_count" else "count"
        val first = if (mixed) "fill_first" else "first"
        val second = if (mixed) "fill_second" else "second"
        val action = if (mixed) "fill_action" else "action"
        val control = JSONObject("""{"type":"button","text":"$title action","action":"$action"}""")
        val spec = JSONObject().put("title", title).put("description", "").put("capabilities", JSONArray())
            .put("stateSchema", JSONObject().put(field, "number")).put("initialState", JSONObject().put(field, 0))
            .put("surfaces", JSONArray("""[{"id":"$first","width":64,"height":64,"x":0,"y":0},
                {"id":"$second","width":64,"height":64,"x":32,"y":0}]"""))
            .put("source", "return {state:input.state,surfaces:${surfaces(title, first, second)}};")
        return if (mixed) {
            spec.put("ui", JSONObject().put("type", "column").put("children", JSONArray().put(control)))
            JSONObject().put("holes", JSONArray().put("scene")).put("fill", spec).toString()
        } else spec.put("formatVersion", 3).put("ui", control).toString()
    }

    private fun surfaces(title: String, first: String, second: String) = """{
        "$second":{"commands":[{"type":"rect","x":-48,"y":0,"width":112,"height":64,"color":"#00FF00"},
            {"type":"text","x":4,"y":16,"size":8,"text":"$title second","color":"accent"}]},
        "$first":{"commands":[{"type":"rect","x":0,"y":0,"width":64,"height":64,"color":"#FF00FF"},
            {"type":"text","x":4,"y":16,"size":8,"text":"$title first","color":"accent"}]}}"""
}
