package com.alpha.assistant.ui

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
import com.alpha.assistant.R
import com.alpha.assistant.automations.AutomationReceiver
import com.alpha.assistant.automations.AutomationRule
import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.surfaces.WidgetPreferences
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class DesktopGridWorkflowTest {
    private val fixture = ShellFixture("desktop-grid")
    private val robot = ShellRobot("desktop-grid")
    private val toolsName = "${fixture.name}.db-tools.db"
    private val rulesName = "$toolsName-automations.db"
    private val desktopName = "$toolsName-desktop.db"

    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase(desktopName)
    }

    @Test fun tileChatFullViewAndWidgetShareOneCounterAcrossReopen() {
        val id = "shared-counter"
        val title = "Shared counter"
        seedTool(id, title, "Shared total", "Add one", placed = true)
        fixture.store.saveConversation(Conversation("chat", "Counter discussion", 1, 1,
            messages = listOf(Message("app", "assistant", "Your counter", 1, "complete", toolId = id, toolRevision = 1))))
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            reveal("Shared total: 0")
            click("Add one")
            reveal("Shared total: 1")
            click(R.string.desktop_edit)
            reveal(robot.text(R.string.desktop_done))
            assertNull(robot.find("Add one"))
            click(R.string.desktop_done)

            robot.openDrawer(scenario)
            click("Counter discussion")
            robot.awaitDrawerClosed(scenario)
            reveal("Shared total: 1")
            click(R.string.tool_open)
            reveal(robot.text(R.string.tool_content))
            reveal("Shared total: 1")

            val context = object : ContextWrapper(fixture.context) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    baseContext.getSharedPreferences("${fixture.name}-$name", mode)
            }
            val widgetId = 101
            ToolStore(fixture.context, toolsName).use { store ->
                val tool = checkNotNull(store.tool(id))
                val engine = ToolEngine(store, ToolGenerationService(fixture.provider, SandboxClient(context)))
                ToolWidgets.bind(context, widgetId, id, WidgetPreferences())
                try {
                    val action = ToolWidgets.actionIntent(context, widgetId, tool, "add", null)
                    runBlocking {
                        assertTrue(ToolWidgets.perform(context, action, store, engine, locked = false))
                        assertFalse(ToolWidgets.perform(context, action, store, engine, locked = false))
                    }
                } finally {
                    ToolWidgets.remove(context, widgetId)
                }
            }
            reveal("Shared total: 2")
            assertEquals(2, count(id))
            robot.back()
            reveal(robot.text(R.string.tool_library_content))
            robot.back()
            reveal(robot.text(R.string.tool_open))
            reveal("Shared total: 2")
            robot.back()
            reveal(robot.text(R.string.desktop_open_tile, title))
            reveal("Shared total: 2")
            robot.diagnostics.capture("desktop-shared-counter")
        }
        fixture.launch().use {
            reveal("Shared total: 2")
            assertEquals(2, count(id))
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun tileActionThatRequestsAnEffectPreservesTheSavedState() {
        val id = "effect-counter"
        seedTool(id, "Effect counter", "Effect total", "Try alert", placed = true, alert = true)
        fixture.install(saveKey = false)
        fixture.launch().use {
            val before = record(id)
            reveal("Effect total: 0")
            click("Try alert")
            reveal("Откройте мини-приложение, чтобы выполнить это системное действие.")
            assertEquals(before, record(id))
            ToolStore(fixture.context, toolsName).use { assertTrue(it.effects(id).isEmpty()) }
            click(R.string.desktop_open_tile, "Effect counter")
            reveal(robot.text(R.string.tool_content))
            reveal("Effect total: 0")
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun pendingTileActionsExpireAcrossVisibilityAndAvailabilityChanges() = runBlocking {
        val id = "pending-counter"
        val header = robot.text(R.string.desktop_open_tile, "Pending counter")
        seedTool(id, "Pending counter", "Pending total", "Add pending", placed = true)
        fixture.install(saveKey = false)
        ToolStore(fixture.context, toolsName).use { store ->
            fixture.launch().use { scenario ->
                val controller = checkNotNull(fixture.model).tools
                suspend fun queueAction(change: () -> Unit) = coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val barrier = launch(Dispatchers.IO) {
                        ArtifactCoordinator.get(store, id).ordered {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    try {
                        withTimeout(5_000) { entered.await() }
                        click("Add pending")
                        robot.waitUntil { controller.busy }
                        change()
                    } finally {
                        release.complete(Unit)
                        barrier.join()
                    }
                    robot.waitUntil { !controller.busy }
                    assertEquals(0, count(id))
                    assertTrue(store.effects(id).isEmpty())
                }
                queueAction {
                    robot.openDrawer(scenario)
                    robot.back()
                    robot.awaitDrawerClosed(scenario)
                }
                queueAction {
                    store.setDesktopPlacement(checkNotNull(store.tool(id)), false)
                    robot.waitGone(header)
                }
                store.setDesktopPlacement(checkNotNull(store.tool(id)), true)
                queueAction {
                    store.setArchived(checkNotNull(store.tool(id)), true)
                    robot.waitGone(header)
                }
                store.setArchived(checkNotNull(store.tool(id)), false)
                val old = checkNotNull(store.tool(id))
                click("Add pending")
                reveal("Pending total: 1")
                robot.waitUntil { !controller.busy }
                robot.instrumentation.runOnMainSync { controller.interactFromDesktop(old, "add", null) { true } }
                robot.waitUntil { !controller.busy }
                assertEquals(1, count(id))
                assertTrue(store.effects(id).isEmpty())
            }
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun placementOrderAndNativeStateSurviveReopen() {
        seedTool("water", "Water counter", "Glasses", "Add a glass")
        seedTool("steps", "Steps counter", "Steps", "Add a step", createdAt = 2)
        fixture.install()

        fixture.launch().use { scenario ->
            click(R.string.desktop_open_library)
            placeFromLibrary("water", "Water counter")
            robot.openDrawer(scenario)
            click(R.string.mini_apps)
            robot.awaitDrawerClosed(scenario)
            placeFromLibrary("steps", "Steps counter")

            click(R.string.desktop_open_tile, "Water counter")
            robot.waitFor(robot.text(R.string.tool_content))
            reveal("Glasses: 0")
            click("Add a glass")
            robot.waitUntil { count("water") == 1 }
            reveal("Glasses: 1")
            click("Add a glass")
            robot.waitUntil { count("water") == 2 }
            click(R.string.tool_options)
            click(R.string.tool_chat)

            click(R.string.desktop_open_tile, "Steps counter")
            click("Add a step")
            robot.waitUntil { count("steps") == 1 }
            click(R.string.tool_options)
            click(R.string.tool_chat)

            click(R.string.desktop_edit)
            val earlier = robot.text(R.string.desktop_move_earlier, "Steps counter")
            reveal(earlier)
            robot.waitUntil { robot.find(earlier)?.isEnabled == true }
            robot.assertTarget(earlier)
            robot.click(earlier)
            val waterEarlier = robot.text(R.string.desktop_move_earlier, "Water counter")
            reveal(waterEarlier)
            robot.waitUntil { robot.find(waterEarlier)?.isEnabled == true }
            click(R.string.desktop_done)
        }

        fixture.launch().use {
            click(R.string.desktop_edit)
            val stepsLater = robot.text(R.string.desktop_move_later, "Steps counter")
            reveal(stepsLater)
            robot.waitUntil { robot.find(stepsLater)?.isEnabled == true }
            assertFalse(reveal(robot.text(R.string.desktop_move_earlier, "Steps counter")).isEnabled)
            val remove = robot.text(R.string.desktop_remove_tile, "Water counter")
            reveal(remove)
            robot.assertTarget(remove)
            robot.click(remove)
            robot.waitUntil { record("water")?.onDesktop == false }
            assertEquals(2, count("water"))
            robot.waitGone(remove)
            click(R.string.desktop_done)
            click(R.string.desktop_open_tile, "Steps counter")
            reveal("Steps: 1")
        }
        fixture.launch().use { scenario ->
            click(R.string.desktop_open_tile, "Steps counter")
            reveal("Steps: 1")
            assertFalse(checkNotNull(record("water")).onDesktop)
            click(R.string.tool_options)
            click(R.string.tool_chat)
            robot.openDrawer(scenario)
            click(R.string.mini_apps)
            robot.awaitDrawerClosed(scenario)
            click("Water counter")
            reveal("Glasses: 2")
            click(R.string.tool_options)
            click(R.string.tool_chat)
            click(R.string.desktop_edit)
            click(R.string.desktop_remove_tile, "Steps counter")
            robot.waitGone(robot.text(R.string.desktop_done))
            reveal(robot.text(R.string.desktop_open_library))
            assertNull(robot.find(robot.text(R.string.desktop_edit)))
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun failedOrderWriteKeepsSavedOrderAndShowsError() {
        seedTool("first", "First counter", "First", "Increase first", placed = true, createdAt = 1)
        seedTool("second", "Second counter", "Second", "Increase second", placed = true, createdAt = 2)
        fixture.install()

        fixture.launch().use {
            click(R.string.desktop_edit)
            val earlier = robot.text(R.string.desktop_move_earlier, "Second counter")
            reveal(earlier)
            robot.waitUntil { robot.find(earlier)?.isEnabled == true }
            SQLiteDatabase.openDatabase(fixture.context.getDatabasePath(desktopName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER fixture_write_failure BEFORE INSERT ON desktop_items BEGIN SELECT RAISE(ABORT, 'Fixture write failure'); END")
            }
            robot.click(earlier)
            reveal(robot.text(R.string.desktop_layout_failed))
            assertTrue(reveal(earlier).isEnabled)
        }
        fixture.launch().use {
            click(R.string.desktop_edit)
            val earlier = robot.text(R.string.desktop_move_earlier, "Second counter")
            reveal(earlier)
            robot.waitUntil { robot.find(earlier)?.isEnabled == true }
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun deniedCalendarUsesLocalAttentionAndAutomationPlacementRequiresAChoice() {
        assumeTrue(fixture.context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
        val rule = AutomationStore(fixture.context, rulesName).use { rules ->
            rules.save(null, JSONObject("""{"name":"Desk review fixture","trigger":{"type":"manual"},"conditions":{},"actions":[{"id":"notice","capability":"notification.post","args":{"title":"Fixture","text":"Fixture"}}]}"""))
        }
        fixture.install()

        fixture.launch().use {
            robot.waitFor(robot.text(R.string.desktop_now_automation_attention))
            assertEquals(null, robot.find(robot.text(R.string.desktop_now_calendar)))
            reveal(robot.text(R.string.desktop_add_automation))
            assertNull(robot.find(robot.text(R.string.desktop_edit)))
            click(R.string.desktop_add_automation)
            click(R.string.desktop_place_automation, rule.definition.name)
            reveal(robot.text(R.string.desktop_edit))
            val switch = robot.text(R.string.desktop_automation_switch, rule.definition.name)
            reveal(switch)
            robot.assertTarget(switch)
            robot.click(switch)
            reveal("Проверить и включить")
            AutomationStore(fixture.context, rulesName).use { rules ->
                val unchanged = rules.rule(rule.id)
                assertNotNull(unchanged)
                assertFalse(checkNotNull(unchanged).enabled)
                assertEquals(0, unchanged.approvedRevision)
            }
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun desktopPauseCancelsAlarmAndUnchangedRuleResumesInPlace() {
        val rule = seedAutomation("Desktop resume fixture", paused = false)
        val context = fixture.context
        val intent = Intent(context, AutomationReceiver::class.java).setAction(AutomationRuntime.ALARM)
            .setData(Uri.parse("alpha-automation://alarm/${rule.id}"))
        val alarm = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(AlarmManager::class.java)
        try {
            val scheduledAt = System.currentTimeMillis() + 86_400_000L
            manager.set(AlarmManager.RTC_WAKEUP, scheduledAt, alarm)
            AutomationStore(context, rulesName).use { it.next(rule.id, rule.revision, scheduledAt) }
            fixture.install()
            fixture.launch().use {
                val switch = placeAutomation(rule)
                val beforePause = checkNotNull(automation(rule.id))
                assertTrue("Placement preserves the active rule: $beforePause",
                    beforePause.enabled && beforePause.approvedRevision == rule.revision && beforePause.attention.isEmpty())
                assertEquals(scheduledAt, beforePause.nextAt)
                assertNotNull("The alarm exists before Pause", pendingAlarm(context, intent))
                val label = reveal(switch)
                val control = checkNotNull(switchControl(label)) {
                    "No checkable control for $switch. Label class=${label.className}, clickable=${label.isClickable}"
                }
                assertEquals("Active rule switch ${control.className} (${control.stateDescription})", CHECKED_STATE_TRUE,
                    AccessibilityNodeInfoCompat.wrap(control).checked)
                robot.click(switch)
                robot.waitUntil {
                    automation(rule.id)?.enabled == false && pendingAlarm(context, intent) == null
                }
                assertNull(automation(rule.id)?.nextAt)
                robot.waitUntil { switchState(switch) == CHECKED_STATE_FALSE }
                robot.click(switch)
                robot.waitUntil { automation(rule.id)?.enabled == true && switchState(switch) == CHECKED_STATE_TRUE }
                val resumed = checkNotNull(automation(rule.id))
                assertEquals(rule.revision, resumed.approvedRevision)
                assertEquals(rule.revision, resumed.revision)
                assertNull(robot.find(robot.text(R.string.automation_all)))
            }
        } finally {
            manager.cancel(alarm)
            alarm.cancel()
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun pendingAlarm(context: Context, intent: Intent): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun switchControl(label: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(label) { it.parent }.firstOrNull { it.isCheckable && it.isClickable }

    private fun switchState(label: String): Int? = robot.find(label)?.let(::switchControl)?.takeIf { it.isEnabled }
        ?.let { AccessibilityNodeInfoCompat.wrap(it).checked }

    @Test fun desktopResumeOpensReviewForAChangedRevision() {
        val rule = seedAutomation("Desktop stale fixture")
        fixture.install()
        fixture.launch().use {
            val switch = placeAutomation(rule)
            val replacement = AutomationStore(fixture.context, rulesName).use { store ->
                store.save(rule.id, JSONObject(rule.definition.json).put("name", "Desktop updated fixture"), rule.revision)
            }
            robot.click(switch)
            assertReviewRequired(replacement)
            assertEquals(0, automation(rule.id)?.approvedRevision)
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun desktopResumeOpensReviewForAMissingDependency() {
        val rule = seedAutomation("Desktop dependency fixture", JSONObject("""{"id":"set","capability":"tool.state.set","args":{
            "toolId":"missing-${UUID.randomUUID()}","revision":1,"field":"count","value":2}}"""))
        fixture.install()
        fixture.launch().use {
            robot.click(placeAutomation(rule))
            assertReviewRequired(rule)
            assertTrue(checkNotNull(automation(rule.id)).attention.contains("Целевой инструмент"))
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun desktopResumeOpensReviewForDeniedCalendarAccess() {
        assumeTrue(fixture.context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
        val rule = seedAutomation("Desktop access fixture", JSONObject("""{"id":"read","capability":"calendar.read",
            "args":{"calendarId":1,"from":1000,"to":2000}}"""))
        fixture.install()
        fixture.launch().use {
            robot.click(placeAutomation(rule))
            assertReviewRequired(rule)
            assertTrue(checkNotNull(automation(rule.id)).attention.contains("Доступ Android"))
            assertTrue(fixture.context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun desktopResumeReportsADeletedRuleWithoutEnablingAnotherRule() {
        val rule = seedAutomation("Desktop removed fixture")
        val sibling = seedAutomation("Desktop unrelated fixture")
        fixture.install()
        fixture.launch().use {
            val switch = placeAutomation(rule)
            AutomationStore(fixture.context, rulesName).use { store -> store.removeStoppedRule(rule.id, rule.revision) }
            robot.click(switch)
            reveal(robot.text(R.string.automation_missing))
            robot.waitGone(switch)
            assertNull(robot.find(robot.text(R.string.automation_all)))
            assertNull(automation(rule.id))
            assertFalse(checkNotNull(automation(sibling.id)).enabled)
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun placeAutomation(rule: AutomationRule): String {
        click(R.string.desktop_add_automation)
        click(R.string.desktop_place_automation, rule.definition.name)
        val label = robot.text(R.string.desktop_automation_switch, rule.definition.name)
        reveal(label)
        return label
    }

    private fun assertReviewRequired(rule: AutomationRule) {
        reveal(robot.text(R.string.automation_all))
        reveal(rule.definition.name)
        reveal("Проверить и включить")
        val current = checkNotNull(automation(rule.id))
        assertEquals(rule.revision, current.revision)
        assertFalse(current.enabled)
    }

    private fun automation(id: String) = AutomationStore(fixture.context, rulesName).use { it.rule(id) }

    private fun seedAutomation(name: String, action: JSONObject = JSONObject("""{"id":"open","capability":"link.open",
        "args":{"url":"https://example.com/"}}"""), paused: Boolean = true): AutomationRule =
        AutomationStore(fixture.context, rulesName).use { store ->
            val definition = JSONObject("""{"trigger":{"type":"manual"},"conditions":{}}""")
                .put("name", name).put("actions", JSONArray().put(action))
            val rule = store.save(null, definition)
            store.approve(rule.id, rule.revision)
            if (paused) check(store.pause(rule.id))
            checkNotNull(store.rule(rule.id))
        }

    private fun placeFromLibrary(id: String, title: String) {
        click(title)
        click(R.string.tool_options)
        robot.click("Закрепить")
        click(R.string.tool_desktop_add)
        robot.waitUntil { record(id)?.onDesktop == true }
        click(R.string.tool_options)
        click(R.string.tool_chat)
        reveal(robot.text(R.string.desktop_open_tile, title))
    }

    private fun click(id: Int, vararg args: Any) = click(robot.text(id, *args))

    private fun click(label: String) {
        reveal(label)
        robot.click(label)
    }

    private fun reveal(label: String): AccessibilityNodeInfo {
        fun scrollBackward(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
            return (0 until node.childCount).any { scrollBackward(node.getChild(it)) }
        }
        repeat(6) {
            if (robot.find(label) != null || !scrollBackward(robot.diagnostics.freshRoot())) return robot.reveal(label)
            robot.instrumentation.uiAutomation.waitForIdle(100, 3000)
        }
        return robot.reveal(label)
    }

    private fun record(id: String) = ToolStore(fixture.context, toolsName).use { it.tool(id) }

    private fun count(id: String) = JSONObject(checkNotNull(record(id)).state).getInt("count")

    private fun seedTool(id: String, title: String, metric: String, button: String, placed: Boolean = false, createdAt: Long = 1,
        alert: Boolean = false) {
        val definition = JSONObject("""{"formatVersion":3,"title":"$title","description":"Desktop workflow fixture","capabilities":[],
            "stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"column","children":[
            {"type":"text","text":"$metric","bind":"count"},{"type":"button","text":"$button","action":"add"}]},
            "source":"if(input.event.type==='action') input.state.count++; return {state:input.state};"}""")
        if (alert) {
            definition.put("capabilities", JSONArray().put("vibration.play"))
            definition.put("source", "input.state.count++; return {state:input.state,effects:[{id:'buzz',capability:'vibration.play',args:{milliseconds:1}}]};")
        }
        val spec = ToolSpec.parse(definition.toString())
        ToolStore(fixture.context, toolsName).use { tools ->
            tools.commitTool(ToolRecord(id, 1, 1, spec.json, spec.initialState.toString(), spec.ui.toString(), null, true,
                createdAt = createdAt, onDesktop = placed), null, true)
        }
    }
}
