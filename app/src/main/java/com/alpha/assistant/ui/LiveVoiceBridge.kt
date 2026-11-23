package com.alpha.assistant.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPhase
import com.alpha.assistant.agent.AgentPlan
import com.alpha.assistant.agent.AgentStep
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.FollowDecision
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Message
import com.alpha.assistant.surfaces.AssistCapture
import com.alpha.assistant.surfaces.AssistantCapture
import com.alpha.assistant.tools.SceneAction
import com.alpha.assistant.tools.SceneNarration
import com.alpha.assistant.tools.ShownControl
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.VoicePress
import com.alpha.assistant.voice.ApprovalWindow
import com.alpha.assistant.voice.LiveEvent
import com.alpha.assistant.voice.LiveProtocol
import com.alpha.assistant.voice.LiveVoiceHost
import com.alpha.assistant.voice.LiveVoiceSession
import com.alpha.assistant.voice.SpokenAnswer
import com.alpha.assistant.voice.UserTurn
import com.alpha.assistant.voice.spokenAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import org.json.JSONObject

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
    /** The assistant message of each hand-off and typed turn, as state, so that [working] is state. The maps above stay ordered maps. */
    private val tracked = mutableStateListOf<String>()
    private val progressed = HashSet<String>()
    private val done = HashSet<String>()
    /** The assistant message id of each running screen of a hand-off (plan D, Db2). */
    private val screens = HashSet<String>()
    /** The assistant message id of each running change of the stage app by a hand-off (plan D, Dc2). */
    private val changes = HashSet<String>()
    /** The assistant message id of each hand-off whose screen or change goes on to the full generation (ruling FX-4). */
    private val fulls = HashSet<String>()
    /** The assistant message of the latest hand-off or typed turn (spec 5.2.3). */
    var cardId by mutableStateOf<String?>(null); private set
    /** The app of the latest tracked message that made or changed one, for the stage (plan D, Da1). The end of the mode clears it. */
    var stageToolId by mutableStateOf<String?>(null); private set
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

    private val pressed = MutableSharedFlow<VoicePress>(extraBufferCapacity = 8)
    /** Each spoken press of a control of the stage app (plan D, Dc1). The stage runs it as a tap of that control. */
    val presses: SharedFlow<VoicePress> = pressed.asSharedFlow()

    /** A hand-off or typed turn is queued or runs. A reader sees it change when one starts and when it ends. */
    val working: Boolean get() = tracked.any { message(it)?.status in unfinishedStatuses }

    /** The last actions on the stage app, oldest first (plan "one scene", decision 10). A new stage app clears them. */
    val actions: List<SceneAction> get() = recent.toList()
    private val recent = ArrayDeque<SceneAction>()
    /** The stage app that the watcher reads, and the value of each of its inputs by label. */
    private var watchedTool: String? = null
    private var watchedValues: Map<String, Any?> = emptyMap()
    /**
     * The control and the who of each own press that has not changed its input yet, with its uptime, oldest first: the next change of that
     * control is this who (decision 10). A list, so a voice press right after a press of the walk does not take the who of the walk.
     */
    private val expected = ArrayDeque<Pair<SceneAction, Long>>()
    private var lastTapMs = 0L
    /** The new scene of a hand-off whose start waits for its narration call or for the list of tools (decision 14). */
    private var sceneWaits: String? = null

    /** The person turn of the follow signal and of the classifier (plan "one scene", decisions 19 and 20, K3 to K7). */
    private class PersonTurn(val texts: Int, val firstPiece: Long) {
        val words = StringBuilder()
        /** The server event ids of the caption pieces of the person, in order. */
        val pieces = ArrayList<String>()
        var handOff = false
        var pair: SpokenPair? = null
        /** The words of the voice in this turn while the host sent no text, and whether the follow call started. */
        val said = StringBuilder()
        var followed = false
        var followDue = false
        /** The end of the phrase came (K3), a command answered the turn ([acted]), and K4 or K6 moved the picture ([moved], K7). */
        var ended = false
        var acted = false
        var moved = false
    }
    private var personTurn: PersonTurn? = null
    /** The input answers of the classifier by the event id of their caption piece, for the turn that runs (K3). */
    private val inputAnswers = HashMap<String, LiveEvent.Decision>()
    private var phraseJob: Job? = null
    /** The number of each output caption piece by its event id, and the count so far (K5). */
    private val outputPieces = HashMap<String, Long>()
    private var pieceCount = 0L
    /** The scene item of the classifier: its server id, its scene and text, and the client event id of a create with no answer yet (K2). */
    private var sceneItem: String? = null
    private var sceneItemTool: String? = null
    private var sceneItemText: String? = null
    private var sceneItemCreate: String? = null
    /** The follow screens of voice-only turns by their assistant message id, with whether the voice gave the answer (decision 19, C5). */
    private val follows = HashMap<String, Boolean>()
    /** Whether the stage scene of each running change had steps before the change, and the changed scene and revision that can walk (C4). */
    private val changedFromSteps = HashMap<String, Boolean>()
    private var changeWalk: Pair<String, Int>? = null
    /** The scene of [sceneWaits] may only walk: a changed scene says no narration line (C4). */
    private var sceneWalkOnly = false
    /** The scene and the step of a move of the voice that runs, so a repeated answer for the same step presses nothing more (K4, K6). */
    private var moving: Pair<String, Int>? = null

    /** The walk of a new stepped scene (plan "one scene", decisions 14 to 17). Branch (b) of Task 24, by ruling 1 of stage 2. */
    private val narrator = StageNarrator(send = { session.thinking(it) }, press = ::pressStep, cardText = ::cardText,
        onsetAfter = session::onsetAfter, cancelOnset = session::cancelOnset, skip = session::skip, skipAfterSpeech = true,
        scope = model.viewModelScope, pieces = { pieceCount })

    /** A walk goes on: the strip keeps the speaking status, and a reply end opens no approval window (decision 17). */
    val walking: Boolean get() = narrator.walking

    /** The plan card that left the stage (plan "one scene", decision 21). The chat keeps it. */
    var hiddenPlan by mutableStateOf<String?>(null); private set

    /** A waiting plan card leaves the stage (decision 21): at a follow clear or screen, or at a later completed hand-off. */
    private fun planLeaves() {
        model.agent.plan?.takeIf { it.phase == AgentPhase.REVIEW || it.phase in SCREEN_PHASES }?.let { hiddenPlan = it.id }
    }

    /**
     * The text of the work card that replaces the stage app while the latest hand-off searches, or builds a screen with no frame yet (decision
     * 21). The subject is the words of the hand-off, cut at a word end at [WORK_SUBJECT_MAX] characters. Null when no card shows.
     */
    val workCard: String? get() {
        val id = cardId?.takeIf { it in handOffs.values } ?: return null
        val message = message(id)?.takeIf { it.status in unfinishedStatuses } ?: return null
        val words = message.replyTo?.let(::message)?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val subject = if (words.length <= WORK_SUBJECT_MAX) words else words.take(WORK_SUBJECT_MAX + 1).substringBeforeLast(' ', words.take(WORK_SUBJECT_MAX)).trimEnd()
        return when {
            message.searchStatus == "searching" -> string(R.string.stage_work_search, subject)
            id in screens && model.tools.stagePreviews.current == null -> string(R.string.stage_work_build, subject)
            else -> null
        }
    }

    /**
     * Runs [control] of the stage app [toolId] as a tap. [who] is "voice" for a routing press and «Дальше», and "narrator" for a press of a
     * walk: the next change of that control is an action of [who]. A press of a step of a stepped scene waits for the ready stage and tries
     * again until the list of tools shows that step, for at most [STEP_PRESS_MS] (stage 7, fix list 2, H2): the set of a step is idempotent,
     * and a press that did not run changed nothing. Any other press, such as a toggle, runs one time: false when no stage ran it in
     * [PRESS_WAIT_MS].
     */
    suspend fun press(toolId: String, control: ShownControl, who: String = "voice", cause: String? = null): Boolean {
        fun screen() = model.tools.tools.find { it.id == toolId }?.let { ShownScreen.of(it) }
        val stepControl = screen()?.takeIf { s -> s.steps.any { control.label == "${s.stepControl}: $it" } }?.stepControl
            ?: return tap(toolId, control, who, cause)
        fun shown() = screen()?.step?.let { "$stepControl: ${it.label}" } == control.label
        // The stage commits the tap, and the list of tools reloads after the store changes.
        return (withTimeoutOrNull(STEP_PRESS_MS) {
            while (!shown()) if (!tap(toolId, control, who, cause) || withTimeoutOrNull(PRESS_WAIT_MS) { while (!shown()) delay(20) } == null) delay(RETRY_MS)
            true
        } == true).also { if (!it) android.util.Log.w("AlphyPress", "${control.actionId}: the step did not show in $STEP_PRESS_MS ms") }
    }

    private suspend fun tap(toolId: String, control: ShownControl, who: String, cause: String?): Boolean {
        val own = SceneAction(who, control.label, control.value, cause) to SystemClock.uptimeMillis()
        expected.addLast(own)
        val press = VoicePress(toolId, control)
        var ran = false
        try {
            if (pressed.subscriptionCount.value > 0 && pressed.tryEmit(press)) ran = withTimeoutOrNull(PRESS_WAIT_MS) { press.ran.await() } == true
            else android.util.Log.w("AlphyPress", "${control.actionId}: no stage took the press")
        } finally {
            // A stage that takes the press later, or after a cancel of a press of a stopped walk, finds it failed, so no tap runs.
            press.ran.complete(false)
            if (!ran) expected.remove(own)
        }
        return ran
    }

    /**
     * The sentence that the voice says for a press of a step of the stepped scene [toolId] by [control] («Дальше», decision 16), or null for
     * any other press. A press of a step stops a walk.
     */
    fun stepSentence(toolId: String, control: ShownControl): String? {
        val record = model.tools.tools.find { it.id == toolId } ?: return null
        val screen = ShownScreen.of(record) ?: return null
        val step = screen.stepControl?.let { control.label.removePrefix("$it: ").takeIf { label -> label != control.label } } ?: return null
        val index = screen.steps.indexOf(step).takeIf { it >= 0 } ?: return null
        narrator.stop(WalkStop.ANSWER)
        return SceneNarration.of(appFileOf(record)).sentence(index + 1)
    }

    /** «Продолжай» (decision 16): the walk of the rest of the steps of [toolId], and the sentence that the voice says first, or null. */
    fun rest(toolId: String): String? {
        val record = model.tools.tools.find { it.id == toolId } ?: return null
        val screen = ShownScreen.of(record) ?: return null
        val present = screen.step?.index ?: return null
        return narrator.rest(toolId, screen.steps, SceneNarration.of(appFileOf(record)), present)
    }

    /** The app file of [record], or the kept text with the narration that the controller saved at its revision, before the list of tools reloads. */
    private fun appFileOf(record: ToolRecord) = model.tools.savedNarration(record) ?: ToolSpec.availablePersisted(record)?.appFile

    /** The press of [step] of the scene of the walk, as a narrator action with [cause]. It returns when the list of tools shows the step. */
    private suspend fun pressStep(step: String, cause: String?): Boolean {
        val toolId = narrator.scene ?: return false
        val screen = model.tools.tools.find { it.id == toolId }?.let { ShownScreen.of(it) } ?: return false
        if (screen.step?.label == step) return true
        val control = screen.controls.find { it.label == "${screen.stepControl}: $step" }
            ?: return false.also { android.util.Log.w("AlphyPress", "${screen.stepControl?.let { "a step" } ?: "no step control"}: no control in the shown screen") }
        return press(toolId, control, "narrator", cause)
    }

    /** The text of the card of the scene of the walk now: the texts in its card after the title (decision 14). */
    private fun cardText(): String? {
        val record = narrator.scene?.let { id -> model.tools.tools.find { it.id == id } } ?: return null
        fun nodes(node: JSONObject): List<JSONObject> = listOf(node) + node.optJSONArray("children")?.let { children ->
            List(children.length()) { children.getJSONObject(it) }.flatMap(::nodes) }.orEmpty()
        val card = runCatching { nodes(JSONObject(record.ui)).lastOrNull { it.optString("type") == "card" } }.getOrNull() ?: return null
        val texts = nodes(card).drop(1).mapNotNull { node -> node.optString("text").takeIf { it.isNotBlank() } }
        return texts.drop(1).ifEmpty { texts }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /**
     * The watcher of the stage app (decision 10). Each change of an input is one action: of the who of an own press, else a tap. Changes of
     * one control by tap less than 1 s apart replace the last action, so a slider drag is one action. A new stage app adds no action.
     */
    private fun observe(record: ToolRecord?) {
        syncSceneItem()
        changeWalks()
        if (record == null) return
        sceneWaits?.takeIf { it == record.id }?.let { beginScene(it) }
        val screen = ShownScreen.of(record) ?: return
        val values = screen.values.toMap()
        if (record.id != watchedTool) { watchedTool = record.id; watchedValues = values; recent.clear(); expected.clear(); return }
        val previous = watchedValues
        watchedValues = values
        for ((label, value) in values) if (previous.containsKey(label) && previous[label] != value) changed(record, screen, label, value)
    }

    private fun changed(record: ToolRecord, screen: ShownScreen, label: String, value: Any?) {
        val now = SystemClock.uptimeMillis()
        expected.removeAll { now - it.second >= EXPECT_MS }
        // The oldest own press of this control with this value, else the oldest of this control.
        fun mine(action: SceneAction) = action.control == label || action.control.startsWith("$label: ")
        val own = expected.firstOrNull { mine(it.first) && it.first.value == value } ?: expected.firstOrNull { mine(it.first) }
        own?.let(expected::remove)
        val who = own?.first?.who ?: "tap"
        if (who == "tap" && now - lastTapMs < TAP_MERGE_MS && recent.lastOrNull()?.let { it.who == "tap" && it.control == label } == true) recent.removeLast()
        recent.addLast(SceneAction(who, label, value, own?.first?.cause))
        while (recent.size > ShownScreen.MAX_ACTIONS) recent.removeFirst()
        if (who != "tap") return
        lastTapMs = now
        // A tap stops a walk (decision 11). A tap on a step while the voice is silent says the sentence of that step (ruling 3 of stage 2:
        // the touch gate holds only the microphone, so it does not hold this append).
        if (narrator.walking) narrator.stop(WalkStop.TAP)
        if (label != screen.stepControl || session.speaksNow) return
        val sentence = screen.step?.let { step -> SceneNarration.of(appFileOf(record)).sentence(step.index) } ?: return
        session.thinking(LiveProtocol.MARK + sentence)
    }

    /** A new scene of a hand-off came on the stage (decision 14): it starts when its narration call ended and the list of tools holds it. */
    private fun sceneCame(toolId: String, walkOnly: Boolean = false) {
        narrator.stop(WalkStop.ANSWER)
        sceneWaits = toolId
        sceneWalkOnly = walkOnly
        beginScene(toolId)
    }

    /** A stepped scene starts its walk. A scene with one narration line says it once, unless only a walk may start. Each other scene says nothing. */
    private fun beginScene(toolId: String) {
        if (sceneWaits != toolId || model.tools.narrationPending(toolId)) return
        val record = model.tools.tools.find { it.id == toolId } ?: return
        sceneWaits = null
        val narration = SceneNarration.of(appFileOf(record))
        val screen = ShownScreen.of(record) ?: return
        if (narration.stepped && screen.steps.isNotEmpty()) narrator.start(toolId, screen.steps, narration)
        else if (!sceneWalkOnly) narration.say?.let { session.thinking(LiveProtocol.MARK + it) }
    }

    /** C4: the changed scene of [changeWalk] walks when the list of tools holds its revision with steps. A result with no steps says nothing. */
    private fun changeWalks() {
        val (id, revision) = changeWalk ?: return
        val record = model.tools.tools.find { it.id == id }?.takeIf { it.revision >= revision } ?: return
        changeWalk = null
        if (ShownScreen.of(record)?.steps.isNullOrEmpty()) return
        sceneCame(id, walkOnly = true)
    }

    /** A touch on the stage starts ([down]) or ends. A press by voice is no touch. The session hears no microphone sound for its time. */
    fun stageTouch(down: Boolean) = session.touch(down)

    fun start(firstSpeech: Boolean) {
        session.start(this, firstSpeech, LiveProtocol.triggers().takeUnless { classifierOffForApp })
        model.tools.narrationEnded = { toolId -> beginScene(toolId); syncSceneItem() }
        watch = model.viewModelScope.launch {
            // The session reads the account only at its start, so a later provider change or key removal ends it here (spec 5.8).
            launch {
                snapshotFlow { model.accountProvider }.drop(1).collect {
                    if (it != AiProvider.OPENAI) session.stop(string(R.string.live_voice_openai_only))
                }
            }
            // onHide, onLockscreenShown, and a new press end the system session of the press, and its voice mode (spec 5.11.6, 5.11.7).
            assistantInvocation?.let { id -> launch { snapshotFlow { AssistantCapture.isOpen(id) }.first { !it }; session.stop() } }
            // The stage app that the person and the voice change (plan "one scene", decision 10).
            launch { snapshotFlow { model.stageToolId?.let { id -> model.tools.tools.find { it.id == id } } }.collect(::observe) }
            snapshotFlow { model.snapshot to model.agent.plan }.collect { report() }
        }
    }

    fun typed(messageId: String, text: String) {
        typed[messageId] = text
        tracked += messageId
        cardId = messageId
    }

    /**
     * The screen of the hand-off of [messageId] starts: the voice says [answer] now, and nothing when the screen comes or when a screen
     * that the person did not ask to see is dropped. A screen that the person asked to see goes on to the full generation
     * ([fullStarted]) when the fast path gives no app. A message of no hand-off gets nothing.
     */
    fun screenStarted(messageId: String, answer: String) {
        val delegation = handOffs.entries.firstOrNull { it.value == messageId }?.key ?: return
        screens += messageId
        session.commentary(delegation, spoken(LiveProtocol.MARK, answer))
    }

    /**
     * The change of the stage app by the hand-off of [messageId] starts: the voice says nothing while it runs, and the answer when the
     * changed or the new screen is there. A change that gives neither goes on to the full generation ([fullStarted]). A message of no
     * hand-off gets nothing.
     */
    fun changeStarted(messageId: String) {
        if (messageId !in handOffs.values) return
        changes += messageId
        changedFromSteps[messageId] = stageScreen()?.steps?.isNotEmpty() == true
    }

    /**
     * The full generation of the hand-off of [messageId] starts, because its screen or its change gave no app on the fast path (ruling FX-4).
     * The voice says [FULL_WAIT] now, and at the end what its screen or its change says. A failure says its reason group and [FULL_HINT].
     */
    fun fullStarted(messageId: String) {
        val delegation = handOffs.entries.firstOrNull { it.value == messageId }?.key ?: return
        fulls += messageId
        session.commentary(delegation, FULL_WAIT)
    }

    /** The capture of the press for the first request, spoken or typed. Later requests get null (spec 5.11.4). */
    fun takeFirstCapture(): AssistCapture? = capture?.takeIf { it.id == assistantInvocation }.also { capture = null }

    override fun history(): List<Message> = model.snapshot.selected?.messages.orEmpty()

    override fun outputPiece(eventId: String?) { pieceCount++; eventId?.let { outputPieces[it] = pieceCount } }

    override fun outputText(text: String, startMs: Long, endMs: Long, replyStart: Boolean, arrivedNanos: Long) {
        followWords(text, replyStart)
        narrator.output(text, replyStart, arrivedNanos)
    }

    /**
     * The follow signal (plan "one scene", decisions 19 and 20): the first reply of a person turn with no hand-off and no host text after it
     * makes the spoken pair, and the words of the voice go to the pair. The follow call starts at the first sentence end of the voice,
     * [FOLLOW_START_MS] after the voice began its reply, or at the reply end, the earliest of the three (block 2 plan, V2). So the call sees
     * whether the voice answered or only promised an answer (C5), and one long sentence does not delay the scene.
     */
    private fun followWords(text: String, replyStart: Boolean) {
        val turn = personTurn?.takeIf { !it.handOff && !it.acted && session.texts == it.texts } ?: return
        if (turn.pair == null) {
            if (!replyStart || turn.words.isBlank()) return
            turn.pair = model.startSpokenPair(turn.words.toString())
            // A later turn closes this one, so the timer starts no call for it then.
            model.viewModelScope.launch { delay(FOLLOW_START_MS); if (personTurn === turn) follow(turn) }
        }
        turn.said.append(text)
        model.appendSpokenWords(checkNotNull(turn.pair), text)
        if (turn.said.any { it in SENTENCE_ENDS }) follow(turn)
    }

    /** Starts the follow call of [turn] once. While the end of its phrase can still find a command (K3), the call waits for that end. */
    private fun follow(turn: PersonTurn) {
        val pair = turn.pair ?: return
        if (turn.followed || turn.handOff || turn.acted || session.texts != turn.texts) return
        if (!turn.ended && phraseJob?.isActive == true) { turn.followDue = true; return }
        turn.followed = true
        model.followTurn(turn.words.toString().trim(), pair)
    }

    /** The follow decision of a voice-only turn (decision 18). Mark and press select the option quietly. After a classifier move, no screen and no clear (K7). */
    fun followed(decision: FollowDecision, pair: SpokenPair, toolId: String?) {
        val turn = personTurn?.takeIf { it.pair == pair } ?: return
        model.tools.traceFollow(decision.kind)
        when (decision) {
            is FollowDecision.None -> Unit
            is FollowDecision.Screen -> if (!turn.moved) model.followScreen(pair, decision)?.let { id -> follows[id] = decision.answered; tracked += id; planLeaves() }
            is FollowDecision.Mark, is FollowDecision.Press -> {
                val target = if (decision is FollowDecision.Mark) decision.target else (decision as FollowDecision.Press).target
                val record = toolId?.let { id -> model.tools.tools.find { it.id == id } } ?: return
                // The list of the follow call: with the continue control, which walks the rest of the steps as the routing press does.
                val screen = ShownScreen.of(record, continueLabel = string(R.string.stage_continue_steps))
                val control = screen?.controls?.singleOrNull { it.label == target } ?: return
                if (control.actionId == ShownScreen.CONTINUE_ACTION) rest(record.id)?.let { session.thinking(LiveProtocol.MARK + it) }
                else model.viewModelScope.launch { press(record.id, control, "voice") }
            }
            is FollowDecision.Clear -> if (!turn.moved) { stageToolId = null; planLeaves() }
        }
    }

    override fun inputStarted() {
        narrator.stop(WalkStop.SPEECH)
        closeTurn()
        personTurn = PersonTurn(session.texts, pieceCount)
        inputAnswers.clear()
    }

    override fun inputText(delta: String, eventId: String?) {
        // K3: the phrase end (1000 ms) comes before the turn gap of the session (1200 ms), so a piece after it starts a new person turn.
        if (personTurn?.ended == true && delta.isNotBlank()) inputStarted()
        val turn = personTurn ?: return
        turn.words.append(delta)
        // A caption piece can come after the voice began its reply, so the pair of the turn gets the whole words of the person.
        turn.pair?.let { model.spokenUserWords(it, turn.words.toString()) }
        eventId?.let { turn.pieces += it }
        // K3: with no hand-off, the end of a phrase is 1000 ms with no new caption piece of the person.
        if (turn.handOff || turn.ended || classifiedScreen() == null) return
        phraseJob?.cancel()
        phraseJob = model.viewModelScope.launch {
            delay(PHRASE_END_MS)
            phraseJob = null
            if (!phraseEnd(turn, null, turn.words.toString().trim()) && turn.followDue) follow(turn)
        }
    }

    /** The turn before a new turn or the end: its spoken pair persists, and its follow call and the screen build of its follow call stop (decisions 19, 20). */
    private fun closeTurn() {
        phraseJob?.cancel(); phraseJob = null
        val turn = personTurn ?: return
        personTurn = null
        turn.pair?.let(model::persistSpokenPair)
        model.cancelFollow()
    }

    override fun delegation(id: String, request: String) {
        // A new hand-off stops a walk (decision 14).
        narrator.stop(WalkStop.ANSWER)
        // A hand-off drops the spoken pair of its turn and stops the follow call (decisions 19 and 20, review focus 3).
        val turn = personTurn
        turn?.let { it.handOff = true; it.pair?.let(model::dropSpokenPair); it.pair = null }
        model.cancelFollow()
        // K3: the hand-off is the end of the phrase, so the 1000 ms timer stops, and the hand-off gets the one answer.
        phraseJob?.cancel(); phraseJob = null
        // Rule 7: the hand-off of an approval or rejection turn gets the rule-5 text, and the chat saves nothing.
        answered?.takeIf { spokenAnswer(request) == it.first }?.let { answered = null; session.commentary(id, it.second); return }
        // K3, Assumption: a hand-off of a turn that the host already answered gets an empty append and no routing call.
        if (turn?.acted == true) { session.thinking("", id); return }
        if (request.isBlank()) { session.commentary(id, NOT_HEARD); return }
        // K3: the newest piece of the turn can still wait for its answer, for at most 300 ms.
        val last = turn?.pieces?.lastOrNull()
        if (turn != null && !turn.ended && last != null && last !in inputAnswers && classifiedScreen() != null) {
            model.viewModelScope.launch {
                withTimeoutOrNull(HAND_OFF_WAIT_MS) { while (last !in inputAnswers) delay(10) }
                handOff(id, request, turn)
            }
            return
        }
        handOff(id, request, turn)
    }

    private fun handOff(id: String, request: String, turn: PersonTurn?) {
        if (turn != null && phraseEnd(turn, id, request)) return
        if (assistantInvocation == null) { track(id, model.submitDraft(Draft(request), search = true, spoken = true)); return }
        val first = takeFirstCapture()
        model.viewModelScope.launch { track(id, handOffOrder.withLock { model.submitLiveRequest(request, assistantInvocation, first) }) }
    }

    /**
     * The end of a phrase (K3, K4): the newest input answer of the turn decides. A command moves the step, and the voice says its sentence with
     * no routing call: then this gives true. A question about another step moves the picture and says nothing, and the turn goes on.
     */
    private fun phraseEnd(turn: PersonTurn, handOff: String?, words: String): Boolean {
        if (turn.ended) return turn.acted
        turn.ended = true
        phraseJob?.cancel()
        if (turn !== personTurn) return false
        val screen = classifiedScreen() ?: return false
        val present = screen.step?.index ?: return false
        val answer = turn.pieces.lastOrNull()?.let(inputAnswers::get) ?: return false
        fun of(name: String) = answer.answers.find { it.name == name }
        fun step(min: Double) = of(LiveProtocol.USER_STEP)?.takeIf { it.confidence >= min }?.choice?.toIntOrNull()?.takeIf { it in 1..screen.steps.size }
        val command = of(LiveProtocol.USER_COMMAND)
        if (command != null && command.choice in COMMANDS && command.confidence >= COMMAND_MIN) {
            // V1: continue walks the rest of the steps as the routing press does. At the last step it goes to the routing call, as next does.
            if (command.choice == "continue") {
                if (present >= screen.steps.size) return false
                val say = answer(turn, handOff, words)
                rest(screen.toolId)?.let { say(LiveProtocol.MARK + it, it) } ?: say(PRESS_FAILED, PRESS_FAILED)
                return true
            }
            val target = when (command.choice) {
                "next" -> present + 1
                "back" -> present - 1
                "repeat" -> present
                else -> step(GO_TO_STEP_MIN)
            }
            // next at the last step and go_to with no valid step go to the routing call, as in Alpha 19.
            if (target == null || target > screen.steps.size) return false
            act(turn, screen, target, handOff, words)
            return true
        }
        if (command?.choice == "none") step(QUESTION_MIN)?.takeIf { it != present }?.let { turn.moved = true; moveTo(screen, it, "question") }
        return false
    }

    /** A spoken command (K3): the step moves with who voice, and the voice says its sentence. The chat keeps the words and the sentence. */
    private fun act(turn: PersonTurn, screen: ShownScreen, target: Int, handOff: String?, words: String) {
        narrator.stop(WalkStop.ANSWER)
        val say = answer(turn, handOff, words)
        if (target < 1) { say(FIRST_STEP, FIRST_STEP); return }
        val record = model.tools.tools.find { it.id == screen.toolId }
        val sentence = record?.let { SceneNarration.of(appFileOf(it)).sentence(target) } ?: screen.steps[target - 1]
        moveTo(screen, target, "command") { ran -> if (ran) say(LiveProtocol.MARK + sentence, sentence) else say(PRESS_FAILED, PRESS_FAILED) }
    }

    /**
     * A command answers [turn]: its spoken pair and its follow call stop. The result says a sentence to the voice, on the hand-off when there
     * is one, and the chat keeps the words of the person with the saved text.
     */
    private fun answer(turn: PersonTurn, handOff: String?, words: String): (String, String) -> Unit {
        turn.acted = true
        turn.pair?.let(model::dropSpokenPair); turn.pair = null
        model.cancelFollow()
        return { text, saved ->
            if (handOff != null) session.commentary(handOff, text) else session.thinking(text)
            model.saveVoiceTurn(words, saved)
        }
    }

    /** Moves the stepped [screen] to [step] with who voice. [then] gets whether the stage shows the step. */
    private fun moveTo(screen: ShownScreen, step: Int, cause: String, then: ((Boolean) -> Unit)? = null) {
        val move = screen.toolId to step
        // An answer comes for each caption piece, so the same step can be named again before the stage shows the first press.
        if (moving == move) { then?.invoke(true); return }
        val control = screen.controls.find { it.label == "${screen.stepControl}: ${screen.steps[step - 1]}" }
        moving = move
        model.viewModelScope.launch {
            val ran = try { screen.step?.index == step || control != null && press(screen.toolId, control, "voice", cause) }
                finally { if (moving == move) moving = null }
            then?.invoke(ran)
        }
    }

    override fun decision(decision: LiveEvent.Decision) {
        if (decision.input) { inputAnswers[decision.sourceEventId] = decision; return }
        val screen = classifiedScreen() ?: return
        val answer = decision.answers.find { it.name == LiveProtocol.SPOKEN_STEP } ?: return
        val step = answer.choice.toIntOrNull()?.takeIf { it in 1..screen.steps.size } ?: return
        val piece = outputPieces[decision.sourceEventId] ?: return
        // K5: in a walk, only the step of the waiting sentence counts.
        if (narrator.walking) { if (answer.confidence >= WALK_MIN && narrator.scene == screen.toolId) narrator.classified(step, piece); return }
        // K6: a free answer in a turn where the host sent no text. A reply to a host text never moves the picture this way.
        val turn = personTurn ?: return
        if (!FREE_ANSWER_MOVES || answer.confidence < FREE_MIN || turn.acted || session.texts != turn.texts || piece <= turn.firstPiece) return
        if (screen.step?.index == step) return
        turn.moved = true
        moveTo(screen, step, "classifier")
    }

    override fun itemCreated(clientEventId: String, itemId: String) {
        // An item that a newer scene replaced before its create came back goes at once.
        if (clientEventId == sceneItemCreate) { sceneItemCreate = null; sceneItem = itemId } else session.deleteItem(itemId)
    }

    override fun classifierOff(appRun: Boolean) {
        if (appRun) classifierOffForApp = true
        sceneItem = null; sceneItemText = null; sceneItemCreate = null
        phraseJob?.cancel()
    }

    /** The stage app as a shown screen, or null. */
    private fun stageScreen(): ShownScreen? = model.stageToolId?.let { id -> model.tools.tools.find { it.id == id } }?.let { ShownScreen.of(it) }

    /** The stepped scene on the stage that the classifier knows from its scene item, or null: then each classifier answer is ignored (K2). */
    private fun classifiedScreen(): ShownScreen? =
        if (!session.classifierOn || sceneItemText == null) null else stageScreen()?.takeIf { it.steps.isNotEmpty() }

    /** K2: one scene item for the stepped scene on the stage. A new or changed scene deletes the old item first. With no stepped scene, none. */
    private fun syncSceneItem() {
        if (!session.classifierOn) return
        val record = model.stageToolId?.let { id -> model.tools.tools.find { it.id == id } }
        val text = record?.takeIf { !model.tools.narrationPending(it.id) }?.let(::sceneText)
        // Another scene with the same text still replaces the item, as K2 asks.
        if (text == sceneItemText && (text == null || record?.id == sceneItemTool)) return
        sceneItem?.let(session::deleteItem)
        sceneItem = null; sceneItemText = text; sceneItemTool = record?.id?.takeIf { text != null }
        sceneItemCreate = text?.let(session::createItem)
    }

    /** The scene text of K2: the title, then each step as number, label, and narration sentence, with no line about the present step. */
    private fun sceneText(record: ToolRecord): String? {
        val screen = ShownScreen.of(record) ?: return null
        val narration = SceneNarration.of(appFileOf(record))
        if (!narration.stepped || screen.steps.isEmpty()) return null
        val steps = screen.steps.mapIndexed { index, label -> "${index + 1}. $label" + narration.sentence(index + 1)?.let { ": $it" }.orEmpty() }
        return "На экране сцена «${screen.title}». Шаги сцены:\n" + steps.joinToString("\n")
    }

    // shortcut: every refusal gets the busy text. A spoken request reaches only the full queue, a busy «+» attachment, or a failed
    // screenshot copy, which shows its own error.
    private fun track(id: String, messageId: String?) {
        if (messageId == null) { session.commentary(id, BUSY); return }
        handOffs[id] = messageId
        tracked += messageId
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
            // The card disables its execute button while the agent is busy, so a busy plan in REVIEW names no button.
            plan == null -> onScreen(agent.plan?.takeIf { !agent.busy }?.phase)
            answer == SpokenAnswer.APPROVE -> { agent.execute(plan); if (agent.busy) STARTED else onScreen(agent.plan?.phase) }
            else -> { agent.dismiss(plan); if (agent.plan == null) CANCELLED else onScreen(null) }
        }
        val decided = answer to text
        answered = decided
        // A delegation of this turn comes in the same main-thread pass and takes the text. Otherwise the plan's hand-off gets it.
        model.viewModelScope.launch(Dispatchers.Main) {
            if (answered === decided) planHandOff?.let { session.commentary(it.first, text) }
        }
    }

    // Until report() reads the end of each message, so a silence check between the commit and report() cannot close the talk. Only the
    // ids that report() reads: a repeated delegation id replaces its entry, and its first message would never reach done.
    override fun handOffRunning() = working || handOffs.values.any { it !in done } || typed.keys.any { it !in done }
    override fun appended(eventId: String) { approval?.takeIf { it.proposal == eventId }?.acknowledged = true }

    // shortcut: the window opens at the first output end after the acknowledgment (rule 3), which can be the tail of an earlier
    // line or early after a late transcript delta. Add a transcript-based end if the live check (6.3) shows an early window.
    override fun outputEnded(endMs: Long) {
        // The spoken pair persists at each reply end, and a reply with no sentence end starts the follow call here (decisions 19 and 20).
        personTurn?.let { turn -> turn.pair?.let { follow(turn); model.persistSpokenPair(it) } }
        narrator.replyEnded()
        // A reply end inside a walk is a gap between two sentences, so it opens no approval window (decision 17).
        if (narrator.walking) return
        approval?.takeIf { it.acknowledged && it.window == null }?.let { it.window = ApprovalWindow(it.plan.id, it.plan.revision, endMs) }
    }

    // The end of the mode also stops each narration call that a stored scene waits on (plan "one scene", decision 6).
    override fun ended(status: String, settingsRequired: Boolean) {
        narrator.stop(WalkStop.END)
        closeTurn()
        inputAnswers.clear(); outputPieces.clear(); follows.clear(); changedFromSteps.clear(); changeWalk = null; moving = null
        sceneItem = null; sceneItemText = null; sceneItemCreate = null
        watch?.cancel(); watch = null; capture = null; stageToolId = null
        model.tools.stopNarrations()
        model.tools.narrationEnded = null
        sceneWaits = null
    }

    /** Sends the mini-app progress and the result of each tracked message once (spec 5.5.5 to 5.5.8, 5.7.2), and puts the app of each complete one on the stage. */
    private fun report() {
        val planReady = string(R.string.agent_plan_ready)
        for ((delegation, id) in handOffs) {
            val message = changed(id) ?: continue
            // The person needs a full silence time to see the screen, and a screen that the voice announced at its start sends no text.
            if (message.status == "complete") message.toolId?.let { stageToolId = it; session.countSilence() }
            // Decision 21: a hand-off that searched and gave no app takes the old scene off the stage, and a later completed hand-off takes a
            // waiting plan card off it. The hand-off of the plan itself keeps it.
            if (message.status == "complete" && message.toolId == null && message.searchStatus.isNotEmpty()) stageToolId = null
            if (message.status == "complete" && message.text != planReady) planLeaves()
            val full = message.status !in unfinishedStatuses && fulls.remove(id)
            // The voice said the answer of a screen at its start. A stop or a failure still goes on to the failure text.
            if (id in screens) {
                if (message.status == "generating") continue
                screens -= id
                // A new scene of this screen can walk its steps or say its narration line (plan "one scene", decision 14).
                if (message.status == "complete") { message.toolId?.takeIf { message.toolRevision == 1 }?.let(::sceneCame); continue }
            }
            // The words of a change never come before its screen. A stop or a failure still goes on to the failure text.
            if (id in changes) {
                if (message.status == "generating") continue
                changes -= id
                val hadSteps = changedFromSteps.remove(id)
                if (message.status == "complete") {
                    session.commentary(delegation, spoken(LiveProtocol.MARK, message.text))
                    // C4: a change that turns a scene into a stepped scene walks it from step 1, once the list of tools holds its revision.
                    if (hadSteps == false) message.toolId?.let { changeWalk = it to (message.toolRevision ?: 0); changeWalks() }
                    continue
                }
            }
            // The chat agent sets the plan in the same main-thread pass as this notice, so the plan is the one of this hand-off.
            if (message.status == "complete" && message.text == planReady) {
                planHandOff = model.agent.plan?.let { delegation to it.id }
                continue
            }
            session.commentary(delegation, when {
                message.status == "generating" -> MINI_APP_STARTED
                full && message.status == "error" -> fullFailed(message.errorCode)
                message.status != "complete" -> FAILED + message.error.take(MAX - FAILED.length)
                message.refinementChoices.isNotEmpty() -> onScreen(null, refinement = true)
                message.text == PRESS_MISSING || message.text == PRESS_FAILED -> message.text
                else -> spoken(LiveProtocol.MARK, message.text)
            })
        }
        // A follow screen shows its scene with no words. When the voice only promised an answer, the scene speaks as a new scene (C5).
        for ((id, answered) in follows.entries.toList()) {
            val message = changed(id)?.takeIf { it.status == "complete" && it.toolId != null } ?: continue
            follows -= id
            val toolId = checkNotNull(message.toolId)
            stageToolId = toolId
            session.countSilence()
            if (!answered && message.toolRevision == 1) sceneCame(toolId)
        }
        for ((id, text) in typed) {
            val message = changed(id)?.takeIf { it.status == "complete" } ?: continue
            message.toolId?.let { stageToolId = it }
            // A typed plan has no hand-off, so GPT-Live neither reads nor approves it.
            if (message.text == planReady) planHandOff = null
            // The typed words go before the mark, so the voice says only the reply (Task 15 probed this form).
            session.thinking(spoken("Пользователь написал: ${LiveProtocol.spoken(text, TYPED_MAX)} ${LiveProtocol.MARK}", message.text))
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
            AgentPhase.REVIEW -> voiceProposal ?: onScreen(plan.phase)
            in SCREEN_PHASES -> onScreen(plan.phase)
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

    /** The reason group of a failed full generation, by the code that its message keeps, and the hint (ruling FX-5). */
    private fun fullFailed(code: String) = when {
        code in NO_ANSWER_CODES || code.startsWith("http_") -> FULL_NO_ANSWER
        code in TOO_BIG_CODES -> FULL_TOO_BIG
        else -> FULL_NOT_BUILT
    } + FULL_HINT

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

    private fun onScreen(phase: AgentPhase?, refinement: Boolean = false) = onScreenLine(model.getApplication(), phase, refinement)

    private fun string(id: Int, vararg args: Any): String =
        model.getApplication<Application>().let { if (args.isEmpty()) it.getString(id) else it.getString(id, *args) }

    internal companion object {
        const val MAX = 1200
        /** The typed text of a thinking append. The rest of the limit is for the reply. */
        const val TYPED_MAX = 400
        // Each host line holds no text of Alphy, so it goes with no mark (plan of 2026-11-22, decisions 7 and 8).
        const val NOT_HEARD = "Не удалось расслышать. Повтори, пожалуйста."
        const val BUSY = "Подожди немного: предыдущий ответ ещё готовится."
        const val MINI_APP_STARTED = "Это появится на экране через несколько секунд."
        /** A spoken press named no single control of the shown screen, so nothing ran. */
        const val PRESS_MISSING = "На экране нет такой кнопки."
        /** A spoken press named a control, but no stage ran its tap, so nothing changed. */
        const val PRESS_FAILED = "Нажать не получилось."
        /** The time for the stage to run or refuse a press. A loaded phone can need some frames, and the voice waits only on a failure. */
        const val PRESS_WAIT_MS = 2_000L
        /** The longest time of a step press that waits for the ready stage, and the time between two tries (stage 7, fix list 2, H2). */
        const val STEP_PRESS_MS = 4_000L
        const val RETRY_MS = 100L
        /** An own press names the next change of its control within this time (decision 10). */
        const val EXPECT_MS = 5_000L
        /** Changes of one control by tap less than this apart are one action (decision 10). */
        const val TAP_MERGE_MS = 1_000L
        /** The full generation of a screen that the fast path did not give starts (ruling FX-4). */
        const val FULL_WAIT = "Быстро не получилось. Экран соберётся заново, это займёт около минуты."
        /** The reason groups of a failed full generation, each with [FULL_HINT] after it (ruling FX-5). */
        const val FULL_NO_ANSWER = "Экран не собран: сервис ИИ не ответил."
        const val FULL_TOO_BIG = "Экран не собран: это не помещается на один экран."
        const val FULL_NOT_BUILT = "Экран не собран: построить его не получилось."
        const val FULL_HINT = " Скажи всё одной фразой."
        /** The failure codes of a provider that did not answer. An http_<status> code is a provider that refused the call. */
        val NO_ANSWER_CODES = setOf("provider", "auth", "quota", "rate_limit", "unavailable", "network", "empty_response", "incomplete",
            "pre_headers_io", "pre_headers_timeout", "stream_io", "stream_timeout", "stream_invalid", "widget_generation")
        /** The failure codes of a request or a reply that does not fit a limit. */
        val TOO_BIG_CODES = setOf("widget_size", "widget_response_size", "response_size", "response_length", "tool_limit")
        /** The thresholds of the classifier rules K3 to K6 (plan "one scene"). */
        // «Дальше» came at 0.61 to 0.83 in the live check of stage 5, and no other last piece came above 0.3 (L3).
        const val COMMAND_MIN = 0.6
        const val GO_TO_STEP_MIN = 0.6
        const val QUESTION_MIN = 0.8
        const val WALK_MIN = 0.5
        const val FREE_MIN = 0.6
        /** K3: the end of a phrase with no hand-off, and the wait for the answer of the newest piece at a hand-off. */
        const val PHRASE_END_MS = 1_000L
        /** V2: the latest start of the follow call after the voice began its reply. */
        const val FOLLOW_START_MS = 2_500L
        const val HAND_OFF_WAIT_MS = 300L
        val COMMANDS = setOf("next", "continue", "back", "repeat", "go_to")
        /** The answer of «back» at the first step (K3). */
        const val FIRST_STEP = "Это первый шаг."
        /** The longest subject of the work card (decision 21). */
        const val WORK_SUBJECT_MAX = 48
        /** K6: the one switch of the move by a free answer. */
        @Volatile var FREE_ANSWER_MOVES = true
        /** K8: a failed start with triggers keeps the classifier off for the rest of the app run. */
        @Volatile var classifierOffForApp = false
        private const val SENTENCE_ENDS = ".!?…"
        const val FAILED = "Не получилось: "
        const val PROPOSE = "Предлагаю: "
        const val ASK = " Скажи «да», чтобы выполнить, или «нет», чтобы отменить."
        const val STARTED = "Запускаю."
        const val CANCELLED = "Действие отменено."
        const val DONE = "Готово. "
        const val NOT_DONE = "Не получилось. "
        /** The plan card needs a tap or a system dialog in these phases (rule 9). */
        val SCREEN_PHASES =
            setOf(AgentPhase.NEEDS_PERMISSION, AgentPhase.NEEDS_SELECTION, AgentPhase.UNAVAILABLE, AgentPhase.WAITING_FOR_CAMERA)

        /**
         * The line of a card that waits for a tap, with the label of its button (plan of 2026-11-22, decision 8): a refinement choice,
         * or the plan card in [phase]. Only REVIEW names the execute button. Each other phase, and no plan, names no button.
         */
        fun onScreenLine(context: Context, phase: AgentPhase?, refinement: Boolean = false): String {
            fun press(label: Int) = "Нажми «${context.getString(label)}» на экране."
            return when {
                refinement -> "Выбери на экране, что изменить."
                phase == AgentPhase.NEEDS_PERMISSION -> press(R.string.agent_permission_allow)
                phase == AgentPhase.WAITING_FOR_CAMERA -> press(R.string.agent_continue_camera)
                phase == AgentPhase.NEEDS_SELECTION -> "Выбери вариант на экране."
                phase == AgentPhase.REVIEW -> "Подтверди на экране: нажми «${context.getString(R.string.agent_execute)}»."
                else -> "Подробности на экране."
            }
        }
    }
}
