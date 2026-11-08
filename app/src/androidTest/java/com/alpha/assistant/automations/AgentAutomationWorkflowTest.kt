package com.alpha.assistant.automations

import android.Manifest
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.surfaces.AssistantChat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class AgentAutomationWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Test fun preparedOccurrenceDeliversOnceAndPreservesForegroundWrites(): Unit = runBlocking {
        val chat = "automation-test-${UUID.randomUUID()}"
        val database = "$chat.db"
        val keys = SecureKeyStore(context)
        assertTrue("Use a fresh owned Android test device", keys.read().getOrThrow() == null)
        val permission = Manifest.permission.POST_NOTIFICATIONS
        assertEquals("Grant notifications before running on the owned emulator",
            android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        val target = ZonedDateTime.now(ZoneId.of("Europe/Moscow")).plusDays(1).with(LocalTime.of(7, 0)).toInstant().toEpochMilli()
        val lead = 15 * 60000L
        var now = target - 2 * lead
        val clock = { now }
        val saved = Conversation(chat, "Background result destination", now, now)
        val payload = """{"name":"Example","trigger":{"type":"schedule","repeat":"daily","time":"07:00","zone":"Europe/Moscow","exact":false},
            "conditions":{},"actions":[{"id":"task","capability":"agent.run","args":{"instruction":"Write a short astronomy fact.","conversationId":"$chat"}}]}"""
        val response = "An astronomy fact."
        var providerCalls = 0
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                providerCalls++
                assertTrue(request.scheduledAgent)
                assertEquals("Summarize one astronomy fact.", request.turns.single().text)
                assertNull(request.automationDraftChatId)
                assertFalse(request.systemInstructions.contains(chat))
                emit(CloudEvent.RoundComplete(response, false))
            }
        }
        assertTrue(keys.save("alpha-test-only").isSuccess)
        try {
            ShellStore(context).use { shell ->
                shell.saveConversation(saved)
                AutomationStore(context, database).use { store ->
                    val draft = store.save(null, JSONObject(payload))
                    store.setState(AgentAutomationRuntime.consentKey(draft.id), checkNotNull(keys.consentIdentity()))
                    store.approve(draft.id, draft.revision)
                    store.next(draft.id, draft.revision, target)
                    val initial = checkNotNull(store.rule(draft.id))
                    val event = AutomationEvent("schedule", initial.definition.occurrenceToken(target), scheduledAt = target)
                    val reserved = checkNotNull(AgentAutomationRuntime.queue(context, store, initial, event, now))
                    val edit = JSONObject(payload.replace("Write a short astronomy fact.", "Summarize one astronomy fact."))
                    val revised = store.save(draft.id, edit, draft.revision)
                    store.approve(revised.id, revised.revision)
                    store.next(revised.id, revised.revision, target)
                    val rule = checkNotNull(store.rule(revised.id))
                    val pending = checkNotNull(AgentAutomationRuntime.queue(context, store, rule, event, now))
                    try {
                        assertEquals("cancelled", store.agentRun(reserved.id)?.run?.status)
                        assertNotEquals(reserved.id, pending.id)
                        assertEquals("pending", pending.status)
                        val preparation = checkNotNull(store.agentRun(pending.id))
                        assertEquals(target - lead, preparation.prepareAt)
                        assertFalse(AgentAutomationRuntime.prepare(context, store, pending.id, CloudSession(), provider, clock))
                        assertEquals(0, providerCalls)

                        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java).forNamespace("alphy-agent-automation")
                        assertEquals(lead, scheduler.getPendingJob(preparation.slot * 2)?.minLatencyMillis)
                        now = target - lead
                        AgentAutomationRuntime.reconcile(context, store, now, clock)
                        assertEquals(0L, scheduler.getPendingJob(preparation.slot * 2)?.minLatencyMillis)
                        now -= 2 * lead
                        AgentAutomationRuntime.reconcile(context, store, now, clock)
                        assertEquals(2 * lead, scheduler.getPendingJob(preparation.slot * 2)?.minLatencyMillis)
                        now = target - lead
                        assertTrue(AgentAutomationRuntime.prepare(context, store, pending.id, CloudSession(), provider, clock))
                        assertEquals("ready", store.agentRun(pending.id)?.run?.status)
                        assertTrue(shell.automationMessages()[chat].isNullOrEmpty())
                        assertEquals(1, providerCalls)

                        now = target + 20 * 60000L
                        val next = target + 86400000
                        store.next(rule.id, rule.revision, next)
                        val later = AutomationEvent("schedule", rule.definition.occurrenceToken(next), scheduledAt = next)
                        assertNull(AgentAutomationRuntime.queue(context, store, rule, later, now))
                        assertNull(store.occurrence(rule.id, later.token))
                        AgentAutomationRuntime.reconcile(context, store, now, clock)
                        AgentAutomationRuntime.reconcile(context, store, now, clock)
                        val notice = notifications.activeNotifications.single { it.tag == "automation:${pending.id}" }
                        assertNotNull(notice.notification.contentIntent)
                        assertEquals("succeeded", store.agentRun(pending.id)?.run?.status)
                        assertEquals(1, providerCalls)
                        val user = Message("foreground", "user", "Still in the original chat", now - 1, "sent")
                        shell.updateConversation(saved.copy(messages = listOf(user)))
                        val messages = shell.load().conversations.single { it.id == chat }.messages
                        assertEquals(listOf(user.text, response), messages.map { it.text })
                        assertTrue(messages.last().automationResult)

                        notice.notification.contentIntent.send()
                        val wait = SystemClock.uptimeMillis() + 10000
                        var selected: String? = null
                        do {
                            instrumentation.runOnMainSync {
                                selected = AssistantChat.model(context.applicationContext as android.app.Application).snapshot.selectedId
                            }
                            if (selected == chat) break
                            SystemClock.sleep(50)
                        } while (SystemClock.uptimeMillis() < wait)
                        assertEquals(chat, selected)

                        now = next - lead
                        val waiting = checkNotNull(AgentAutomationRuntime.queue(context, store, rule, later, now))
                        now = next + AutomationSchedule.MAX_LATENESS + 1
                        AgentAutomationRuntime.reconcile(context, store, now, clock)
                        assertEquals("missed", store.agentRun(waiting.id)?.run?.status)
                        assertTrue(AgentAutomationRuntime.prepare(context, store, waiting.id, CloudSession(), provider, clock))
                        assertEquals(1, providerCalls)

                        store.prune(target + 31L * 86400000)
                        assertEquals(response, shell.automationMessages()[chat]?.single()?.text)

                        for ((index, change) in listOf("pause", "key change", "delete chat").withIndex()) {
                            val due = next + (index + 1) * 86400000L
                            now = due - lead
                            store.setState(AgentAutomationRuntime.consentKey(rule.id), checkNotNull(keys.consentIdentity()))
                            store.approve(rule.id, rule.revision)
                            store.next(rule.id, rule.revision, due)
                            val trigger = AutomationEvent("schedule", rule.definition.occurrenceToken(due), scheduledAt = due)
                            val interrupted = checkNotNull(AgentAutomationRuntime.queue(context, store, rule, trigger, now))
                            val entered = CompletableDeferred<Unit>()
                            val release = CompletableDeferred<Unit>()
                            val gated = object : ChatProvider {
                                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                                    entered.complete(Unit)
                                    release.await()
                                    emit(CloudEvent.RoundComplete("A late result that must stay out of chat.", false))
                                }
                            }
                            val attempt = async(Dispatchers.IO) {
                                AgentAutomationRuntime.prepare(context, store, interrupted.id, CloudSession(), gated, clock)
                            }
                            try {
                                withTimeout(5000) { entered.await() }
                                when (change) {
                                    "pause" -> assertTrue(store.pause(rule.id, rule.revision))
                                    "key change" -> assertTrue(keys.save("alpha-test-rotated").isSuccess)
                                    else -> shell.deleteConversation(chat)
                                }
                                AgentAutomationRuntime.reconcile(context, store, now, clock)
                            } finally { release.complete(Unit) }
                            assertTrue(attempt.await())
                            assertEquals(change, "cancelled", store.agentRun(interrupted.id)?.run?.status)
                            assertNull(shell.automationMessages()[chat]?.find { it.id == "automation:${interrupted.id}" })
                            assertFalse(notifications.activeNotifications.any { it.tag == "automation:${interrupted.id}" })
                            if (change != "delete chat") assertEquals(response, shell.automationMessages()[chat]?.single()?.text)
                        }

                        shell.updateConversation(saved.copy(messages = listOf(user)))
                        assertFalse(shell.hasConversation(chat))
                    } finally {
                        notifications.cancel("automation:${pending.id}", 0)
                        store.pause(rule.id)
                        AgentAutomationRuntime.reconcile(context, store, now, clock)
                    }
                }
            }
        } finally {
            ShellStore(context).use { it.deleteConversation(chat) }
            context.deleteDatabase(database)
            keys.clear()
        }
    }
    @Test fun exactAlarmDeliversAReadyResultInTheAndroidReceiver(): Unit = runBlocking {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        assertNull("Disable the owned emulator's network for this receiver test", connectivity.activeNetwork)
        assertTrue("Grant exact alarm access on the owned emulator", AutomationRuntime.exactAllowed(context))
        val chat = "exact-delivery-${UUID.randomUUID()}"
        val keys = SecureKeyStore(context)
        assertTrue("Use a fresh owned Android test device", keys.read().getOrThrow() == null)
        val now = System.currentTimeMillis()
        val due = ZonedDateTime.now(ZoneId.of("UTC")).plusSeconds(20).withNano(0)
        val target = due.toInstant().toEpochMilli()
        val payload = """{"name":"Exact delivery","trigger":{"type":"schedule","repeat":"once","date":"${due.toLocalDate()}",
            "time":"${due.toLocalTime()}","zone":"UTC","exact":true},"conditions":{},
            "actions":[{"id":"task","capability":"agent.run","args":{"instruction":"Write a short astronomy fact.","conversationId":"$chat"}}]}"""
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                emit(CloudEvent.RoundComplete("The Moon orbits Earth.", false))
            }
        }
        assertTrue(keys.save("alpha-test-only").isSuccess)
        try {
            ShellStore(context).use { shell ->
                shell.saveConversation(Conversation(chat, "Exact delivery destination", now, now))
                AutomationStore(context).use { store ->
                    val draft = store.save(null, JSONObject(payload))
                    var runId: String? = null
                    try {
                        AutomationRuntime.activate(context, store, AutomationRuntime.prepareApproval(store, draft.id, draft.revision))
                        val token = draft.definition.occurrenceToken(target)
                        val wait = SystemClock.uptimeMillis() + 10000
                        while (SystemClock.uptimeMillis() < wait && runId == null) {
                            runId = store.occurrence(draft.id, token)?.id
                            if (runId == null) SystemClock.sleep(50)
                        }
                        val id = checkNotNull(runId)
                        assertEquals(target, store.rule(draft.id)?.nextAt)
                        assertTrue(AgentAutomationRuntime.prepare(context, store, id, CloudSession(), provider))
                        assertEquals("ready", store.agentRun(id)?.run?.status)
                        assertTrue(shell.automationMessages()[chat].isNullOrEmpty())
                        val end = SystemClock.uptimeMillis() + 35000
                        while (SystemClock.uptimeMillis() < end &&
                            (shell.automationMessages()[chat].isNullOrEmpty() || store.agentRun(id)?.run?.status != "succeeded")
                        ) SystemClock.sleep(100)
                        val result = shell.automationMessages()[chat]?.single() ?: error("The exact alarm delivered no message.")
                        assertEquals("The Moon orbits Earth.", result.text)
                        assertTrue(result.createdAt >= target && result.createdAt < target + 15000)
                        assertEquals("succeeded", store.agentRun(id)?.run?.status)
                    } finally {
                        runId?.let { notifications.cancel("automation:$it", 0) }
                        store.rule(draft.id)?.let { AutomationRuntime.deleteRule(context, store, it) }
                    }
                }
            }
        } finally {
            ShellStore(context).use { it.deleteConversation(chat) }
            keys.clear()
        }
    }

    @Test fun upgradeKeepsAnApprovedNativeRuleItsHistoryAndExistingChat() {
        val prefix = "automation-upgrade-${UUID.randomUUID()}"
        val rules = "$prefix-rules.db"
        val chats = "$prefix-chat.db"
        val definition = JSONObject("""{"name":"Local reminder","trigger":{"type":"manual"},"conditions":{},
            "actions":[{"id":"remind","capability":"notification.post","args":{"title":"Hello","text":"Local only"}}]}""")
        try {
            val rule = AutomationStore(context, rules).use { store ->
                val draft = store.save(null, definition)
                store.approve(draft.id, draft.revision)
                val approved = checkNotNull(store.rule(draft.id))
                val run = checkNotNull(store.begin(approved, AutomationEvent("manual", "legacy-run"), 1, false))
                store.finish(run, "succeeded", "Existing history")
                store.writableDatabase.execSQL("DROP TABLE agent_runs")
                store.writableDatabase.version = 2
                approved
            }
            ShellStore(context, chats).use { shell ->
                shell.saveConversation(Conversation("chat", "Existing chat", 1, 1,
                    messages = listOf(Message("m1", "user", "Existing message", 1, "sent"))))
                shell.writableDatabase.execSQL("DROP TABLE automation_messages")
                shell.writableDatabase.version = 1
            }
            AutomationStore(context, rules).use { upgraded ->
                val restored = checkNotNull(upgraded.rule(rule.id))
                assertTrue(restored.enabled)
                assertEquals(rule.approvedRevision, restored.approvedRevision)
                assertEquals(rule.definition.json, restored.definition.json)
                assertEquals("Existing history", upgraded.history(rule.id).single().message)
                assertTrue(upgraded.unfinishedAgentRuns().isEmpty())
            }
            ShellStore(context, chats).use { upgraded ->
                assertEquals("Existing message", upgraded.load().conversations.single().messages.single().text)
                assertTrue(upgraded.automationMessages().isEmpty())
            }
        } finally {
            context.deleteDatabase(rules)
            context.deleteDatabase(chats)
        }
    }

}
