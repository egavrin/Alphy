package com.alpha.assistant.automations

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationCardEditorWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val robot = ShellRobot("automation-card-editor")
    private fun chain() = JSONObject("""{
        "name":"Editor fixture",
        "trigger":{"type":"schedule","repeat":"daily","time":"08:15","zone":"Europe/Berlin","exact":false},
        "conditions":{"charging":true,"fromMinute":450,"toMinute":1200},
        "actions":[
            {"id":"read_custom","capability":"calendar.read","args":{"calendarId":17,"from":1793589634321,"to":1793689298765}},
            {"id":"set_custom","capability":"tool.state.set","args":{"toolId":"calendar-view","revision":4,"field":"title","value":"Fixture"}},
            {"id":"notice_custom","capability":"notification.post","args":{"title":"Private notice","text":"Keep the action unchanged"}}
        ]
    }""")

    private fun withRule(
        input: JSONObject = chain(),
        block: (AutomationStore, AutomationRule, ActivityScenario<CloudTestActivity>) -> Unit,
    ) {
        val fixture = ShellFixture("automation-card-editor")
        try {
            fixture.install(saveKey = false)
            AutomationStore(fixture.context, "${fixture.name}.db-tools.db-automations.db").use { store ->
                val rule = store.save(null,input)
                fixture.launch().use { scenario ->
                    robot.openDrawer(scenario)
                    robot.click(robot.text(R.string.automations))
                    robot.awaitDrawerClosed(scenario)
                    val options=robot.text(R.string.automation_rule_options)
                    if(robot.find(options)==null) robot.click(robot.text(R.string.tool_open_automation,rule.definition.name))
                    block(store,rule,scenario)
                }
            }
        } finally {
            fixture.remove()
        }
    }

    private fun click(label: String, forward: Boolean = true) {
        robot.reveal(label, forward)
        var target: AccessibilityNodeInfo? = null
        robot.waitUntil {
            target = generateSequence(robot.find(label)) { it.parent }.firstOrNull { it.isClickable }
            target?.let { it.isVisibleToUser && it.isEnabled &&
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK in it.actionList } == true
        }
        assertTrue(robot.diagnostics.click(label, target))
        robot.automation.waitForIdle(100, 3000)
    }

    @Test fun nativeTimeEditKeepsThreeOrderedActionsAndExplicitZone() = withRule { store,rule,_ ->
        click("Когда")
        click("Время: 08:15")
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo
        val previousFlags=info.flags
        try {
            info.flags=previousFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo=info
            robot.click("16")
            robot.waitUntil {
                robot.diagnostics.freshRoot()?.findAccessibilityNodeInfosByText("16")
                    ?.any { it.isVisibleToUser && it.isEditable && it.text?.toString() == "16" } == true
            }
            var confirm: AccessibilityNodeInfo? = null
            robot.diagnostics.target("android:id/button1")
            robot.waitUntil {
                confirm = robot.diagnostics.freshRoot()?.findAccessibilityNodeInfosByViewId("android:id/button1")
                    ?.singleOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
                confirm != null
            }
            assertTrue(robot.diagnostics.click("android:id/button1", confirm))
        } finally {
            info.flags=previousFlags
            automation.serviceInfo=info
        }
        robot.waitFor("Время: 08:16")
        click("Сохранить для проверки")
        robot.waitUntil { store.rule(rule.id)?.revision==2 }
        val saved=checkNotNull(store.rule(rule.id))
        assertEquals("08:16",saved.definition.trigger.getString("time"))
        assertEquals("Europe/Berlin",saved.definition.trigger.getString("zone"))
        assertEquals(rule.definition.conditions.toString(),saved.definition.conditions.toString())
        assertEquals(rule.definition.actions.map { it.json().toString() },saved.definition.actions.map { it.json().toString() })
        assertEquals("Fixture",saved.definition.actions[1].args.getString("value"))
        assertFalse(saved.enabled)
        assertEquals(0,saved.approvedRevision)
    }

    @Test fun actionLimitAndCanceledReorderPreserveSavedRule() = withRule { store,rule,_ ->
        click("Что сделать")
        var add: AccessibilityNodeInfo?=robot.reveal("Добавить действие")
        while(add!=null && add.isEnabled) add=add.parent
        assertNotNull("The action limit must disable the add control",add)
        click("Поднять действие 2", forward = false)
        assertEquals(rule.definition.json,store.rule(rule.id)?.definition?.json)
        assertEquals(1,store.rule(rule.id)?.revision)
        click("Отмена")
        assertEquals(rule.definition.json,store.rule(rule.id)?.definition?.json)
    }

    @Test fun addAndRemoveActionRespectBoundsAndStaleSaveKeepsNewerRevision() = withRule(
        chain().apply { getJSONArray("actions").remove(2) }
    ) { store,rule,scenario ->
        click("Что сделать")
        click("Добавить действие")
        robot.reveal("Действие 3", forward = null)
        click("Удалить действие 3")
        click("Добавить действие")
        robot.reveal("Действие 3", forward = null)
        val nameField = robot.text(R.string.automation_editor_name)
        val draftName = "Unsaved editor name"
        robot.reveal(nameField, forward = false)
        robot.edit(nameField, draftName)
        val concurrent=store.save(rule.id,JSONObject(rule.definition.json).put("name","Newer editor revision"),rule.revision)
        scenario.recreate()
        assertEquals(draftName, robot.field(nameField).text.toString())
        click("Сохранить для проверки")
        robot.reveal("Сценарий изменился. Открой текущую версию.")
        assertEquals(concurrent.definition.json,store.rule(rule.id)?.definition?.json)
        assertEquals(2,store.rule(rule.id)?.revision)
    }
}
