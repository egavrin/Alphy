package com.alpha.assistant.tools

import com.alpha.assistant.ui.seedOpenChat
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RouteStackWorkflowTest {
    private val robot = ShellRobot("plan11-route-stack")
    private val title = "Маршрут по городу"
    private val push = "Открыть карточку"
    private val again = "Открыть карточку ещё раз"
    private val details = "Карточка маршрута"
    private val modal = "Диалог маршрута"

    @Test fun embeddedAndFullHostsShareRoutesAndBackRespectsOverlays() {
        val fixture = ShellFixture("plan11-route")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { tools ->
                val spec = ToolSpec.parse(definition().toString())
                tools.saveTool(ToolRecord("route", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true), true)
                fixture.store.saveConversation(Conversation("chat", "Маршрут", 1, 1, messages = listOf(
                    Message("artifact", "assistant", "", 1, "complete", toolId = "route", toolRevision = 1),
                )))
                fixture.store.seedOpenChat("chat")
                fixture.install()
                fun saved() = checkNotNull(tools.tool("route"))
                fun state() = JSONObject(saved().state)
                fun awaitStack(vararg routes: String) = robot.waitUntil {
                    fixture.model?.tools?.busy == false && state().getJSONArray("stack").toString() == JSONArray(routes.toList()).toString()
                }
                fixture.launch().use { scenario ->
                    robot.reveal(push)
                    robot.click(push)
                    awaitStack("home", "detail")
                    assertTrue(robot.reveal(details).isVisibleToUser)
                    robot.reveal(robot.text(R.string.tool_route_back))

                    robot.openDrawer(scenario)
                    robot.rotate(scenario, landscape = true)
                    robot.awaitDrawerOpen(scenario)
                    assertTrue(robot.reveal("Маршрут").isVisibleToUser)
                    robot.back()
                    robot.awaitDrawerClosed(scenario)
                    awaitStack("home", "detail")
                    robot.rotate(scenario, landscape = false)

                    val open = robot.text(R.string.tool_open)
                    robot.reveal(open)
                    robot.click(open)
                    robot.waitFor(robot.text(R.string.tool_content))
                    robot.reveal(again)
                    robot.click(again)
                    awaitStack("home", "detail", "detail")
                    scenario.recreate()
                    robot.waitFor(robot.text(R.string.tool_content))
                    robot.reveal(details)
                    awaitStack("home", "detail", "detail")

                    robot.rotate(scenario, landscape = true)
                    robot.reveal("Показать диалог")
                    val paneWindow = checkNotNull(robot.diagnostics.freshRoot()).windowId
                    robot.click("Показать диалог")
                    robot.waitUntil {
                        robot.find(modal)?.let { it.isVisibleToUser && it.windowId != paneWindow } == true
                    }
                    robot.back()
                    robot.waitGone(modal)
                    assertFalse(state().getBoolean("dialog"))
                    awaitStack("home", "detail", "detail")
                    assertEquals(0, state().getInt("pops"))
                    robot.rotate(scenario, landscape = false)

                    robot.click(robot.text(R.string.tool_options))
                    robot.click(robot.text(R.string.tool_details_desktop))
                    val closeDetails = robot.text(R.string.tool_details_close)
                    robot.waitFor(closeDetails)
                    robot.back()
                    robot.waitGone(closeDetails)
                    awaitStack("home", "detail", "detail")

                    val old = saved()
                    robot.back()
                    awaitStack("home", "detail")
                    assertEquals(1, state().getInt("pops"))
                    robot.instrumentation.runOnMainSync {
                        checkNotNull(fixture.model).tools.interact(old, "routeBack", JSONArray("""["home","detail","detail"]"""))
                    }
                    robot.waitUntil { fixture.model?.tools?.let { !it.busy && it.error != null } == true }
                    awaitStack("home", "detail")
                    assertEquals(1, state().getInt("pops"))
                    robot.click(robot.text(R.string.tool_dismiss))

                    robot.click(robot.text(R.string.tool_library_back))
                    robot.waitFor(robot.text(R.string.composer_label))
                    robot.openDrawer(scenario)
                    robot.click(robot.text(R.string.mini_apps))
                    robot.awaitDrawerClosed(scenario)
                    robot.waitFor(robot.text(R.string.tool_library_content))
                    robot.reveal(title)
                    robot.click(title)
                    robot.waitFor(robot.text(R.string.tool_content))
                    robot.reveal(details)
                    awaitStack("home", "detail")
                    val routeBack = robot.text(R.string.tool_route_back)
                    robot.reveal(routeBack)
                    robot.click(routeBack)
                    awaitStack("home")
                    assertEquals(2, state().getInt("pops"))
                    robot.waitGone(routeBack)
                    robot.back()
                    robot.waitFor(robot.text(R.string.tool_library_content))
                    awaitStack("home")
                    assertEquals(2, state().getInt("pops"))
                }
                assertEquals(1, tools.revisions("route").size)
                assertEquals(0, fixture.provider.calls)
            }
        } finally { fixture.remove() }
    }

    @Test fun onlyTheActiveRouteAndItsExactNonRootBackSnapshotCanAct() {
        val spec = ToolSpec.parse(definition().toString())
        val root = JSONObject(spec.initialState)
        val ui = spec.ui
        ToolUiCatalog.validateAction(ui, "push", null, root)
        rejects { ToolUiCatalog.validateAction(ui, "again", null, root) }
        rejects { ToolUiCatalog.validateAction(ui, "routeBack", JSONArray("""["home"]"""), root) }
        val active = root.put("stack", JSONArray("""["home","detail","detail"]"""))
        ToolUiCatalog.validateAction(ui, "again", null, active)
        ToolUiCatalog.validateAction(ui, "routeBack", JSONArray("""["home","detail","detail"]"""), active)
        rejects { ToolUiCatalog.validateAction(ui, "push", null, active) }
        for (invalid in listOf(null, "detail", JSONArray(), JSONArray("""["home","detail"]"""),
            JSONArray("""["home","outside","detail"]"""), JSONArray("""["home",1,"detail"]"""))) {
            rejects { ToolUiCatalog.validateAction(ui, "routeBack", invalid, active) }
        }
        val covered = JSONObject().put("type", "column").put("enabled", false).put("children", JSONArray().put(ui))
        rejects { ToolUiCatalog.validateAction(covered, "routeBack", active.getJSONArray("stack"), active) }
        val widget = ToolUiCatalog.widgetNodes(ui, active).single()
        assertEquals("routeStack", widget.getString("type"))
        for (property in listOf("action", "children", "bind", "routes")) assertFalse(widget.has(property))
        assertFalse(ToolUiCatalog.widgetLabel(widget, active).contains(details))
        val legacy = JSONObject("""{"type":"backHandler","action":"legacyBack"}""")
        val retired = definition(legacy).toString()
        rejects { ToolSpec.parse(retired) }
        assertNull(ToolSpec.availablePersisted(retired))
        assertEquals(definition().getString("title"), ToolSpec.storedTitle(retired))
        val saved = ToolRecord("fixture",1,1,spec.json,spec.initialState,legacy.toString(),null,true)
        assertNull(ToolSpec.availablePersisted(saved))
    }

    @Test fun routeDefinitionsAndInactiveContentConsumeTheExistingLimits() {
        fun parse(raw: JSONObject) = ToolSpec.parse(raw.toString())
        for (stack in listOf("[]", """["detail"]""", """["home","outside"]""", """["home",1]""")) {
            rejects { parse(definition().apply { getJSONObject("initialState").put("stack", JSONArray(stack)) }) }
        }
        val maximum = JSONArray(listOf("home") + List(15) { "detail" })
        parse(definition().apply { getJSONObject("initialState").put("stack", maximum) })
        rejects { parse(definition().apply { getJSONObject("stateSchema").getJSONObject("stack").put("maxItems", 17) }) }
        rejects { parse(definition().apply { getJSONObject("initialState").put("stack", JSONArray(maximum.toString()).put("detail")) }) }
        for (routes in listOf("""["home","home"]""", """["home","wrong ID"]""", """["home"]""")) {
            rejects { parse(definition(ui().put("routes", JSONArray(routes)))) }
        }
        val tooMany = JSONArray((0..16).map { "route$it" })
        rejects { parse(definition(ui().put("routes", tooMany).put("children", JSONArray(List(17) { text() })))) }
        val inactiveInvalid = ui().apply { getJSONArray("children").put(1, JSONObject("""{"type":"outside"}""")) }
        rejects { parse(definition(inactiveInvalid)) }
        val withLegacy = ui().apply {
            getJSONArray("children").put(1, JSONObject("""{"type":"backHandler","action":"legacyBack"}"""))
        }
        rejects { parse(definition(withLegacy)) }
        val nested = ui().apply { getJSONArray("children").put(1, ui().put("action", "otherBack")) }
        rejects { parse(definition(nested)) }
        val oversized = ui().apply {
            getJSONArray("children").put(1, column(List(12) { column(List(45) { text() }) }))
        }
        rejects { parse(definition(oversized)) }
        val largeInactive = ui().apply {
            val chart = JSONObject("""{"type":"chart","kind":"line","text":"Данные","categories":"labels","series":[{"text":"Значения","bind":"values"}]}""")
            getJSONArray("children").put(1, column(List(8) { chart }))
        }
        rejects {
            parse(definition(largeInactive).apply {
                getJSONObject("stateSchema").put("labels", JSONObject("""{"type":"array","items":"string","maxItems":256}"""))
                    .put("values", JSONObject("""{"type":"array","items":"number","maxItems":256}"""))
                getJSONObject("initialState").put("labels", JSONArray(List(256) { "А" })).put("values", JSONArray(List(256) { 1 }))
            })
        }
    }

    private fun rejects(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
    private fun text() = JSONObject("""{"type":"text","text":"Данные"}""")
    private fun column(children: List<JSONObject>) = JSONObject().put("type", "column").put("children", JSONArray(children))
    private fun ui() = JSONObject("""{
        "type":"routeStack","text":"Навигация маршрута","bind":"stack","action":"routeBack","routes":["home","detail"],
        "children":[
            {"type":"button","text":"$push","action":"push"},
            {"type":"column","children":[
                {"type":"text","text":"$details"},
                {"type":"button","text":"$again","action":"again"},
                {"type":"button","text":"Показать диалог","action":"showDialog"},
                {"type":"dialog","text":"$modal","visibleBind":"dialog","dismissAction":"closeDialog",
                    "children":[{"type":"text","text":"Проверка маршрута"}]}
            ]}
        ]
    }""")

    private fun definition(ui: JSONObject = ui()) = JSONObject().put("formatVersion", 3).put("title", title)
        .put("description", "Локальная навигация").put("capabilities", JSONArray()).put("ui", ui)
        .put("stateSchema", JSONObject("""{"stack":{"type":"array","items":"string","maxItems":16},"dialog":"boolean","pops":"number"}"""))
        .put("initialState", JSONObject("""{"stack":["home"],"dialog":false,"pops":0}"""))
        .put("source", """
            const state = input.state;
            if (input.event.actionId === 'push' || input.event.actionId === 'again') state.stack.push('detail');
            if (input.event.actionId === 'showDialog') state.dialog = true;
            if (input.event.actionId === 'closeDialog') state.dialog = false;
            if (input.event.actionId === 'routeBack') {
                if (JSON.stringify(input.event.value) !== JSON.stringify(state.stack)) throw new Error('Invalid route snapshot');
                state.stack.pop();
                state.pops++;
            }
            return {state};
        """.trimIndent())
}
