package com.alpha.assistant.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as testFoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.sandbox.SurfaceFrameMetrics
import com.alpha.assistant.tools.ArkanoidFixture
import com.alpha.assistant.tools.ToolDraft
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class AdaptiveShellWorkflowTest {
    @get:Rule val folds = WindowLayoutInfoPublisherRule()

    private val robot = ShellRobot("adaptive-shell")
    private val composer get() = robot.text(R.string.composer_label)
    private val refinement get() = robot.text(R.string.tool_refine_request)

    @Test fun automationEditorKeepsSelectionAndDraftAcrossWidthAndHingeChanges() {
        val fixture = ShellFixture("automation-adaptive")
        val definition = JSONObject("""{"trigger":{"type":"manual"},"conditions":{},"actions":[
            {"id":"notice","capability":"notification.post","args":{"title":"Fixture","text":"Fixture"}}]}""")
        val name = "Adaptive automation"
        val row = robot.text(R.string.tool_open_automation, name)
        val draft = "Unsaved automation name"
        val editor = robot.text(R.string.automation_editor_edit)
        val field = robot.text(R.string.automation_editor_name)
        val list = "automation-list"
        val detail = "automation-detail"
        try {
            AutomationStore(fixture.context, "${fixture.name}.db-tools.db-automations.db").use { store ->
                store.save(null, definition.put("name", "Another automation"))
                store.save(null, definition.put("name", name))
            }
            fixture.install(saveKey = false)
            fixture.launch().use { scenario ->
                val size = constrain(scenario, fixture, 420)
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.automations))
                robot.awaitDrawerClosed(scenario)
                robot.click(row)
                robot.click(robot.text(R.string.automation_edit))
                robot.edit(field, draft)
                if (robot.keyboardTop(scenario) != null) {
                    robot.back()
                    robot.waitUntil { robot.keyboardTop(scenario) == null }
                }
                assertEquals(draft, robot.field(field).text.toString())

                scenario.onActivity { size.value = 900 }
                robot.waitUntil { robot.composed(scenario, list) }
                assertEquals(draft, robot.field(field).text.toString())

                val hinge = publishHinge(scenario, FoldingFeature.Orientation.VERTICAL, 450)
                robot.waitUntil {
                    val left = robot.composedBounds(scenario, list).first
                    val right = robot.composedBounds(scenario, detail).first
                    left.width() > 0 && right.width() > 0 && left.right <= hinge.left && right.left >= hinge.right
                }
                assertEquals(draft, robot.field(field).text.toString())
                robot.diagnostics.capture("automation-editor-hinge")

                folds.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
                scenario.onActivity { size.value = 420 }
                robot.waitUntil { !robot.composed(scenario, list) }
                assertEquals(draft, robot.field(field).text.toString())
                robot.back()
                robot.waitGone(editor)
                robot.waitFor(robot.text(R.string.automation_rule_options))
                robot.back()
                robot.waitFor(row)
                robot.waitGone(robot.text(R.string.automation_rule_options))
                robot.back()
                robot.waitUntil { robot.composed(scenario, "desktop") }
                assertEquals(0, fixture.provider.calls)
            }
        } finally {
            fixture.remove()
        }
    }

    @Test fun narrowWideNarrowKeepsDraftsAndOneAppSession() = withTool { fixture, tools, scenario ->
        val size = constrain(scenario, fixture, 420)
        robot.edit(composer, "Chat draft survives")
        openFromChat(scenario)
        robot.waitGone(composer)
        robot.waitGone(refinement)
        val session = awaitSession()

        scenario.onActivity { size.value = 900 }
        awaitSingleApp(scenario)
        robot.reveal("Increment item")
        robot.click("Increment item")
        robot.waitUntil { count(tools) == 1 }
        assertSession(session)
        robot.diagnostics.capture("wide-after-action")

        scenario.onActivity { size.value = 420 }
        awaitSingleApp(scenario)
        assertEquals(1, count(tools))
        assertSession(session)
        robot.back()
        assertEquals("Chat draft survives", robot.field(composer).text.toString())
        openFromChat(scenario)
        robot.click(robot.text(R.string.tool_refine))
        robot.waitUntil { fixture.model?.snapshot?.selected?.refinementTargetId == TOOL_ID }
        assertEquals("", robot.field(composer).text.toString())
        robot.edit(composer, "App change draft")
        assertEquals("Chat draft survives", fixture.model?.snapshot?.selected?.draft?.text)
        assertEquals("App change draft", fixture.model?.snapshot?.selected?.refinementDraft?.text)
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun delayedRefinementKeepsTheNewShellDestination() = withTool { fixture, tools, scenario ->
        val model = checkNotNull(fixture.model)
        val original = checkNotNull(tools.tool(TOOL_ID))
        tools.saveTool(original.copy(lastConversationId = "missing-chat"), revision = false)
        openFromChat(scenario)

        val db = fixture.store.writableDatabase
        db.beginTransactionNonExclusive()
        val pendingChat = try {
            robot.click(robot.text(R.string.tool_refine))
            robot.waitUntil { model.snapshot.conversations.any { it.refinementTargetId == TOOL_ID } }
            val pending = model.snapshot.conversations.single { it.refinementTargetId == TOOL_ID }.id
            assertTrue(fixture.store.load().conversations.none { it.id == pending })

            robot.click(robot.text(R.string.tool_options))
            robot.reveal(robot.text(R.string.tool_chat))
            robot.click(robot.text(R.string.tool_chat))
            robot.waitFor(composer)
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            assertEquals("chat", model.snapshot.selectedId)
            pending
        } finally {
            db.endTransaction()
        }
        robot.waitUntil { tools.tool(TOOL_ID)?.lastConversationId == pendingChat }
        robot.awaitFrames(15)
        assertEquals("The delayed Edit must preserve the origin chat", "chat", model.snapshot.selectedId)
        assertTrue("The delayed Edit must leave Settings visible", robot.composed(scenario, "settings-pane"))
        assertFalse(robot.composed(scenario, "mini-app"))
        assertEquals(TOOL_ID, fixture.store.load().conversations.single { it.id == pendingChat }.refinementTargetId)
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun libraryOpenRestoresSearchAfterWideWindowAndHinge() = withTool { fixture, tools, scenario ->
        val size = constrain(scenario, fixture, 420)
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.mini_apps))
        robot.awaitDrawerClosed(scenario)
        val library = robot.text(R.string.tool_library_content)
        val search = robot.text(R.string.tool_search)
        fun awaitPairedPreview() {
            robot.waitFor(robot.text(R.string.tool_preview_label))
            robot.waitUntil {
                val catalog = robot.composedBoundsOrNull(scenario, "mini-app-library")?.first ?: return@waitUntil false
                val app = robot.composedBoundsOrNull(scenario, "mini-app")?.first ?: return@waitUntil false
                catalog.width() > 0 && app.width() > 0 && !Rect.intersects(catalog, app)
            }
        }
        robot.waitFor(library)
        robot.edit(search, "Adaptive")
        robot.click("Adaptive fixture")
        robot.waitFor(robot.text(R.string.tool_library_back))
        robot.waitGone(library)
        robot.waitGone(composer)
        val session = awaitSession()

        scenario.onActivity { size.value = 900 }
        awaitSingleApp(scenario)
        robot.waitGone(library)
        assertSession(session)
        robot.click(robot.text(R.string.tool_options))
        robot.waitFor(robot.text(R.string.tool_versions))
        robot.back()
        robot.waitGone(robot.text(R.string.tool_versions))
        assertSession(session)
        robot.reveal("Increment item")
        robot.click("Increment item")
        robot.waitUntil { count(tools) == 1 }

        val book = publishHinge(scenario, FoldingFeature.Orientation.VERTICAL, 360)
        robot.waitUntil {
            val app = robot.composedBoundsOrNull(scenario, "mini-app")?.first ?: return@waitUntil false
            app.width() > 0 && app.left >= book.right
        }
        robot.waitGone(library)
        assertSession(session)
        robot.back()
        robot.waitFor(library)
        robot.waitGone(robot.text(R.string.tool_library_back))
        assertEquals("Adaptive", robot.field(search).text.toString())

        folds.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
        val saved = checkNotNull(tools.tool(TOOL_ID))
        val prompt = "Adaptive draft preview"
        tools.saveDraft(ToolDraft("adaptive-preview", saved.id, saved.revision, saved.stateVersion,
            prompt, saved.spec, saved.state, saved.ui, saved.conversationId))
        robot.click(prompt)
        awaitPairedPreview()
        robot.back()
        robot.waitUntil { !robot.composed(scenario, "mini-app") }
        assertNotNull(tools.draft("adaptive-preview"))
        assertEquals("Adaptive", robot.field(search).text.toString())
        robot.click(prompt)
        robot.click(robot.text(R.string.tool_discard_preview))
        awaitSingleApp(scenario)
        robot.waitGone(library)
        assertEquals(saved, tools.tool(TOOL_ID))

        robot.back()
        robot.waitFor(library)
        val applyPrompt = "Adaptive draft to apply"
        val revisedSpec = JSONObject(saved.spec).put("title", "Applied adaptive fixture").toString()
        tools.saveDraft(ToolDraft("adaptive-apply", saved.id, saved.revision, saved.stateVersion,
            applyPrompt, revisedSpec, saved.state, saved.ui, conversationId = null))
        robot.click(applyPrompt)
        awaitPairedPreview()
        robot.click(robot.text(R.string.tool_apply_changes))
        robot.waitUntil { tools.tool(TOOL_ID)?.revision == saved.revision + 1 && tools.draft("adaptive-apply") == null }
        awaitSingleApp(scenario)
        robot.waitGone(library)
        robot.waitGone(robot.text(R.string.tool_preview_label))
        robot.waitFor("Applied adaptive fixture")
        robot.back()
        robot.waitFor(library)
        assertEquals("Adaptive", robot.field(search).text.toString())
    }

    @Test fun tabletopAndVerticalHingeKeepControlsInTheirPanels() = withTool(refinementChat = "other") { fixture, tools, scenario ->
        constrain(scenario, fixture, 900, fontScale = 1.5f)
        openFromChat(scenario)
        robot.click(robot.text(R.string.tool_refine))
        awaitPanes(scenario)
        robot.waitUntil { fixture.model?.snapshot?.selectedId == "other" }
        val session = awaitSession()

        val tabletop = publishHinge(scenario, FoldingFeature.Orientation.HORIZONTAL, 390)
        robot.waitUntil {
            val app = robot.composedBounds(scenario, "mini-app").first
            val chat = robot.composedBounds(scenario, "chat").first
            app.height() > 0 && chat.height() > 0 && app.bottom <= tabletop.top && chat.top >= tabletop.bottom
        }
        robot.assertTarget(robot.text(R.string.composer_add_context))
        robot.click(robot.text(R.string.composer_add_context))
        robot.waitFor(robot.text(R.string.context_title))
        robot.back()
        robot.waitGone(robot.text(R.string.context_title))
        assertComposerAboveKeyboard(scenario)
        assertSession(session)
        robot.diagnostics.capture("tabletop-large-font")

        val book = publishHinge(scenario, FoldingFeature.Orientation.VERTICAL, 450)
        robot.waitUntil {
            val app = robot.composedBounds(scenario, "mini-app").first
            val chat = robot.composedBounds(scenario, "chat").first
            app.width() > 0 && chat.width() > 0 && chat.right <= book.left && app.left >= book.right
        }
        val plus = robot.text(R.string.composer_add_context)
        val title = robot.text(R.string.context_title)
        val anchor = robot.bounds(plus)
        robot.click(plus)
        assertTrue(robot.bounds(title).bottom < anchor.top)
        assertTrue(robot.bounds(robot.text(R.string.cancel)).right <= book.left)
        val camera = robot.bounds(robot.text(R.string.context_camera))
        val photo = robot.bounds(robot.text(R.string.context_photo))
        val file = robot.bounds(robot.text(R.string.context_file))
        assertTrue(camera.right < photo.left && photo.right < file.left)
        assertEquals(camera.centerY(), file.centerY())
        assertTrue(robot.bounds(robot.text(R.string.context_calendar)).top > file.bottom)
        assertTrue(robot.bounds(robot.text(R.string.context_paste)).top > robot.bounds(robot.text(R.string.context_calendar)).bottom)
        robot.back()
        robot.waitGone(title)
        assertComposerAboveKeyboard(scenario)
        assertSession(session)
        assertTrue(tools.effects(TOOL_ID).isEmpty())
        robot.diagnostics.capture("vertical-hinge-large-font")

        val narrowBook = publishHinge(scenario, FoldingFeature.Orientation.VERTICAL, 280)
        robot.waitFor(composer)
        robot.waitUntil { !robot.composed(scenario, "mini-app") }
        val chat = robot.composedBounds(scenario, "chat").first
        assertTrue(chat.width() > 0 && chat.left >= narrowBook.right)
        robot.back()
        robot.waitFor(composer)
        assertNull(fixture.model?.tools?.selectedId)
        assertEquals("chat", fixture.model?.snapshot?.selectedId)
    }

    @Test fun openAppSurvivesActivityRecreationAndProtectedActionStaysDenied() = withTool { fixture, tools, scenario ->
        openFromChat(scenario)
        robot.click(robot.text(R.string.tool_refine))
        robot.waitUntil { fixture.model?.snapshot?.selected?.refinementTargetId == TOOL_ID }
        robot.edit(composer, "Keep this refinement")
        openFromChat(scenario)
        assertTrue(tools.effects(TOOL_ID).isEmpty())
        scenario.recreate()
        robot.waitGone(refinement)
        assertEquals("Keep this refinement", fixture.model?.snapshot?.selected?.refinementDraft?.text)
        assertEquals(TOOL_ID, fixture.model?.tools?.selectedId)
        assertTrue(tools.effects(TOOL_ID).isEmpty())
        robot.reveal("Request arrival")
        robot.click("Request arrival")
        robot.waitUntil { tools.effects(TOOL_ID).singleOrNull()?.result?.status == "denied" }
        assertEquals(0, tools.readableDatabase.rawQuery("SELECT COUNT(*) FROM maps_reached", null).use { it.moveToFirst(); it.getInt(0) })

        scenario.onActivity { checkNotNull(fixture.model).openSurface(null, settings = true) }
        robot.reveal(robot.text(R.string.key_label))
        assertTrue(robot.composed(scenario, "settings-detail"))
        robot.withBackInput(scenario) { input -> scenario.onActivity { input.complete() } }
        robot.waitUntil { !robot.composed(scenario, "settings-pane") }
        assertEquals("denied", tools.effects(TOOL_ID).single().result.status)
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun wideSettingsNameFieldEditsWithoutOpeningADetail() = withTool(selectedChat = false) { fixture, _, scenario ->
        constrain(scenario, fixture, 900)
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.composed(scenario, "settings-detail") }
        val label = robot.text(R.string.settings_name_label)
        robot.reveal(label)
        robot.edit(label, "Евгений")
        robot.waitUntil { fixture.store.load().userName == "Евгений" }
        robot.reveal(robot.text(R.string.key_label))
    }

    @Test fun wideNewChatKeepsDesktopBackPath() = withTool(selectedChat = false) { fixture, _, scenario ->
        constrain(scenario, fixture, 900)
        robot.edit(composer, "New chat draft")
        scenario.onActivity { checkNotNull(fixture.model).openSurface(TOOL_ID) }
        awaitSingleApp(scenario)
        robot.back()
        robot.waitUntil { robot.composed(scenario, "desktop") }
        assertEquals("New chat draft", robot.field(composer).text.toString())
    }

    @Test fun immersiveToolStaysSingleOnWideWindow() = withTool(immersive = true) { fixture, _, scenario ->
        constrain(scenario, fixture, 900)
        scenario.onActivity { checkNotNull(fixture.model).openSurface(TOOL_ID) }
        robot.click(robot.text(R.string.tool_game_controls_open))
        robot.waitFor(robot.text(R.string.tool_full_screen_exit))
        robot.waitGone(composer)
        robot.waitUntil { !robot.composed(scenario, "chat") }
    }

    private fun openFromChat(scenario: ActivityScenario<CloudTestActivity>) {
        robot.reveal(robot.text(R.string.tool_open))
        robot.click(robot.text(R.string.tool_open))
        robot.waitUntil { robot.composed(scenario, "mini-app") }
        robot.awaitFrames(30)
        assertTrue(robot.composed(scenario, "mini-app"))
    }

    private fun awaitSingleApp(scenario: ActivityScenario<CloudTestActivity>) {
        robot.waitUntil { robot.composed(scenario, "mini-app") }
        robot.waitGone(composer)
        robot.waitUntil { !robot.composed(scenario, "chat") }
    }

    private fun awaitPanes(scenario: ActivityScenario<CloudTestActivity>) {
        robot.waitFor(composer)
        robot.waitUntil {
            val app = robot.composedBounds(scenario, "mini-app").first
            val chat = robot.composedBounds(scenario, "chat").first
            app.width() > 0 && chat.width() > 0 && !Rect.intersects(app, chat)
        }
    }

    private fun awaitSession(): Long {
        robot.waitUntil { SurfaceFrameMetrics.snapshot()?.let { it.status == "RUNNING" && it.validatedSnapshots > 0 } == true }
        return checkNotNull(SurfaceFrameMetrics.snapshot()).generation
    }

    private fun assertSession(expected: Long) {
        robot.awaitFrames(30)
        assertEquals(expected, awaitSession())
    }

    private fun assertComposerAboveKeyboard(scenario: ActivityScenario<CloudTestActivity>) {
        assertTrue(robot.field(composer).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        robot.waitUntil { robot.keyboardTop(scenario) != null }
        robot.awaitFrames(30)
        val keyboardTop = checkNotNull(robot.keyboardTop(scenario))
        assertTrue(robot.bounds(composer).bottom <= keyboardTop)
        assertTrue(robot.assertTarget(robot.text(R.string.composer_add_context)).bottom <= keyboardTop)
        robot.back()
        robot.waitUntil { robot.keyboardTop(scenario) == null }
    }

    private fun constrain(scenario: ActivityScenario<CloudTestActivity>, fixture: ShellFixture, width: Int, fontScale: Float = 1f) =
        mutableStateOf(width).also { widthDp ->
            var tabletSize = false
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val density = activity.resources.displayMetrics.density
                tabletSize = decor.width >= 900 * density && decor.height >= 840 * density
            }
            assumeTrue("Use a test window of at least 900 × 840 dp", tabletSize)
            scenario.onActivity { activity ->
                val model = checkNotNull(fixture.model)
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        Box(Modifier.width(widthDp.value.dp).fillMaxHeight()) {
                            AlphaTheme { AlphaShell(model) }
                        }
                    }
                }
            }
            robot.awaitFrames(10)
        }

    private fun publishHinge(scenario: ActivityScenario<CloudTestActivity>, orientation: FoldingFeature.Orientation, centerDp: Int): Rect {
        lateinit var bounds: Rect
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val hinge = testFoldingFeature(activity, center = (centerDp * density).roundToInt(),
                size = (24 * density).roundToInt(), orientation = orientation)
            bounds = hinge.bounds
            folds.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(hinge)))
        }
        robot.awaitFrames(30)
        return bounds
    }

    private fun count(tools: ToolStore) = JSONObject(checkNotNull(tools.tool(TOOL_ID)).state).getInt("count")

    private fun withTool(selectedChat: Boolean = true, immersive: Boolean = false, refinementChat: String = "chat",
        block: (ShellFixture, ToolStore, ActivityScenario<CloudTestActivity>) -> Unit) {
        val fixture = ShellFixture("adaptive")
        val tools = ToolStore(fixture.context, "${fixture.name}.db-tools.db")
        try {
            val json = JSONObject(ArkanoidFixture.spec()).put("title", "Adaptive fixture")
                .put("presentation", JSONObject().put("mode", if (immersive) "immersive" else "app"))
                .put("stateSchema", JSONObject("""{"count":"number"}"""))
                .put("initialState", JSONObject("""{"count":0}"""))
                .put("ui", JSONObject("""{"type":"column","children":[{"type":"button","text":"Increment item","action":"increment"},{"type":"button","text":"Request arrival","action":"arrival"}]}"""))
                .put("capabilities", JSONArray().put("maps.markReached"))
                .put("capabilityResults", true)
                .put("source", """
                    const {event, state} = input;
                    if (event.type === 'action' && event.actionId === 'increment') state.count++;
                    const effects = event.type === 'action' && event.actionId === 'arrival'
                        ? [{id:'arrival',capability:'maps.markReached',args:{version:1,stopId:'A',label:'Stop A',latE6:31230000,lonE6:121473000,coordinateSystem:'wgs84'}}] : [];
                    return {state,effects,surfaces:{main:{commands:[{type:'rect',x:0,y:0,width:320,height:420,color:'#123456'}]}}};
                """.trimIndent())
            val spec = ToolSpec.parse(json.toString())
            tools.saveTool(ToolRecord(TOOL_ID, 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat",
                true, lastConversationId = refinementChat), true)
            fixture.store.saveConversation(Conversation("chat", "Adaptive discussion", 1, 1,
                messages = listOf(Message("app", "assistant", "A mini-app for this discussion.", 1, "complete", toolId = TOOL_ID, toolRevision = 1))))
            if (refinementChat != "chat") {
                fixture.store.saveConversation(Conversation(refinementChat, "Refinement discussion", 2, 2))
            }
            if (selectedChat) fixture.store.seedOpenChat("chat")
            fixture.install()
            fixture.launch().use { scenario ->
                robot.waitUntil { fixture.model?.let { !it.loading && !it.tools.busy } == true }
                block(fixture, tools, scenario)
            }
        } finally {
            tools.close()
            fixture.remove()
        }
    }

    private companion object { const val TOOL_ID = "adaptive-tool" }
}
