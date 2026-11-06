package com.alpha.assistant.tools

import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import com.alpha.assistant.ui.AlphaShell
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class StaticDraftPreviewUiWorkflowTest {
    private val robot = ShellRobot("static-draft-preview-ui")

    @Test fun manualPreviewKeepsTheDrawingAcrossResizeAndAppliesOnlyOnRequest() = withFixture(Provider("Initial", "Revised")) { fixture, store ->
        fixture.launch().use { scenario ->
            robot.waitUntil { robot.composed(scenario, "desktop") }
            val viewport = constrainWidth(fixture, scenario)
            openLibrary(scenario)
            val before = SurfaceFrameMetrics.snapshot()?.generation
            createPreview(fixture, "Draw the original shape")
            val drawing = canvasLabel("Initial first. Initial second.")
            reveal(drawing)
            assertTrue(store.tools().isEmpty())
            assertDisabledAction("Initial action")
            assertEquals(before, SurfaceFrameMetrics.snapshot()?.generation)

            val composer = robot.text(R.string.composer_label)
            robot.waitGone(composer)
            resize(scenario, viewport, 900)
            assertWidePanes(scenario, composer, viewport)
            reveal(drawing)
            robot.diagnostics.capture("manual-wide")
            resize(scenario, viewport, 420)
            robot.waitGone(composer)
            reveal(drawing)
            assertEquals(before, SurfaceFrameMetrics.snapshot()?.generation)
            robot.diagnostics.capture("manual-narrow-wide-narrow")

            robot.click(robot.text(R.string.tool_apply_changes))
            robot.waitGone(drawing)
            robot.waitUntil { store.tools().singleOrNull()?.revision == 1 }
            val original = store.tools().single()
            robot.waitGone(robot.text(R.string.tool_refinement))
            val pending = ToolDraft("pending-refinement", original.id, original.revision, original.stateVersion,
                "Draw the revised shape", null, null, null, original.conversationId)
            store.saveDraft(pending)
            robot.click(robot.text(R.string.tool_library_back))
            reveal(pending.prompt)
            robot.click(pending.prompt)
            reveal(pending.prompt)
            robot.waitGone(robot.text(R.string.tool_refine_request))
            robot.waitGone(robot.text(R.string.tool_preview_changes))
            robot.click(robot.text(R.string.tool_retry_preview))
            val revised = canvasLabel("Revised first. Revised second.")
            waitForEnabledAction(robot.text(R.string.tool_apply_changes), timeoutMs = 30_000)
            assertTrue("The revised drawing is visible", robot.waitFor(revised).isVisibleToUser)
            assertEquals(pending.id, store.drafts().single().id)
            assertEquals(original, store.tool(original.id))
            robot.waitGone(drawing)
            robot.diagnostics.capture("manual-revised")
            robot.click(robot.text(R.string.tool_discard_preview))
            robot.waitGone(revised)
            robot.waitUntil { store.drafts().isEmpty() }
            assertEquals(original, store.tool(original.id))

            store.saveDraft(pending)
            store.setArchived(original, true)
            robot.click(robot.text(R.string.tool_library_back))
            reveal(pending.prompt)
            robot.click(pending.prompt)
            reveal(pending.prompt)
            robot.waitGone(robot.text(R.string.tool_retry_preview))
            robot.click(robot.text(R.string.tool_discard_draft))
            robot.waitUntil { store.drafts().isEmpty() }
            assertEquals(original.copy(archived = true, stateVersion = original.stateVersion + 1), store.tool(original.id))
        }
    }

    @Test fun mixedCandidateShowsItsSealedPartsAndStaticDrawing() = withFixture(Provider("Mixed", mixed = true)) { fixture, store ->
        fixture.launch().use { scenario ->
            robot.waitUntil { robot.composed(scenario, "desktop") }
            openLibrary(scenario)
            createPreview(fixture, "Draw a dot and add an independent counter")
            reveal(canvasLabel("Mixed first. Mixed second."))
            assertDisabledAction("Increase")
            assertDisabledAction("Mixed action")
            val draft = store.drafts().single()
            val spec = ToolSpec.parsePersisted(checkNotNull(draft.candidate))
            assertNotNull(spec.composition)
            assertEquals(0, JSONObject(checkNotNull(draft.previewState)).getInt("p5_count_value"))
            assertTrue(store.tools().isEmpty())
        }
    }

    @Test fun savedDraftCanApplyAfterModelRecreationWithoutBorrowingDrawing() = withFixture(Provider("Restored")) { fixture, store ->
        val prompt = "Draw the saved candidate"
        val drawing = canvasLabel("Restored first. Restored second.")
        fixture.launch().use { scenario ->
            robot.waitUntil { robot.composed(scenario, "desktop") }
            openLibrary(scenario)
            createPreview(fixture, prompt)
            reveal(drawing)
        }
        fixture.launch().use { scenario ->
            robot.waitUntil { robot.composed(scenario, "desktop") }
            openLibrary(scenario)
            reveal(prompt)
            robot.click(prompt)
            robot.waitFor(robot.text(R.string.tool_content))
            val unavailable = robot.text(R.string.tool_preview_drawing_unavailable)
            assertTrue("Restored drawing notice is visible", robot.waitFor(unavailable).isVisibleToUser)
            robot.waitGone(drawing)
            assertDisabledAction("Restored action")
            waitForEnabledAction(robot.text(R.string.tool_apply_changes))
            robot.diagnostics.capture("recreated-without-drawing")
            robot.click(robot.text(R.string.tool_apply_changes))
            robot.waitUntil { store.tools().singleOrNull()?.revision == 1 }
        }
    }

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

    private fun openLibrary(scenario: ActivityScenario<CloudTestActivity>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.mini_apps))
        robot.awaitDrawerClosed(scenario)
    }

    private fun createPreview(fixture: ShellFixture, prompt: String) {
        robot.instrumentation.runOnMainSync {
            checkNotNull(fixture.model).tools.generate(prompt, null, null, emptyList())
        }
        waitForEnabledAction(robot.text(R.string.tool_apply_changes), timeoutMs = 30_000)
    }

    private fun reveal(label: String): AccessibilityNodeInfo {
        robot.diagnostics.target(label)
        val shown = robot.find(label)
        // Compose can accept Show on screen for a visible node in a viewport that cannot scroll.
        if (shown?.isVisibleToUser == true && generateSequence(shown) { it.parent }.none { it.isScrollable }) return shown
        return robot.reveal(label).also { assertTrue("Visible: $label", it.isVisibleToUser) }
    }

    private fun action(label: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        generateSequence(label) { it.parent }.firstOrNull { it.isClickable }

    private fun waitForEnabledAction(label: String, timeoutMs: Long = 10_000) {
        robot.diagnostics.target(label)
        robot.waitUntil(timeoutMs) {
            action(robot.find(label))?.let { it.isVisibleToUser && it.isEnabled && supportsClick(it) } == true
        }
    }

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

    private data class PreviewViewport(val width: MutableState<Int>, val density: Float, val measuredPx: AtomicInteger)

    private fun constrainWidth(fixture: ShellFixture, scenario: ActivityScenario<CloudTestActivity>): PreviewViewport {
        val width = mutableStateOf(420)
        val measuredPx = AtomicInteger()
        var density = 0f
        scenario.onActivity { activity ->
            val model = checkNotNull(fixture.model)
            density = minOf(activity.resources.displayMetrics.density, activity.window.decorView.width / 960f)
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    Box(Modifier.width(width.value.dp).fillMaxHeight().onSizeChanged { measuredPx.set(it.width) }) {
                        AlphaTheme { AlphaShell(model) }
                    }
                }
            }
        }
        robot.waitUntil { robot.composed(scenario, "desktop") }
        return PreviewViewport(width, density, measuredPx).also { awaitWidth(it, 420) }
    }

    private fun resize(scenario: ActivityScenario<CloudTestActivity>, viewport: PreviewViewport, widthDp: Int) {
        scenario.onActivity { viewport.width.value = widthDp }
        awaitWidth(viewport, widthDp)
    }

    private fun awaitWidth(viewport: PreviewViewport, widthDp: Int) {
        val expectedPx = (widthDp * viewport.density).roundToInt()
        try {
            robot.waitUntil { viewport.measuredPx.get() == expectedPx }
        } catch (failure: AssertionError) {
            throw AssertionError("Preview width ${viewport.measuredPx.get()}px, expected ${expectedPx}px for ${widthDp}dp", failure)
        }
    }

    private fun assertWidePanes(scenario: ActivityScenario<CloudTestActivity>, composer: String, viewport: PreviewViewport) {
        try {
            assertTrue("The wide chat composer is visible", robot.waitFor(composer).isVisibleToUser)
            robot.waitUntil {
                if (!robot.composed(scenario, "mini-app") || !robot.composed(scenario, "chat")) return@waitUntil false
                val app = robot.composedBounds(scenario, "mini-app").first
                val chat = robot.composedBounds(scenario, "chat").first
                app.width() > 0 && chat.width() > 0 && !Rect.intersects(app, chat)
            }
        } catch (failure: AssertionError) {
            val app = if (robot.composed(scenario, "mini-app")) robot.composedBounds(scenario, "mini-app").first else null
            val chat = if (robot.composed(scenario, "chat")) robot.composedBounds(scenario, "chat").first else null
            var windowWidthDp = 0
            scenario.onActivity { windowWidthDp = it.resources.configuration.screenWidthDp }
            throw AssertionError("Wide preview: requested=${viewport.width.value}dp viewport=${viewport.measuredPx.get()}px density=${viewport.density} " +
                "Android window=${windowWidthDp}dp app=$app chat=$chat", failure)
        }
    }

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
            if (!mixed && request.replyWithFixturePlan(emit)) return
            val response = when (request.generationStage) {
                CloudGenerationStage.PLAN -> """{"title":"Mixed","description":"","language":"en","inputs":[],"constants":[],"questions":[],"needs":[
                    {"id":"count","type":"counter","label":"Count","description":"Increase and reset"},
                    {"id":"scene","type":"scene","label":"Dot","description":"Draw a dot"}]}""".withUncheckedBehavior()
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
                CloudGenerationStage.COMPLETE -> definition(responses.removeFirst(), mixed)
                CloudGenerationStage.REFINE -> {
                    check(!mixed) { "The fixture only refines a full definition" }
                    definition(responses.removeFirst(), mixed = false)
                }
                else -> error("Unexpected provider stage: ${request.generationStage}")
            }
            emit(CloudEvent.Text(response))
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
