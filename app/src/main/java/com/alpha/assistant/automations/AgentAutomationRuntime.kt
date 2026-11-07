package com.alpha.assistant.automations

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.os.SystemClock
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.cloud.cloudKey
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.tools.ToolLibraryStorage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object AgentAutomationRuntime {
    private const val CHANNEL = "alpha-tools"
    private const val JOB_NAMESPACE = "alphy-agent-automation"
    private val process = UUID.randomUUID().toString()
    private val sessions = ConcurrentHashMap<String, CloudSession>()

    fun consentKey(ruleId: String) = "agent-consent:$ruleId"

    fun notificationAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun accessFailure(context: Context, store: AutomationStore, rule: AutomationRule, requireApprovedConsent: Boolean): String? {
        val agent = rule.definition.agent ?: return null
        val consent = SecureKeyStore(context).consentIdentity()
        return when {
            consent == null -> "Сохраните ключ DeepSeek и подтвердите доступ."
            requireApprovedConsent && store.state(consentKey(rule.id)) != consent -> "Ключ DeepSeek изменился. Проверьте и включите сценарий заново."
            !notificationAllowed(context) -> "Разрешите уведомления Alphy в настройках Android."
            !ShellStore(context).use { it.hasConversation(agent.conversationId) } -> "Чат для доставки удалён."
            else -> null
        }
    }

    fun queue(context: Context, store: AutomationStore, rule: AutomationRule, event: AutomationEvent, now: Long): AutomationRun? {
        val target = event.scheduledAt ?: now
        val zone = rule.definition.zone()
        val condition = rule.definition.conditionFailure(AutomationRuntime.charging(context), maxOf(now, target))
        if (now < target && condition != null) return null
        var occurrence: AgentOccurrence? = null
        var run: AutomationRun? = null
        store.transaction {
            if (!store.active(rule.id, rule.revision) ||
                event.scheduledAt != null && store.rule(rule.id)?.nextAt != event.scheduledAt) return@transaction
            val approval = store.approvalToken(rule.id).orEmpty()
            val existing = store.occurrence(rule.id, event.token)
            if (existing != null) {
                val previous = store.agentRun(existing.id)
                val superseded = previous != null && previous.process == null && previous.result == null &&
                    (previous.run.revision != rule.revision || previous.approval != approval ||
                        previous.target != target || previous.zone != zone)
                if (!superseded) {
                    run = existing
                    occurrence = previous
                    return@transaction
                }
                store.supersedeAgentReservation(checkNotNull(previous))
                cancelJobs(context, previous)
            }
            val busy = store.unfinishedAgentRuns().any { it.run.ruleId == rule.id && now <= it.expiresAt &&
                it.run.revision == rule.revision && it.approval == approval }
            // An early reservation can wait for the previous occurrence. The next reconciliation can enqueue it.
            if (busy && now < target) return@transaction
            val claim = store.begin(rule, event, now, false) ?: return@transaction
            val blocked = AutomationRuntime.triggerFailure(context, rule) ?: accessFailure(context, store, rule, true)
            run = when {
                blocked != null -> {
                    store.attention(rule.id, rule.revision, blocked)
                    store.finish(claim, "revoked", blocked)
                }
                condition != null -> store.finish(claim, "skipped", condition)
                busy -> store.finish(claim, "skipped", "Предыдущая задача ещё выполняется.")
                else -> {
                    val prepareAt = if (event.scheduledAt != null && rule.definition.type == "schedule")
                        agentPreparationTime(target, zone) else now
                    occurrence = store.addAgentRun(claim, target, zone, prepareAt, approval,
                        checkNotNull(store.state(consentKey(rule.id))))
                    occurrence?.run
                }
            }
        }
        occurrence?.let { reconcileOne(context, store, it, now) }
        return run?.let { store.occurrence(it.ruleId, it.token) }
    }

    private fun authorityFailure(context: Context, store: AutomationStore, occurrence: AgentOccurrence, now: Long): String? {
        val rule = store.rule(occurrence.run.ruleId) ?: return "Сценарий удалён."
        if (!store.active(rule.id, occurrence.run.revision) || store.approvalToken(rule.id).orEmpty() != occurrence.approval)
            return "Сценарий остановлен или изменён."
        if (rule.definition.zone() != occurrence.zone || (now < occurrence.target && rule.nextAt != occurrence.target))
            return "Время запуска изменилось."
        if (store.state(consentKey(rule.id)) != occurrence.credential) return "Доступ DeepSeek изменился."
        return AutomationRuntime.triggerFailure(context, rule) ?: accessFailure(context, store, rule, true)
    }

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    private fun jobId(occurrence: AgentOccurrence, expiry: Boolean) = occurrence.slot * 2 + if (expiry) 1 else 0

    private fun schedule(context: Context, store: AutomationStore, occurrence: AgentOccurrence, expiry: Boolean, now: Long): Boolean {
        val scheduler = scheduler(context)
        val id = jobId(occurrence, expiry)
        val library = ToolLibraryStorage.relatedSelection(context, store.databaseName, "automations")?.generation
            ?: ToolLibraryStorage.ORIGINAL
        val wallOffset = now - SystemClock.elapsedRealtime()
        val pending = scheduler.getPendingJob(id)?.extras
        if (pending?.getString("run") == occurrence.run.id && pending.getString("library") == library &&
            pending.containsKey("wallOffset") && kotlin.math.abs(wallOffset - pending.getLong("wallOffset")) <= 1000) return true
        val extras = PersistableBundle().apply {
            putString("run", occurrence.run.id)
            putString("library", library)
            putBoolean("expiry", expiry)
            putLong("wallOffset", wallOffset)
        }
        val time = if (expiry) occurrence.expiresAt + 1 else occurrence.prepareAt
        val builder = JobInfo.Builder(id, ComponentName(context, AgentAutomationJobService::class.java))
            .setExtras(extras).setPersisted(true).setMinimumLatency((time - now).coerceAtLeast(0L))
        if (!expiry) builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        return scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    }

    fun reconcile(context: Context, store: AutomationStore, now: Long, clock: () -> Long = System::currentTimeMillis) {
        store.unfinishedAgentRuns().forEach { reconcileOne(context, store, it, now, clock) }
    }

    private fun reconcileOne(
        context: Context, store: AutomationStore, occurrence: AgentOccurrence, now: Long,
        clock: () -> Long = System::currentTimeMillis,
    ) = synchronized(ShellStore.deliveryLock) {
        var publish = false
        store.transaction {
            val current = store.agentRun(occurrence.run.id) ?: return@transaction
            val failure = authorityFailure(context, store, current, now)
            val published = current.run.status == "ready" && (failure != null || now > current.expiresAt) &&
                ShellStore(context).use { it.hasAutomationMessage("automation:${current.run.id}") }
            val blocked = when {
                current.run.status == "notifying" -> "unknownOutcome" to "Результат сохранён в чате. Исход уведомления неизвестен."
                current.run.status !in setOf("pending", "preparing", "ready") -> return@transaction
                published -> "succeeded" to "Результат сохранён в чате. Уведомление не подтверждено."
                failure != null -> "cancelled" to failure
                now > current.expiresAt -> "missed" to "Время доставки прошло."
                current.run.status == "preparing" && current.process != process ->
                    "unknownOutcome" to "Подготовка прервалась. Запрос не повторялся."
                else -> null
            }
            if (blocked != null) {
                store.finish(current.run, blocked.first, blocked.second)
                sessions[current.run.id]?.cancel()
                cancelJobs(context, current)
            } else if (current.run.status == "ready" && now >= current.target) publish = true
            else {
                val scheduled = schedule(context, store, current, true, now) &&
                    (current.run.status != "pending" || schedule(context, store, current, false, now))
                if (!scheduled) {
                    store.finish(current.run, "failed", "Android не принял фоновую задачу.")
                    sessions[current.run.id]?.cancel()
                    cancelJobs(context, current)
                }
            }
        }
        if (publish) publish(context, store, occurrence.run.id, clock)
    }

    private fun cancelJobs(context: Context, occurrence: AgentOccurrence) {
        scheduler(context).apply {
            listOf(jobId(occurrence, false), jobId(occurrence, true)).forEach { id ->
                if (getPendingJob(id)?.extras?.getString("run") == occurrence.run.id) cancel(id)
            }
        }
    }

    /** A stopped job can reattach only before the persisted outbound claim. */
    suspend fun prepare(
        context: Context, store: AutomationStore, id: String, session: CloudSession,
        provider: ChatProvider = DeepSeekProvider(), clock: () -> Long = System::currentTimeMillis,
    ): Boolean {
        val occurrence = store.agentRun(id) ?: return true
        val now = clock()
        if (now < occurrence.prepareAt) return false
        var agent: AgentAutomation? = null
        store.transaction {
            val current = store.agentRun(id) ?: return@transaction
            if (current.run.status != "pending" || now > current.expiresAt ||
                authorityFailure(context, store, current, now) != null) return@transaction
            agent = store.rule(current.run.ruleId)?.definition?.agent ?: return@transaction
            store.markAgentPreparing(current, process)
            sessions[id] = session
        }
        val task = agent ?: run { reconcileOne(context, store, occurrence, now, clock); return true }
        val keys = SecureKeyStore(context)
        session.beforeHttp = {
            if (clock() > occurrence.expiresAt || authorityFailure(context, store, occurrence, clock()) != null)
                throw CloudFailure("cancelled", "Задача больше не разрешена.")
        }
        var result: JSONObject? = null
        var error = "Подготовка прервалась. Запрос не повторялся."
        var status = "unknownOutcome"
        try {
            result = keys.observeConsent { if (keys.consentIdentity() != occurrence.credential) session.cancel() }.use {
                session.beforeHttp?.invoke()
                prepareAgentResult(provider, keys.cloudKey(context), task, occurrence.target, occurrence.zone, session)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: CloudFailure) {
            error = failure.message
            status = if (failure.code == "cancelled") "unknownOutcome" else "failed"
        } catch (_: Exception) {
            error = "Не удалось подготовить результат."
        } finally {
            sessions.remove(id, session)
            store.transaction {
                val current = store.agentRun(id) ?: return@transaction
                if (current.run.status != "preparing") return@transaction
                val now = clock()
                val blocked = authorityFailure(context, store, current, now)
                when {
                    blocked != null -> store.finish(current.run, "cancelled", blocked)
                    now > current.expiresAt -> store.finish(current.run, "missed", "Время доставки прошло.")
                    result != null -> store.markAgentReady(current, checkNotNull(result))
                    else -> store.finish(current.run, status, error)
                }
            }
            val current = store.agentRun(id)
            if (current != null && current.run.status != "ready") cancelJobs(context, current)
            context.sendBroadcast(Intent(AutomationRuntime.CHANGED).setPackage(context.packageName))
        }
        store.agentRun(id)?.let { reconcileOne(context, store, it, clock(), clock) }
        return true
    }

    private fun publish(context: Context, store: AutomationStore, id: String, clock: () -> Long) {
        var delivered: AgentOccurrence? = null
        store.transaction {
            val occurrence = store.agentRun(id) ?: return@transaction
            val now = clock()
            if (occurrence.run.status != "ready" || now < occurrence.target || now > occurrence.expiresAt) return@transaction
            val rule = store.rule(occurrence.run.ruleId) ?: return@transaction
            val task = rule.definition.agent ?: return@transaction
            val failure = authorityFailure(context, store, occurrence, now)
            val condition = rule.definition.conditionFailure(AutomationRuntime.charging(context), now)
            if (failure != null || condition != null) {
                store.finish(occurrence.run, if (failure != null) "cancelled" else "skipped", failure ?: checkNotNull(condition))
                cancelJobs(context, occurrence)
                return@transaction
            }
            val written = ShellStore(context).use {
                it.appendAutomationMessage(task.conversationId, "automation:${occurrence.run.id}", now, JSONObject(checkNotNull(occurrence.result)))
            }
            if (!written) {
                store.finish(occurrence.run, "cancelled", "Чат для доставки удалён.")
                cancelJobs(context, occurrence)
                return@transaction
            }
            store.finish(occurrence.run, "notifying", "Результат сохранён в чате.")
            delivered = occurrence
        }
        val occurrence = delivered ?: return
        context.sendBroadcast(Intent(ShellStore.AUTOMATION_RESULT_CHANGED).setPackage(context.packageName))
        store.transaction {
            val rule = store.rule(occurrence.run.ruleId)
            val task = rule?.definition?.agent
            val failure = authorityFailure(context, store, occurrence, clock())
            val notified = task != null && failure == null && clock() <= occurrence.expiresAt && runCatching {
                postNotification(context, checkNotNull(rule).definition.name, task.conversationId, occurrence.run.id)
            }.isSuccess
            store.finish(occurrence.run, "succeeded", if (notified) "Результат сохранён в чате, уведомление отправлено."
                else "Результат сохранён в чате. Уведомление не подтверждено.")
        }
        cancelJobs(context, occurrence)
    }

    private fun postNotification(context: Context, name: String, chat: String, occurrence: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Инструменты Alphy", NotificationManager.IMPORTANCE_DEFAULT))
        val uri = Uri.Builder().scheme("alpha").authority("automation-chat").appendPath(chat).build()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(uri).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify("automation:$occurrence", 0, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_alpha).setContentTitle(name).setContentText("Результат готов в чате Alphy.")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
    }
}
