package com.alpha.assistant.automations

import android.app.job.JobParameters
import android.app.job.JobService
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.ToolLibraryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentAutomationJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Pair<Job, CloudSession>>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId in jobs) return true
        val session = CloudSession()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val complete = try {
                withContext(Dispatchers.IO) {
                    val library = ToolLibraryStorage.current(this@AgentAutomationJobService)
                    if (params.extras.getString("library") != library.generation) return@withContext true
                    val id = params.extras.getString("run") ?: return@withContext true
                    AutomationStore(this@AgentAutomationJobService, library.automation).use { store ->
                        if (params.extras.getBoolean("expiry")) {
                            AgentAutomationRuntime.reconcile(this@AgentAutomationJobService, store, System.currentTimeMillis())
                            true
                        } else AgentAutomationRuntime.prepare(this@AgentAutomationJobService, store, id, session)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            } finally {
                // A terminal occurrence frees the rule for its next scheduled preparation.
                AutomationRuntime.refresh(this@AgentAutomationJobService)
            }
            jobs.remove(params.jobId)
            jobFinished(params, !complete)
        }
        jobs[params.jobId] = job to session
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.let { (job, session) -> session.cancel(); job.cancel() }
        return true
    }

    override fun onDestroy() {
        jobs.values.forEach { (_, session) -> session.cancel() }
        scope.cancel()
        super.onDestroy()
    }
}
