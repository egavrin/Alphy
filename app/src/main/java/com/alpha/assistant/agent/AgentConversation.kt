package com.alpha.assistant.agent

import android.app.Application
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudTurn
import com.alpha.assistant.memory.MemoryProvenance
import com.alpha.assistant.memory.MemoryPurpose
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** Shares one foreground run between chat and the Android assistant. */
internal class AgentConversation(private val app: Application, private val scope: CoroutineScope, name: String,
    private val provider: ChatProvider, private val memory: MemoryStore, private val tools: ToolStore,
    private val getKey: suspend () -> String, private val selectedChat: () -> String?) {
    private val runtime = AgentRuntime(app, name, memory, tools)
    private val hosts = linkedSetOf<ComponentActivity>()
    private val dismissed = mutableSetOf<String>()
    private var running: Job? = null
    private var change: Job? = null
    private var revisionSession: CloudSession? = null
    private var currentRequest: Request? = null
    var plan by mutableStateOf<AgentPlan?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set

    private data class Request(val text: String, val reviewedText: String, val reviewedImages: List<String>)

    fun host(activity: ComponentActivity, attached: Boolean) {
        if (attached) hosts.add(activity) else hosts.remove(activity)
    }

    private fun foreground(): ComponentActivity? = hosts.lastOrNull { activity ->
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !activity.isFinishing &&
            app.getSystemService(PowerManager::class.java).isInteractive && !app.getSystemService(KeyguardManager::class.java).isDeviceLocked
    }

    suspend fun propose(conversation: String, text: String, reviewedText: String, reviewedImages: List<String>, key: String,
        session: CloudSession, active: () -> Boolean): Boolean {
        if (!active() || selectedChat() != conversation || running?.isActive == true || busy) return false
        val request = Request(text, reviewedText, reviewedImages)
        busy = true
        try {
            val previous = plan?.takeIf { it.conversationId == conversation && it.phase in AgentRuntime.editable }
            val next = withContext(Dispatchers.IO) { prepare(conversation, request, key, session, previous) }
            if (!active() || selectedChat() != conversation || running?.isActive == true) {
                withContext(Dispatchers.IO) { runtime.discard(next) }
                return false
            }
            currentRequest = request
            plan = next
            error = false
            return true
        } finally { busy = false }
    }

    private suspend fun prepare(conversation: String, request: Request, key: String, session: CloudSession,
        replacing: AgentPlan?): AgentPlan {
        val selection = memory.selectForRequest(request.text, MemoryPurpose.AGENT_PLAN)
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            session.check()
            val context = memory.withRequestFacts(selection, MemoryPurpose.AGENT_PLAN, MemoryProvenance.CLEAN) { facts ->
                JSONObject().put("currentRequest", request.text).put("deviceTime", ZonedDateTime.now().toString()).apply {
                    if (request.reviewedText.isNotEmpty()) put("reviewedReference", request.reviewedText)
                    if (facts.texts.isNotEmpty()) put("localMemoryReference", JSONArray(facts.texts))
                }.toString()
            }
            val instructions = AgentCommand.instructions + if (attempt == 0) "" else
                "\nThe prior response failed validation. Return the supported JSON fields only."
            var raw: String? = null
            provider.respond(key, CloudRequest(listOf(CloudTurn("user", context, request.reviewedImages)), false,
                instructions, jsonObject = true, agentPlan = true), session) { event ->
                if (event is CloudEvent.RoundComplete && !event.hasToolCalls) raw = event.text
            }
            session.check()
            val reply = raw
            val commands = reply?.let { runCatching { AgentCommand.parse(it) }.getOrNull() }
            if (reply != null && commands != null && commands.all { AgentIntent.permits(request.text, it) }) {
                val prepared = runtime.prepare(conversation, request.text, reply, replacing)
                if (currentCoroutineContext()[Job]?.isActive != true || runCatching { session.check() }.isFailure) {
                    runtime.discard(prepared)
                    currentCoroutineContext().ensureActive()
                    session.check()
                }
                return prepared
            }
        }
        throw CloudFailure("agent_plan", app.getString(R.string.agent_plan_error))
    }

    fun load(conversation: String) {
        if (plan?.conversationId == conversation) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runtime.latest(conversation) }
            if (selectedChat() != conversation || running?.isActive == true || plan?.conversationId == conversation) return@launch
            if (saved == null || saved.id in dismissed) return@launch
            currentRequest = Request(saved.instruction, "", emptyList())
            plan = saved
            error = false
        }
    }

    fun select(plan: AgentPlan, step: Int, option: Int) = change(plan) { runtime.select(it.id, it.revision, step, option) }
    fun refreshPermissions(plan: AgentPlan) = change(plan, runtime::refreshPermissions)

    fun revise(plan: AgentPlan, instruction: String) {
        if (instruction.isBlank() || instruction.length > 2000 || plan.phase !in AgentRuntime.editable) return
        val previous = currentRequest ?: Request(plan.instruction, "", emptyList())
        val revised = previous.copy(text = previous.text + "\n" + instruction.trim())
        if (revised.text.length > 16000) { error = true; return }
        val session = CloudSession()
        change(plan) {
            prepare(plan.conversationId, revised, getKey(), session, it)
        }?.let { job ->
            revisionSession = session
            scope.launch {
                job.join()
                if (revisionSession === session) {
                    revisionSession = null
                    if (!error && this@AgentConversation.plan?.id == plan.id && this@AgentConversation.plan?.revision != plan.revision)
                        currentRequest = revised
                }
            }
        }
    }

    private fun change(expected: AgentPlan, operation: suspend (AgentPlan) -> AgentPlan): Job? {
        if (busy || expected != plan || selectedChat() != expected.conversationId) return null
        busy = true
        error = false
        return scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { operation(expected) }
                if (plan == expected && selectedChat() == expected.conversationId) plan = next
                else withContext(Dispatchers.IO) { runtime.discard(next) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }.also { change = it }
    }

    fun execute(expected: AgentPlan) {
        if (busy || expected != plan || selectedChat() != expected.conversationId || foreground() == null) return
        busy = true
        error = false
        running = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val approved = runtime.approve(expected.id, expected.revision) ?: return@withContext
                    withContext(Dispatchers.Main) { plan = approved; busy = false }
                    val finished = runtime.execute(approved, active = { awaitForeground(approved.conversationId) }, launch = { intent ->
                        withContext(Dispatchers.Main) {
                            val activity = foreground()?.takeIf { selectedChat() == approved.conversationId }
                                ?: throw SecurityException("Foreground approval expired")
                            activity.startActivity(intent)
                        }
                    }, onUpdate = { state -> withContext(Dispatchers.Main) { showRun(state) } })
                    withContext(Dispatchers.Main) { showRun(finished) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }

    private suspend fun awaitForeground(conversation: String): Boolean = withContext(Dispatchers.Main) {
        // The compact panel can pause before the full chat resumes during a handoff.
        withTimeoutOrNull(750) {
            while (selectedChat() == conversation && foreground() == null) delay(25)
            selectedChat() == conversation && foreground() != null
        } ?: false
    }

    private fun showRun(state: AgentPlan) {
        if (plan?.id == state.id) plan = state
    }

    fun stop(plan: AgentPlan) {
        scope.launch(Dispatchers.IO) { runtime.stop(plan.id) }
    }

    fun dismiss(current: AgentPlan) {
        if (current != plan || current.phase == AgentPhase.RUNNING || busy) return
        dismissed += current.id
        plan = null
        currentRequest = null
        scope.launch(Dispatchers.IO) { runtime.discard(current) }
    }

    fun cancelRevision() {
        revisionSession?.cancel()
        change?.cancel()
    }

    fun forget(conversation: String) {
        if (plan?.conversationId == conversation) cancelRevision()
        val run = running.takeIf { plan?.conversationId == conversation }
        scope.launch(Dispatchers.IO) {
            runtime.latest(conversation)?.takeIf { it.phase == AgentPhase.RUNNING }?.let { runtime.stop(it.id) }
            run?.join()
            runtime.forget(conversation)
        }
        if (plan?.conversationId == conversation) { plan = null; currentRequest = null }
    }

    fun close() {
        cancelRevision()
        val work = listOfNotNull(running, change)
        work.forEach { it.cancel() }
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            work.forEach { it.join() }
            runtime.close()
            tools.close()
        }
    }
}
