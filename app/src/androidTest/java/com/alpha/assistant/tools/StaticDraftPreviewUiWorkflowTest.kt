package com.alpha.assistant.tools

import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
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
        withFixture(Provider) { fixture, _ ->
            val draft = ToolDraft("draft", null, 0, 0, "Drawing in chat", null, null, null, "chat")
            val previews = ToolControllerDraftPreviews(this) { draft }
            val request = previews.begin(draft.id, "request")
            val first = snapshot(draft.id, "request", 1, "First")
            val corrected = snapshot(draft.id, "request", 2, "Second")
            fixture.launch().use { scenario ->
                scenario.onActivity { it.showPreview(draft, previews) }
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

    /** Review finding: the draft drew its canvas on the surface color, a gray box in a tinted card, unlike the accepted app. */
    @Test fun aDraftCanvasTakesItsCardColor(): Unit = runBlocking {
        withFixture(Provider) { fixture, _ ->
            val draft = ToolDraft("draft", null, 0, 0, "Board in chat", null, null, null, "chat")
            val previews = ToolControllerDraftPreviews(this) { draft }
            val request = previews.begin(draft.id, "request")
            val canvas = JSONObject("""{"type":"canvas","text":"Drawing board","surfaces":["board"]}""")
            val spec = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Board").put("description", "").put("capabilities", JSONArray())
                .put("stateSchema", JSONObject().put("n", "number")).put("initialState", JSONObject().put("n", 0))
                .put("surfaces", JSONArray("""[{"id":"board","width":300,"height":200}]"""))
                .put("source", "return {state:input.state,surfaces:{board:{commands:[]}}};")
                .put("ui", JSONObject().put("type", "theme").put("preset", "playful").put("palette", "orange").put("children", JSONArray().put(
                    JSONObject().put("type", "card").put("text", "Board card").put("children", JSONArray().put(canvas))))).toString())
            fixture.launch().use { scenario ->
                scenario.onActivity { it.showPreview(draft, previews) }
                request.onPreview(ToolDraftPreview(ToolDraftPreviewIdentity(draft.id, "request", 1), spec.json, spec.initialState, spec.ui.toString(),
                    """{"board":{"commands":[]}}"""))
                val board = robot.bounds("Drawing board")
                val shot = checkNotNull(robot.automation.takeScreenshot())
                try {
                    val inside = shot.getPixel(board.centerX(), board.centerY())
                    val card = shot.getPixel(board.left - (8 * shot.density / 160), board.centerY())
                    assertSameColor("the canvas matches its card", inside, card)
                } finally { shot.recycle() }
            }
        }
    }

    private fun ComponentActivity.showPreview(draft: ToolDraft, previews: ToolControllerDraftPreviews) =
        setContent { AlphaTheme { Box(Modifier.safeDrawingPadding()) { GeneratedToolPreview(draft, previews.previewFor(draft.id)) } } }

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

    private inline fun withFixture(provider: ChatProvider, block: (ShellFixture, ToolStore) -> Unit) {
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
        val spec = ToolSpec.parse(definition(title))
        return ToolDraftPreview(ToolDraftPreviewIdentity(draftId, requestId, attempt), spec.json,
            spec.initialState, spec.ui.toString(), surfaces(title, "first", "second"))
    }

    private object Provider : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
            error("The workflow publishes previews directly")
    }

    private fun definition(title: String): String {
        val control = JSONObject("""{"type":"button","text":"$title action","action":"action"}""")
        return JSONObject().put("formatVersion", 3).put("title", title).put("description", "").put("capabilities", JSONArray())
            .put("stateSchema", JSONObject().put("count", "number")).put("initialState", JSONObject().put("count", 0))
            .put("surfaces", JSONArray("""[{"id":"first","width":64,"height":64,"x":0,"y":0},
                {"id":"second","width":64,"height":64,"x":32,"y":0}]"""))
            .put("source", "return {state:input.state,surfaces:${surfaces(title, "first", "second")}};")
            .put("ui", control).toString()
    }

    private fun surfaces(title: String, first: String, second: String) = """{
        "$second":{"commands":[{"type":"rect","x":-48,"y":0,"width":112,"height":64,"color":"#00FF00"},
            {"type":"text","x":4,"y":16,"size":8,"text":"$title second","color":"accent"}]},
        "$first":{"commands":[{"type":"rect","x":0,"y":0,"width":64,"height":64,"color":"#FF00FF"},
            {"type":"text","x":4,"y":16,"size":8,"text":"$title first","color":"accent"}]}}"""
}
