package com.alpha.assistant.scheduling

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.cloud.cloudKey
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.Source
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SourceRefreshJobService : JobService() {
    private data class RunningJob(val task: Job, val session: CloudSession?) {
        fun cancel() {
            session?.cancel()
            task.cancel()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Pair<String?, Int>, RunningJob>()

    override fun onStartJob(params: JobParameters): Boolean {
        val key = params.jobNamespace to params.jobId
        if (key in running) return true
        val session = if (params.jobNamespace == SEARCH_NAMESPACE) CloudSession() else null
        val work = scope.launch(start = CoroutineStart.LAZY) {
            val retry = try {
                withContext(Dispatchers.IO) { !deliver(params, session) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                true
            }
            running.remove(key)
            jobFinished(params, retry)
        }
        running[key] = RunningJob(work, session)
        work.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobNamespace to params.jobId)?.cancel()
        // The durable claim fences repeated callbacks, including rescheduling after a system stop.
        return true
    }

    override fun onDestroy() {
        running.values.forEach(RunningJob::cancel)
        running.clear()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun deliver(params: JobParameters, session: CloudSession?): Boolean = ToolStore(this).use { store ->
        val extras = params.extras
        if (extras.getString("library") != store.library.generation) return true
        val owner = extras.getString("owner") ?: return true
        if (session != null) return deliverSearch(store, owner, params, session)
        if (params.jobNamespace != SOURCE_NAMESPACE) return true
        val refresh = SourceRefresh(store)
        val current = refresh.ticket(owner) ?: return true
        if (!current.matches(params)) return enqueue(store, current)

        val authority = ScheduleAuthority(refresh, System::currentTimeMillis) { _, action ->
            val approved = refresh.status(owner)?.offer
            val declaration = JSONObject(action.declaration)
            if (action.kind != ScheduleAction.Kind.SOURCE_REFRESH || approved == null ||
                approved.sourceId != declaration.getString("id") ||
                approved.args != declaration.getJSONObject("args").toString() ||
                !refresh.refresh(approved, current)) ScheduleActionResult.STOPPED
            else {
                ToolWidgets.updateAll(this)
                ScheduleActionResult.COMPLETED
            }
        }
        authority.deliver(current.delivery)
        val next = refresh.advance(current) ?: return true
        enqueue(store, next)
    }

    private suspend fun deliverSearch(store: ToolStore, owner: String, params: JobParameters, session: CloudSession): Boolean {
        val keys = SecureKeyStore(this)
        val search = RecurringSearch(store, keys::consentIdentity)
        val ticket = search.status(owner)?.ticket ?: return true
        if (!ticket.matches(params)) return enqueue(store, ticket)
        when (val admission = search.reserve(ticket)) {
            is RecurringAdmission.Reserved -> runSearch(store, keys, search, admission.run, session)
            RecurringAdmission.Deferred -> return false
            RecurringAdmission.Skip -> Unit
        }
        val next = search.advance(ticket) ?: return true
        return enqueue(store, next)
    }

    private suspend fun runSearch(store: ToolStore, keys: SecureKeyStore, search: RecurringSearch,
        run: RecurringSearchRun, session: CloudSession) {
        val task = currentCoroutineContext()
        val deadlineAt = SystemClock.elapsedRealtime() + 90_000
        var results: List<Source>? = null
        var finished = false
        try {
            coroutineScope {
                val deadline = launch(Dispatchers.Default) { delay(90_000); session.cancel() }
                val lifecycle = launch {
                    store.changes.collect { if (!search.authorized(run)) session.cancel() }
                }
                try {
                    keys.observeConsent { if (keys.consentIdentity() != run.credential) session.cancel() }.use {
                        val key = keys.cloudKey(this@SourceRefreshJobService)
                        if (!search.authorized(run)) return@coroutineScope
                        task.ensureActive()
                        session.check()
                        results = DeepSeekProvider().search(key, run.query, session)
                        session.check()
                        if (SystemClock.elapsedRealtime() >= deadlineAt) results = null
                        finished = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    results = null
                    finished = true
                } finally {
                    deadline.cancel()
                    lifecycle.cancel()
                    session.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                search.finish(run, results, publish = finished && task.isActive)
            }
        }
    }

    private fun ScheduledTicket.matches(params: JobParameters): Boolean =
        params.jobId == jobId && params.extras.getString("activation") == delivery.activationId &&
            params.extras.getLong("due") == due

    companion object {
        private const val SOURCE_NAMESPACE = "alphy-source-refresh"
        private const val SEARCH_NAMESPACE = "alphy-recurring-search"
        private fun scheduler(context: Context, ticket: ScheduledTicket): JobScheduler =
            context.getSystemService(JobScheduler::class.java).forNamespace(
                if (ticket.delivery.key.scheduleId == RecurringSearch.ID) SEARCH_NAMESPACE else SOURCE_NAMESPACE)

        internal fun enqueue(store: ToolStore, ticket: ScheduledTicket): Boolean {
            if (!store.primaryLibrary) return false
            val context = store.context
            val extras = PersistableBundle().apply {
                putString("owner", ticket.delivery.key.ownerId)
                putString("library", store.library.generation)
                putString("activation", ticket.delivery.activationId)
                putLong("due", ticket.due)
            }
            val jobs = scheduler(context, ticket)
            val existing = jobs.getPendingJob(ticket.jobId)?.extras
            if (existing?.getString("activation") == ticket.delivery.activationId &&
                existing.getString("library") == store.library.generation && existing.getLong("due") == ticket.due) return true
            val builder = JobInfo.Builder(ticket.jobId, ComponentName(context, SourceRefreshJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency((ticket.due - System.currentTimeMillis()).coerceAtLeast(0L))
                .setPersisted(true)
                .setExtras(extras)
            if (ticket.delivery.key.scheduleId == RecurringSearch.ID)
                builder.setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_LINEAR)
            return jobs.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
        }

        internal fun cancel(store: ToolStore, ticket: ScheduledTicket) {
            if (!store.primaryLibrary) return
            val jobs = scheduler(store.context, ticket)
            val firstId = ticket.jobId - ticket.turn
            for (id in firstId..firstId + 1) {
                val extras = jobs.getPendingJob(id)?.extras ?: continue
                if (extras.getString("activation") == ticket.delivery.activationId &&
                    extras.getString("library") == store.library.generation) jobs.cancel(id)
            }
        }
    }
}
