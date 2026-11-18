package com.alpha.assistant.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPhase
import com.alpha.assistant.agent.AgentPlan
import com.alpha.assistant.agent.AgentStep
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.surfaces.AssistCapture
import com.alpha.assistant.surfaces.AssistantCapture
import com.alpha.assistant.voice.ApprovalWindow
import com.alpha.assistant.voice.LiveProtocol
import com.alpha.assistant.voice.LiveVoiceHost
import com.alpha.assistant.voice.LiveVoiceSession
import com.alpha.assistant.voice.SpokenAnswer
import com.alpha.assistant.voice.UserTurn
import com.alpha.assistant.voice.spokenAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The chat side of the voice mode (spec 5.5 to 5.8). Each hand-off goes to the chat agent as a normal message, and its result goes
 * to GPT-Live once, by its delegation id. A request outlives the mode (spec 5.5.9), so an end stops only the watch.
 */
internal class LiveVoiceBridge(
    private val model: ShellViewModel,
    private val session: LiveVoiceSession,
    // The first hand-off of an assistant button press carries the screen of this invocation (spec 5.11.4). The capture can hold a
    // screenshot, so the bridge drops it after that hand-off and at the end.
    private val assistantInvocation: String?,
    private var capture: AssistCapture?,
    // A spoken «да» or «нет» can decide a phone plan (spec 5.5.10 rule 10).
    private val voiceApproval: Boolean,
) : LiveVoiceHost {
    /** The delegation id to the assistant message id of each hand-off (spec 5.5.4). */
    val handOffs = LinkedHashMap<String, String>()
    /** The assistant message id to the user text of each typed turn (spec 5.7). */
    private val typed = LinkedHashMap<String, String>()
    private val progressed = HashSet<String>()
    private val done = HashSet<String>()
    /** The assistant message of the latest hand-off or typed turn (spec 5.2.3). */
    var cardId by mutableStateOf<String?>(null); private set
    private var watch: Job? = null
    /** The delegation id of the hand-off that proposed the phone plan, and the plan id (spec 5.5.8). */
    private var planHandOff: Pair<String, String>? = null
    /** Each plan state goes out once: plan id, revision, and phase. */
    private val planStates = HashSet<Triple<String, Int, AgentPhase>>()
    private var approval: Approval? = null
    /** The decided answer of the latest user turn and its rule-5 text. A delegation of that turn takes the text (rule 7). */
    private var answered: Pair<SpokenAnswer, String>? = null
    /** An assistant hand-off can wait for the screen capture, so the hand-offs go to the chat one at a time, in order. */
    private val handOffOrder = Mutex()

    /** The voice approval of one spoken REVIEW revision: its proposal event id and its window (spec 5.5.10 rules 2 and 3). */
    private class Approval(val plan: AgentPlan, val proposal: String) {
        var acknowledged = false
        var window: ApprovalWindow? = null
    }

    val working: Boolean get() = (handOffs.values + typed.keys).any { message(it)?.status in unfinishedStatuses }

    fun start(firstSpeech: Boolean) {
        session.start(this, firstSpeech)
        watch = model.viewModelScope.launch {
            // The session reads the account only at its start, so a later provider change or key removal ends it here (spec 5.8).
            launch {
                snapshotFlow { model.accountProvider }.drop(1).collect {
                    if (it != AiProvider.OPENAI) session.stop(string(R.string.live_voice_openai_only))
                }
            }
            // onHide, onLockscreenShown, and a new press end the system session of the press, and its voice mode (spec 5.11.6, 5.11.7).
            assistantInvocation?.let { id -> launch { snapshotFlow { AssistantCapture.isOpen(id) }.first { !it }; session.stop() } }
            snapshotFlow { model.snapshot to model.agent.plan }.collect { report() }
        }
    }

    fun typed(messageId: String, text: String) {
        typed[messageId] = text
        cardId = messageId
    }

    /** The capture of the press for the first request, spoken or typed. Later requests get null (spec 5.11.4). */
    fun takeFirstCapture(): AssistCapture? = capture?.takeIf { it.id == assistantInvocation }.also { capture = null }

    override fun history(): List<Message> = model.snapshot.selected?.messages.orEmpty()

    override fun delegation(id: String, request: String) {
        // Rule 7: the hand-off of an approval or rejection turn gets the rule-5 text, and the chat saves nothing.
        answered?.takeIf { spokenAnswer(request) == it.first }?.let { answered = null; session.commentary(id, it.second); return }
        if (request.isBlank()) { session.commentary(id, NOT_HEARD); return }
        if (assistantInvocation == null) { track(id, model.submitDraft(Draft(request), search = true)); return }
        val first = takeFirstCapture()
        model.viewModelScope.launch { track(id, handOffOrder.withLock { model.submitLiveRequest(request, assistantInvocation, first) }) }
    }

    // shortcut: every refusal gets the busy text. A spoken request reaches only the full queue, a busy «+» attachment, or a failed
    // screenshot copy, which shows its own error.
    private fun track(id: String, messageId: String?) {
        if (messageId == null) { session.commentary(id, BUSY); return }
        handOffs[id] = messageId
        cardId = messageId
    }

    /** Decides a turn in the approval window before the session hands the same turn over (spec 5.5.10 rules 4 to 8). */
    override fun turn(turn: UserTurn) {
        answered = null
        val window = approval?.window ?: return
        val answer = window.decide(turn)?.takeIf { it != SpokenAnswer.OTHER } ?: return
        val agent = model.agent
        // Rule 5: only the spoken revision, still in REVIEW, runs or closes. On any doubt, nothing runs and the card waits for a tap.
        val plan = agent.plan?.takeIf {
            it.id == window.planId && it.revision == window.revision && it.phase == AgentPhase.REVIEW && !agent.busy
        }
        val text = when {
            plan == null -> ON_SCREEN
            answer == SpokenAnswer.APPROVE -> { agent.execute(plan); if (agent.busy) STARTED else ON_SCREEN }
            else -> { agent.dismiss(plan); if (agent.plan == null) CANCELLED else ON_SCREEN }
        }
        val decided = answer to text
        answered = decided
        // A delegation of this turn comes in the same main-thread pass and takes the text. Otherwise the plan's hand-off gets it.
        model.viewModelScope.launch(Dispatchers.Main) {
            if (answered === decided) planHandOff?.let { session.commentary(it.first, text) }
        }
    }

    override fun handOffRunning() = working
    override fun appended(eventId: String) { approval?.takeIf { it.proposal == eventId }?.acknowledged = true }

    // shortcut: the window opens at the first output end after the acknowledgment (rule 3), which can be the tail of an earlier
    // line or early after a late transcript delta. Add a transcript-based end if the live check (6.3) shows an early window.
    override fun outputEnded(endMs: Long) {
        approval?.takeIf { it.acknowledged && it.window == null }?.let { it.window = ApprovalWindow(it.plan.id, it.plan.revision, endMs) }
    }

    override fun ended(status: String, settingsRequired: Boolean) { watch?.cancel(); watch = null; capture = null }

    /** Sends the mini-app progress and the result of each tracked message once (spec 5.5.5 to 5.5.8, 5.7.2). */
    private fun report() {
        val planReady = string(R.string.agent_plan_ready)
        for ((delegation, id) in handOffs) {
            val message = changed(id) ?: continue
            // The chat agent sets the plan in the same main-thread pass as this notice, so the plan is the one of this hand-off.
            if (message.status == "complete" && message.text == planReady) {
                planHandOff = model.agent.plan?.let { delegation to it.id }
                continue
            }
            session.commentary(delegation, when {
                message.status == "generating" -> MINI_APP_STARTED
                message.status != "complete" -> FAILED + message.error.take(MAX - FAILED.length)
                message.refinementChoices.isNotEmpty() -> ON_SCREEN
                message.toolId != null -> spoken(MINI_APP_READY, message.text)
                else -> spoken(ANSWER, message.text)
            })
        }
        for ((id, text) in typed) {
            val message = changed(id)?.takeIf { it.status == "complete" } ?: continue
            // A typed plan has no hand-off, so GPT-Live neither reads nor approves it.
            if (message.text == planReady) planHandOff = null
            session.thinking(spoken("Пользователь написал: ${LiveProtocol.spoken(text, TYPED_MAX)} Ответ Alphy на экране: ", message.text))
        }
        reportPlan()
    }

    /** Sends each state of the hand-off plan once, and keeps voice approval on its spoken REVIEW revision (spec 5.5.8, 5.5.10). */
    private fun reportPlan() {
        val (delegation, planId) = planHandOff ?: return
        val plan = model.agent.plan?.takeIf { it.id == planId }
        // Any other revision or phase closes the voice approval of the spoken one (rules 6 and 8).
        if (plan != approval?.plan) approval = null
        if (plan == null || !planStates.add(Triple(plan.id, plan.revision, plan.phase))) return
        val voiceProposal = plan.takeIf { it.phase == AgentPhase.REVIEW && voiceApproval }?.let(::proposal)
        val text = when (plan.phase) {
            AgentPhase.REVIEW -> voiceProposal ?: ON_SCREEN
            in SCREEN_PHASES -> ON_SCREEN
            AgentPhase.COMPLETED -> spoken(DONE, string(if (plan.alarmSequence()) R.string.agent_alarms_sent else R.string.agent_completed))
            AgentPhase.FAILED -> spoken(NOT_DONE, plan.results.lastOrNull().orEmpty())
            AgentPhase.STOPPED -> CANCELLED
            else -> return
        }
        val event = session.commentary(delegation, text)
        if (voiceProposal != null) approval = Approval(plan, event)
    }

    /** The full step list of the plan card (rule 2, ruling R16), or null when it does not fit in one append. */
    private fun proposal(plan: AgentPlan): String? {
        // A step holds its own commas, so «; » separates the steps. The list is not cut here.
        val list = LiveProtocol.spoken(plan.steps.joinToString("; ", transform = ::spokenStep), Int.MAX_VALUE)
        // A shorter list would let a «да» run steps that the user did not hear, so only a tap can approve it (review I1).
        return (PROPOSE + list + ASK).takeIf { it.length <= MAX }
    }

    /** One step as the plan card shows it: the label, the «before» and «after» texts, and the non-reversible note. */
    private fun spokenStep(step: AgentStep): String {
        val target = step.target ?: return ""
        val parts = listOfNotNull(target.label,
            target.before.takeIf { it.isNotEmpty() }?.let { string(R.string.agent_before, it) },
            target.after.takeIf { it.isNotEmpty() }?.let { string(R.string.agent_after, it) },
            string(R.string.agent_non_reversible).takeIf { step.irreversible })
        return parts.joinToString(", ") { it.trimEnd('.') }
    }

    /** The message when it enters `generating` or ends, once for each run. A deleted message ends without a result. */
    private fun changed(id: String): Message? {
        val message = message(id)
        if (message == null || message.status !in unfinishedStatuses) return message.takeIf { done.add(id) }
        // A refinement choice or a retry runs the same message again, and its new result goes to the same delegation (ruling R14).
        if (done.remove(id)) progressed -= id
        return message.takeIf { it.status == "generating" && progressed.add(id) }
    }

    private fun message(id: String) = model.snapshot.conversations.firstNotNullOfOrNull { c -> c.messages.find { it.id == id } }

    /** [prefix] and the speakable [reply] within the limit of one append (spec 5.5.5). */
    private fun spoken(prefix: String, reply: String) = prefix + LiveProtocol.spoken(reply, MAX - prefix.length)

    private fun string(id: Int, vararg args: Any): String =
        model.getApplication<Application>().let { if (args.isEmpty()) it.getString(id) else it.getString(id, *args) }

    private companion object {
        const val MAX = 1200
        /** The typed text of a thinking append. The rest of the limit is for the reply. */
        const val TYPED_MAX = 400
        const val NOT_HEARD = "Alphy не расслышал запрос."
        const val BUSY = "Alphy ещё занят предыдущими запросами."
        const val ANSWER = "Ответ Alphy: "
        const val MINI_APP_STARTED = "Alphy готовит мини-приложение, оно появится на экране."
        const val MINI_APP_READY = "Мини-приложение готово. Ответ Alphy: "
        const val FAILED = "Alphy не смог выполнить запрос: "
        const val ON_SCREEN = "Нужно подтверждение на экране."
        const val PROPOSE = "Alphy предлагает: "
        const val ASK = " Скажите «да», чтобы выполнить, или «нет», чтобы отменить."
        const val STARTED = "Запускаю."
        const val CANCELLED = "Действие отменено."
        const val DONE = "Готово. "
        const val NOT_DONE = "Не получилось. "
        /** The plan card needs a tap or a system dialog in these phases (rule 9). */
        val SCREEN_PHASES =
            setOf(AgentPhase.NEEDS_PERMISSION, AgentPhase.NEEDS_SELECTION, AgentPhase.UNAVAILABLE, AgentPhase.WAITING_FOR_CAMERA)
    }
}
