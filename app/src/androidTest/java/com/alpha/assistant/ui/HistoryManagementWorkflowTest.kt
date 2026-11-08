package com.alpha.assistant.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.data.*
import com.alpha.assistant.cloud.*
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.automations.AutomationTools
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class HistoryManagementWorkflowTest {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val robot = ShellRobot("history")

    @Test fun renameDeleteAndClearRespectDataBoundaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = "history-workflow-${UUID.randomUUID()}"
        val store = ShellStore(context, "$fixture.db")
        val keys = SecureKeyStore(context, fixture, fixture)
        val tools = ToolStore(context, "$fixture.db-tools.db")
        val rules = AutomationStore(context, "$fixture-automations.db")
        val source = File(context.cacheDir, fixture).apply { writeText("attachment source") }
        val first = Conversation("first", "First fixture", 1, 2, Draft("first draft"), listOf(Message("m", "user", "Protected message", 1, "sent", listOf(Attachment(source.toURI().toString(), source.name, "text/plain")))))
        val second = Conversation("second", "Second fixture", 1, 1, Draft("second draft"))
        val newDraft = Draft("independent new draft")
        try {
            store.saveConversation(first); store.saveConversation(second); store.saveNewDraft(newDraft); store.seedOpenChat(first.id)
            keys.save("history-test-only").getOrThrow()
            val tool = AutomationTools.createDayTool(tools)
            val rule = rules.save(null, JSONObject("""{"name":"History fixture","trigger":{"type":"manual"},"conditions":{},"actions":[{"id":"notice","capability":"notification.post","args":{"title":"Fixture","text":"Fixture"}}]}"""))
            CloudTestActivity.factory = { app -> ShellViewModel(app, object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { error("History actions must not call a provider") }
            }, store, keys).openSeededChat(store) }
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitFor("Сообщение ассистенту")
                openSearch(scenario)
                waitFor(robot.text(R.string.search_row_draft, DateFormat.getDateInstance(DateFormat.SHORT, Locale.forLanguageTag("ru")).format(Date(first.updatedAt))))
                click("Действия разговора First fixture"); click("Переименовать")
                edit("Название разговора", " ")
                waitFor("Введите название")
                assertFalse(button("Сохранить название").isEnabled)
                edit("Название разговора", "Renamed fixture"); click("Сохранить название")
                waitUntil { store.load().conversations.find { it.id == first.id }?.title == "Renamed fixture" }
                scenario.recreate(); waitFor("Renamed fixture")
                edit("Поиск разговоров", "Renamed fixture"); click("Renamed fixture")
                assertEquals("first draft", field("Сообщение ассистенту").text.toString())
                openSearch(scenario); edit("Поиск разговоров", "")
                click("Действия разговора Renamed fixture"); click("Удалить разговор"); click("Отмена")
                assertEquals(2, store.load().conversations.size)
                click("Действия разговора Renamed fixture"); click("Удалить разговор"); click("Удалить")
                waitUntil { store.load().conversations.size == 1 }
                assertEquals(second, store.load().conversations.single())
                openNewChatFromSearch(scenario); assertEquals(newDraft.text, field("Сообщение ассистенту").text.toString())
                openSearch(scenario); openClearHistory(); click("Отмена")
                assertEquals(second, store.load().conversations.single())
                // Failure case: a query kept through the clear shows «Ничего не найдено» on an empty history.
                edit("Поиск разговоров", "Second fixture")
                openClearHistory(); waitFor("Черновик нового чата, инструменты и их данные, автоматизации, API-ключи и исходные файлы сохранятся."); click("Очистить всё")
                waitUntil { store.load().conversations.isEmpty() }
                waitFor(robot.text(R.string.search_empty))
                openNewChatFromSearch(scenario); assertEquals(newDraft.text, field("Сообщение ассистенту").text.toString())
                assertEquals("history-test-only", keys.read().getOrThrow())
                assertEquals(tool, tools.tool(tool.id))
                val preservedRule = checkNotNull(rules.rule(rule.id))
                assertEquals(rule.definition.json,preservedRule.definition.json)
                assertEquals(rule.copy(definition = preservedRule.definition),preservedRule)
                assertEquals("attachment source", source.readText())
            }
        } finally {
            CloudTestActivity.factory = null
            store.close(); tools.close(); rules.close(); keys.clear(); source.delete()
            context.deleteDatabase("$fixture.db"); context.deleteDatabase("$fixture.db-tools.db"); context.deleteDatabase("$fixture-automations.db")
            context.deleteSharedPreferences(fixture)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixture) }
        }
    }

    @Test fun failedDeletionRetriesWithoutLateResponseResurrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = "history-cancel-${UUID.randomUUID()}"
        val store = ShellStore(context, "$fixture.db")
        val keys = SecureKeyStore(context, fixture, fixture)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        try {
            keys.save("history-test-only").getOrThrow()
            CloudTestActivity.factory = { app -> ShellViewModel(app, object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await(); emit(CloudEvent.Text("Late deleted response")); delivered.complete(Unit) }
                }
            }, store, keys).openSeededChat(store) }
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                edit("Сообщение ассистенту", "Running fixture")
                click("Отправить сообщение")
                waitUntil { entered.isCompleted }
                waitUntil { robot.composed(scenario, "chat") }
                click(robot.text(R.string.new_chat))
                // The Chat entry leaves composition at the end of the pop transition.
                waitUntil { !robot.composed(scenario, "chat") }
                edit("Сообщение ассистенту", "Keep independent draft")
                store.writableDatabase.execSQL("CREATE TRIGGER fixture_reject_delete BEFORE DELETE ON conversations BEGIN SELECT RAISE(ABORT, 'fixture'); END")
                openSearch(scenario); click("Действия разговора Running fixture"); click("Удалить разговор"); click("Удалить")
                waitFor("Изменения не сохранены")
                assertEquals(1, store.load().conversations.size)
                release.complete(Unit); waitUntil { delivered.isCompleted }
                store.writableDatabase.execSQL("DROP TRIGGER fixture_reject_delete")
                click("Повторить")
                waitUntil { store.load().conversations.isEmpty() }
                openNewChatFromSearch(scenario)
                assertEquals("Keep independent draft", field("Сообщение ассистенту").text.toString())
                assertTrue(store.load().conversations.isEmpty())
                assertEquals("history-test-only", keys.read().getOrThrow())
            }
        } finally {
            release.complete(Unit)
            CloudTestActivity.factory = null
            store.close(); keys.clear(); context.deleteDatabase("$fixture.db"); context.deleteDatabase("$fixture.db-tools.db"); context.deleteSharedPreferences(fixture)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixture) }
        }
    }

    private fun newChat(scenario: ActivityScenario<*>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.new_chat))
        robot.awaitDrawerClosed(scenario)
    }

    private fun openNewChatFromSearch(scenario: ActivityScenario<*>) {
        robot.click(robot.text(R.string.search_back))
        newChat(scenario)
    }
    private fun openClearHistory() {
        robot.click(robot.text(R.string.search_history_actions))
        robot.click(robot.text(R.string.clear_history))
    }
    private fun openSearch(scenario: ActivityScenario<*>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.search))
        robot.awaitDrawerClosed(scenario)
    }
    private fun edit(label: String, text: String) {
        val node = field(label)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        waitUntil { field(label).text?.toString() == text }
    }
    private fun field(label: String): AccessibilityNodeInfo {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> editable(child)?.let { return it } } }
            return null
        }
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) {
            editable(node)?.let { return it }
            node = node.parent
        }
        error("No editable node for $label")
    }
    private fun button(label: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        return checkNotNull(node) { "No clickable node for $label" }
    }
    private fun click(label: String) {
        assertTrue(button(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        automation.waitForIdle(100, 3000)
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        // A fresh root holds no cached node of an entry that a transition covers or removes.
        waitUntil { result = find(robot.diagnostics.freshRoot(), label); result != null }
        return result!!
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if ((!node.isEditable && node.text?.toString() == label) || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { match -> return match } }
        return null
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("UI condition did not complete within 10 seconds")
    }
}
