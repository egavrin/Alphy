package com.alpha.assistant.timer

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTimerWorkflowTest {
    @Test fun resetAndBackgroundNeverAlertAndExplicitResumeCompletesOnce() {
        val fixture = ShellFixture("foreground-timer")
        val robot = ShellRobot("foreground-timer")
        val notifications = fixture.context.getSystemService(NotificationManager::class.java)
        val title = "Timer ${fixture.name.takeLast(12)}"
        fun alerts() = notifications.activeNotifications.filter { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == title }
        val allowed = notifications.areNotificationsEnabled() && notifications.getNotificationChannel("alpha-tools")
            ?.importance != NotificationManager.IMPORTANCE_NONE
        InstrumentationRegistry.getArguments().getString("expectTimerNotifications")?.let {
            assertEquals(it.toBooleanStrict(), allowed)
        }
        val duration = 6_000L
        val timer = JSONObject("""{"type":"timer","mode":"countdown","text":"Interval","action":"interval"}""")
            .put("foreground", JSONObject().put("durationMs", duration)
                .put("notification", JSONObject().put("title", title).put("text", "Interval complete")))
        val ui = JSONObject().put("type", "column").put("children", JSONArray()
            .put(JSONObject("""{"type":"button","text":"Cover timer","action":"cover"}""")).put(timer)
            .put(JSONObject("""{"type":"dialog","text":"Timer cover","visibleBind":"covered","dismissAction":"uncover",
                "children":[{"type":"text","text":"The timer is covered"}]}""")))
        val spec = ToolSpec.parse(JSONObject("""{"formatVersion":3,"title":"Interval test","description":"","capabilities":["notification.post"],
            "stateSchema":{"covered":"boolean"},"initialState":{"covered":false}}""")
            .put("ui", ui).put("source", """
                if (input.event.actionId === 'cover') input.state.covered = true;
                if (input.event.actionId === 'uncover') input.state.covered = false;
                return {state: input.state};
            """.trimIndent()).toString())
        val record = ToolRecord("interval", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "chat", true)
        val start = robot.text(R.string.foreground_timer_start)
        val reset = robot.text(R.string.foreground_timer_reset)
        val resume = robot.text(R.string.foreground_timer_resume)
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                store.saveTool(record, true)
                val ledger = ForegroundTimerLedger(store, record, ForegroundTimerSpec.parse(timer))
                fixture.store.saveConversation(Conversation("chat", "Timer chat", 1, 1, messages = listOf(
                    Message("artifact", "assistant", "", 1, "complete", toolId = record.id, toolRevision = 1),
                )))
                fixture.store.saveSetting("selected", "chat")
                fixture.install(saveKey = false)
                fixture.launch().use { scenario ->
                    robot.waitFor(robot.text(R.string.tool_open))
                    robot.waitGone(start)
                    robot.click(robot.text(R.string.tool_open))
                    robot.click(start)
                    robot.click(reset)
                    robot.waitFor(start)
                    SystemClock.sleep(duration + 300)
                    assertTrue(alerts().isEmpty())
                    assertEquals(ForegroundTimerLedger.Phase.IDLE, ledger.read()?.run?.phase)

                    robot.click(start)
                    assertEquals(ForegroundTimerLedger.Phase.RUNNING, ledger.read()?.run?.phase)
                    robot.reveal("Cover timer", forward = null)
                    robot.click("Cover timer")
                    robot.waitFor("Timer cover")
                    robot.waitUntil { ledger.read()?.run?.phase == ForegroundTimerLedger.Phase.PAUSED }
                    SystemClock.sleep(duration + 300)
                    assertTrue(alerts().isEmpty())
                    robot.click(robot.text(R.string.tool_generated_dialog_close, "Timer cover"))
                    robot.waitFor(resume)
                    robot.reveal(reset, forward = null)
                    robot.click(reset)

                    robot.click(start)
                    robot.waitFor(reset)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    assertEquals(ForegroundTimerLedger.Phase.PAUSED, ledger.read()?.run?.phase)
                    SystemClock.sleep(duration + 300)
                    assertTrue(alerts().isEmpty())
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    robot.waitFor(resume)
                    assertTrue(alerts().isEmpty())
                    robot.click(resume)
                    val expected = robot.text(if (allowed) R.string.tool_timer_expired else R.string.foreground_timer_denied)
                    robot.waitFor(robot.text(R.string.tool_timer_value, "Interval", expected))
                    val receipt = checkNotNull(ledger.read())
                    assertEquals(ForegroundTimerLedger.Phase.FINISHED, receipt.run.phase)
                    assertEquals(if (allowed) "succeeded" else "denied", receipt.run.outcome)
                    assertTrue(store.effects(record.id).isEmpty())
                    assertEquals(record.state, store.tool(record.id)?.state)
                    val delivered = alerts().map { it.key }
                    assertEquals(if (allowed) 1 else 0, delivered.size)

                    scenario.recreate()
                    robot.waitFor(robot.text(R.string.tool_timer_value, "Interval", expected))
                    SystemClock.sleep(duration + 300)
                    assertEquals(delivered, alerts().map { it.key })
                    assertEquals(receipt, ledger.read())
                    assertEquals(0, fixture.provider.calls)
                    robot.diagnostics.capture("terminal")
                }
            }
        } finally {
            alerts().forEach { notifications.cancel(it.tag, it.id) }
            fixture.remove()
        }
    }
}
