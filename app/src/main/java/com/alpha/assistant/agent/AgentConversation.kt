package com.alpha.assistant.agent

import android.app.Application
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alpha.assistant.capabilities.PhotoCapture
import com.alpha.assistant.capabilities.PhotoLens
import com.alpha.assistant.semantic.SemanticRuntime
import com.alpha.assistant.semantic.SemanticUnavailable
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatDecisionContext
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.data.MessageProvenance
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
import kotlinx.coroutines.CompletableDeferred
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
    private val hosts = linkedMapOf<ComponentActivity, LifecycleEventObserver>()
    private val dismissed = mutableSetOf<String>()
    private var running: Job? = null
    private var change: Job? = null
    private var revisionSession: CloudSession? = null
    private var currentRequest: Request? = null
    private data class ClockReturn(val planId: String, val revision: Int, val activity: ComponentActivity,
        val departed: Boolean = false, val returned: Boolean = false)
    private var clockReturn by mutableStateOf<ClockReturn?>(null)
    private var captureHost: ComponentActivity? = null
    private var photoCapture by mutableStateOf<PhotoCapture?>(null)
    private var photoChat by mutableStateOf<String?>(null)
    private var photoQuery = 0
    private var photos by mutableStateOf<List<AgentPhoto>>(emptyList())
    var plan by mutableStateOf<AgentPlan?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set

    private data class Request(val text: String, val reviewedText: String, val reviewedImages: List<String>)

    fun host(activity: ComponentActivity, attached: Boolean) {
        if (!attached) {
            hosts.remove(activity)?.let(activity.lifecycle::removeObserver)
            if (captureHost === activity) photoCapture?.finish("cancelled")
            return
        }
        if (activity in hosts) return
        val observer = LifecycleEventObserver { _, event ->
            val handoff = clockReturn?.takeIf { it.activity === activity } ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> clockReturn = handoff.copy(departed = true, returned = false)
                Lifecycle.Event.ON_RESUME -> if (handoff.departed) clockReturn = handoff.copy(returned = true)
                else -> Unit
            }
        }
        hosts[activity] = observer
        activity.lifecycle.addObserver(observer)
    }

    private fun foreground(): ComponentActivity? = hosts.keys.lastOrNull { activity ->
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !activity.isFinishing &&
            app.getSystemService(PowerManager::class.java).isInteractive && !app.getSystemService(KeyguardManager::class.java).isDeviceLocked
    }

    suspend fun propose(conversation: String, text: String, reviewedText: String, reviewedImages: List<String>,
        needs: List<PhoneActionEvidence>, key: String,
        session: CloudSession, active: () -> Boolean): Boolean {
        if (!active() || selectedChat() != conversation || running?.isActive == true || busy) return false
        val request = Request(text, reviewedText, reviewedImages)
        busy = true
        try {
            plan?.takeIf { it.phase == AgentPhase.WAITING_FOR_CAMERA }?.let { waiting ->
                clockReturn = null
                withContext(Dispatchers.IO) { runtime.stop(waiting.id) }
                if (plan == waiting) plan = withContext(Dispatchers.IO) { runtime.latest(waiting.conversationId) }
            }
            val previous = plan?.takeIf { it.conversationId == conversation && it.phase in AgentRuntime.editable }
            val next = withContext(Dispatchers.IO) { prepare(conversation, request, needs, key, session, previous) }
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

    private suspend fun prepare(conversation: String, request: Request, needs: List<PhoneActionEvidence>, key: String, session: CloudSession,
        replacing: AgentPlan?): AgentPlan {
        if (!PhoneActionEvidence.valid(request.text, needs)) throw CloudFailure("agent_plan", app.getString(R.string.agent_plan_error))
        val embeddings = SemanticRuntime.get(app)
        val candidates = try {
            needs.map { AgentCommand.shortlist(it.context, embeddings) }
        } catch (_: SemanticUnavailable) {
            throw CloudFailure("agent_plan", app.getString(R.string.agent_plan_error))
        }
        if (candidates.any { it.isEmpty() }) throw CloudFailure("agent_plan", app.getString(R.string.agent_plan_error))
        val selection = memory.selectForRequest(request.text, MemoryPurpose.AGENT_PLAN)
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            session.check()
            val context = memory.withRequestFacts(selection, MemoryPurpose.AGENT_PLAN, MemoryProvenance.CLEAN) { facts ->
                JSONObject().put("currentRequest", request.text).put("steps", JSONArray(needs.map(PhoneActionEvidence::json)))
                    .put("deviceTime", ZonedDateTime.now().toString()).apply {
                    if (request.reviewedText.isNotEmpty()) put("reviewedReference", request.reviewedText)
                    if (facts.texts.isNotEmpty()) put("localMemoryReference", JSONArray(facts.texts))
                }.toString()
            }
            val correction = if (attempt == 0) "" else
                "\nThe prior response failed validation. Stay within the allowed commands and return only the supported JSON fields."
            val instructions = AgentCommand.instructions(candidates) + correction
            var raw: String? = null
            provider.respond(key, CloudRequest(listOf(CloudTurn("user", context, request.reviewedImages)), false,
                instructions, jsonObject = true, agentPlan = true, capabilityDiscovery = {
                    com.alpha.assistant.capabilities.AndroidCapabilities(app)
                        .discover(com.alpha.assistant.capabilities.CapabilityCaller.PHONE)
                        .put("eligibleByStep", JSONArray(candidates.map { JSONArray(it) }))
                }), session) { event ->
                if (event is CloudEvent.RoundComplete && !event.hasToolCalls) raw = event.text
            }
            session.check()
            val reply = raw
            val commands = reply?.let { runCatching { AgentCommand.parse(it) }.getOrNull() }
            if (reply != null && commands?.size == candidates.size &&
                commands.withIndex().all { (index, command) -> command.grant in candidates[index] }) {
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

    fun chatChanged(conversation: String?) {
        plan?.takeIf { it.conversationId != conversation }?.let { previous ->
            photoCapture?.finish("cancelled")
            if (clockReturn?.planId == previous.id) {
                clockReturn = null
                stop(previous)
            }
        }
        if (photoChat != conversation) {
            photoChat = conversation
            photos = emptyList()
            if (conversation != null) scope.launch { refreshPhotos(conversation) }
        }
        if (conversation != null) load(conversation)
    }

    fun keptPhotos(conversation: String): List<AgentPhoto> = if (photoChat == conversation) photos else emptyList()
    fun photoFile(photo: AgentPhoto) = runtime.photoFile(photo)

    fun deletePhoto(conversation: String, photo: AgentPhoto) {
        if (selectedChat() != conversation || photo !in keptPhotos(conversation)) return
        scope.launch {
            withContext(Dispatchers.IO) { runtime.deletePhoto(conversation, photo) }
            refreshPhotos(conversation)
        }
    }

    private suspend fun refreshPhotos(conversation: String) {
        if (photoChat != conversation || selectedChat() != conversation) return
        val query = ++photoQuery
        val kept = withContext(Dispatchers.IO) { runtime.keptPhotos(conversation) }
        if (photoChat == conversation && selectedChat() == conversation && query == photoQuery) photos = kept
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
            val key = getKey()
            val turn = CloudTurn("user", revised.text)
            val instructions = ChatToolReply.instructions(available = false)
            var needs: List<PhoneActionEvidence>? = null
            provider.respond(key, CloudRequest(listOf(turn), false, instructions, chatDecision = true, allowDevicePlan = true,
                decisionContext = ChatDecisionContext(turn, emptyList(), instructions, MessageProvenance.CLEAN)), session) { event ->
                if (event is CloudEvent.RoundComplete && !event.hasToolCalls && event.text.isEmpty() &&
                    event.provenance == MessageProvenance.CLEAN) needs = event.devicePlanSteps
            }
            val allowed = needs?.takeIf { PhoneActionEvidence.valid(revised.text, it) }
                ?: throw CloudFailure("agent_plan", app.getString(R.string.agent_plan_error))
            prepare(plan.conversationId, revised, allowed, key, session, it)
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

    fun cameraFor(activity: ComponentActivity?) = photoCapture.takeIf { activity != null && captureHost === activity }

    fun canContinue(expected: AgentPlan): Boolean = expected.phase == AgentPhase.WAITING_FOR_CAMERA &&
        clockReturn?.let { it.planId == expected.id && it.revision == expected.revision && it.returned } == true

    fun execute(expected: AgentPlan) {
        if (busy || running?.isActive == true || expected != plan || selectedChat() != expected.conversationId || foreground() == null) return
        val continuing = expected.phase == AgentPhase.WAITING_FOR_CAMERA
        if (continuing && !canContinue(expected)) return
        busy = true
        error = false
        running = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val approved = runtime.approve(expected.id, expected.revision, continuing) ?: return@withContext
                    withContext(Dispatchers.Main) {
                        if (continuing) clockReturn = null
                        plan = approved
                        busy = false
                    }
                    val finished = runtime.execute(approved, active = { awaitForeground(approved.conversationId) }, launch = { intent ->
                        withContext(Dispatchers.Main) {
                            val activity = foreground()?.takeIf { selectedChat() == approved.conversationId }
                                ?: throw SecurityException("Foreground approval expired")
                            if (approved.timerThenPhoto() && !continuing) clockReturn = ClockReturn(approved.id, approved.revision, activity)
                            try { activity.startActivity(intent) }
                            catch (failure: Exception) { clockReturn = null; throw failure }
                        }
                    }, capture = { lens -> withContext(Dispatchers.Main.immediate) { capturePhoto(approved, lens) } },
                        onUpdate = { state -> withContext(Dispatchers.Main) { showRun(state) } })
                    withContext(Dispatchers.Main) { showRun(finished) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            finally {
                busy = false
                selectedChat()?.takeIf { it != expected.conversationId }?.let(::load)
            }
        }
    }

    private suspend fun capturePhoto(approved: AgentPlan, lens: PhotoLens): Pair<String, Bitmap?> {
        val activity = foreground()?.takeIf { selectedChat() == approved.conversationId } ?: return "cancelled" to null
        val step = plan?.claimed ?: return "cancelled" to null
        val answer = CompletableDeferred<Pair<String, Bitmap?>>()
        val request = PhotoCapture(lens, {
            foreground() === activity && selectedChat() == approved.conversationId &&
                plan?.let { it.id == approved.id && it.revision == approved.revision && it.claimed == step } == true
        }) { status, photo -> answer.complete(status to photo) }
        captureHost = activity
        photoCapture = request
        try { return answer.await() }
        finally {
            request.finish("cancelled")
            captureHost = null
            photoCapture = null
        }
    }

    private suspend fun awaitForeground(conversation: String): Boolean = withContext(Dispatchers.Main) {
        // The compact panel can pause before the full chat resumes during a handoff.
        withTimeoutOrNull(750) {
            while (selectedChat() == conversation && foreground() == null) delay(25)
            selectedChat() == conversation && foreground() != null
        } ?: false
    }

    private suspend fun showRun(state: AgentPlan) {
        val previous = plan?.takeIf { it.id == state.id } ?: return
        plan = state
        if (state.phase != AgentPhase.RUNNING && state.phase != AgentPhase.WAITING_FOR_CAMERA) clockReturn = null
        if (previous.keptPhotos != state.keptPhotos) refreshPhotos(state.conversationId)
    }

    fun stop(plan: AgentPlan) {
        if (clockReturn?.planId == plan.id) clockReturn = null
        if (this.plan?.id == plan.id) photoCapture?.finish("cancelled")
        scope.launch {
            val stopped = withContext(Dispatchers.IO) {
                runtime.stop(plan.id)
                if (plan.phase == AgentPhase.WAITING_FOR_CAMERA) runtime.latest(plan.conversationId) else null
            }
            if (this@AgentConversation.plan == plan && stopped?.id == plan.id) this@AgentConversation.plan = stopped
        }
    }

    fun dismiss(current: AgentPlan) {
        if (current != plan || current.phase == AgentPhase.RUNNING || busy) return
        if (clockReturn?.planId == current.id) clockReturn = null
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
        if (plan?.conversationId == conversation) {
            cancelRevision()
            clockReturn = null
            photoCapture?.finish("cancelled")
        }
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
