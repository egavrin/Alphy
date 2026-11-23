package com.alpha.assistant.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPlanCard
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.images.GeneratedImage
import com.alpha.assistant.tools.ArtifactSnapshot
import com.alpha.assistant.tools.CoveredToolSurface
import com.alpha.assistant.tools.DIALOGUE_WIDE_DP
import com.alpha.assistant.tools.LocalNodeMark
import com.alpha.assistant.tools.MiniAppActions
import com.alpha.assistant.tools.MiniAppContent
import com.alpha.assistant.tools.StagePreview
import com.alpha.assistant.tools.STAGE_FIT_MARK
import com.alpha.assistant.tools.StagePreviewContent
import com.alpha.assistant.tools.dialogueSplit
import com.alpha.assistant.tools.VoicePress
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.three.ThreeScenePane
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveVoiceSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import org.json.JSONObject

/**
 * A card of the voice mode that the stage shows: a card that waits for a tap (an error or an unsent request with its retry, or the
 * refinement choices), or a finished answer with a generated image, which the voice cannot speak. The voice speaks each other result, and
 * the stage shows the app of a hand-off, so the stage shows no other card (plan D, Da1 Step 0, Da2 ruling 3).
 */
internal fun showsOnTheStage(message: Message): Boolean = message.status !in unfinishedStatuses &&
    (message.status != "complete" || message.refinementChoices.isNotEmpty() || message.attachments.any { it.mime == GeneratedImage.ATTACHMENT })

/**
 * The fixed text of a failed request on the stage for the group of its error [code], with what the person can say next (plan "one scene",
 * decision 22). The groups of no answer and too big are those of a failed full generation (LiveVoiceBridge.fullFailed).
 */
@StringRes internal fun stageError(code: String): Int = when {
    // First, because auth is also a code of no answer. The card of a key error has the settings button.
    code in com.alpha.assistant.cloud.KEY_ERRORS -> R.string.stage_error_key
    code == "search_no_usable_results" || code == "source_attribution" -> R.string.stage_error_search
    code == "image_generation" -> R.string.stage_error_image
    code in LiveVoiceBridge.NO_ANSWER_CODES || code.startsWith("http_") -> R.string.stage_error_no_answer
    code in LiveVoiceBridge.TOO_BIG_CODES -> R.string.stage_error_too_big
    else -> R.string.stage_error_other
}

/**
 * The stage height around the drawing of an app: the padding, the kind label, the title, the first control, and the gaps (Da2 ruling 1).
 * The label and the title take about 120 dp, and a slider with its label, the tallest first control, about 105 dp.
 * Gate 3 (2026-11-21): at the fold size a template at its caps scrolled 5.8 dp, with a 266 dp drawing that its width limits on a 534 dp
 * stage, and its title, its control label, and two rows of options took about 274 dp.
 */
private val STAGE_TEXT_HEIGHT = 276.dp

/** The canvas height of an app on a stage of [maxHeight]. The stage preview and its cost test use the same height. */
internal fun stageCanvasHeight(maxHeight: Dp, fold: Boolean): Dp = (if (fold) maxHeight - STAGE_TEXT_HEIGHT else maxHeight * 0.65f).coerceIn(120.dp, 560.dp)

/**
 * The preview that the stage draws in place of [tool] (Task 16): an uncommitted one, or a committed one until the stage holds its stored
 * revision, because the stage tool and the tool list change after the commit. While [changing], an uncommitted frame of [tool] does not
 * draw, so the old scene and the frames of its change never mix (plan "one scene", decision 22).
 */
private fun StagePreview.covers(tool: ToolRecord?, changing: Boolean): Boolean =
    if (revision == null) !changing || tool?.id != toolId else tool == null || tool.id != toolId || tool.revision < revision

/** True while an operation of [chat] changes [tool]: the draft of that tool is stored, and the message of the operation still waits for it. */
private fun changing(model: ShellViewModel, chat: Conversation?, tool: ToolRecord?): Boolean = tool != null && chat != null &&
    model.tools.drafts.any { draft -> draft.toolId == tool.id && chat.messages.any { it.toolOperationId == draft.id && it.status == "generating" } }

/** The alpha of the old scene while its change runs (plan "one scene", decision 22). */
private const val CHANGING_ALPHA = 0.45f

/** How long a new part keeps its mark. */
private const val NEW_PART_MS = 1_000L

/**
 * The JSON text of each topmost node of [ui] with no node of [old] in it: a node whose text [old] does not have, or whose children are all
 * such nodes. So a changed text is new, and a new row around an old part is not. A [ui] that is new as a whole marks nothing.
 */
private fun newNodes(old: String, ui: String): Set<String> {
    fun children(node: JSONObject) = node.optJSONArray("children")?.let { list -> List(list.length()) { list.getJSONObject(it) } }.orEmpty()
    val known = HashSet<String>()
    fun index(node: JSONObject) { known += node.toString(); children(node).forEach(::index) }
    index(JSONObject(old))
    val found = HashSet<String>()
    fun new(node: JSONObject): Boolean {
        val inner = children(node)
        val fresh = inner.map(::new)
        val whole = if (inner.isEmpty()) node.toString() !in known else fresh.all { it }
        if (!whole) inner.filterIndexed { index, _ -> fresh[index] }.forEach { found += it.toString() }
        return whole
    }
    new(JSONObject(ui))
    return found
}

/**
 * The new parts of [tool] (decision 22): the nodes of its UI that its revision before did not have, for [NEW_PART_MS] after the stage shows
 * the new revision. The first revision of an app on the stage marks nothing.
 */
@Composable private fun newParts(tool: ToolRecord?): Set<String> {
    // The record of the last composition, so a new revision compares with the newest UI of the revision before it.
    val last = remember(tool?.id) { arrayOf(tool) }
    val parts = remember(tool?.id, tool?.revision) {
        last[0]?.takeIf { tool != null && it.revision != tool.revision }?.let { newNodes(it.ui, checkNotNull(tool).ui) }.orEmpty()
    }
    SideEffect { last[0] = tool }
    var marked by remember(parts) { mutableStateOf(parts) }
    if (parts.isNotEmpty()) LaunchedEffect(parts) { delay(NEW_PART_MS); marked = emptySet() }
    return marked
}

/** The mark of a new part: an accent fill behind it with its state description, and a scroll of the stage that brings the part into view. */
@Composable private fun newPartMark(): Modifier {
    val inView = remember { BringIntoViewRequester() }
    var measured by remember { mutableStateOf(IntSize.Zero) }
    // The gap of the stage around the part comes into view too, so the part and its mark do not touch the strip.
    val gap = with(LocalDensity.current) { 12.dp.toPx() }
    // One frame first, so the part has its place and its size on the stage (the date fields of NativeToolUi.kt do the same).
    LaunchedEffect(inView) { withFrameNanos {}; inView.bringIntoView(Rect(-gap, -gap, measured.width + gap, measured.height + gap)) }
    val label = stringResource(R.string.stage_new_part)
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val shape = MaterialTheme.shapes.medium
    // Draw-only and behind the part, so the mark moves nothing and covers no text. An outline on a text line crossed its first letter.
    return Modifier.bringIntoViewRequester(inView).onSizeChanged { measured = it }.semantics { stateDescription = label }.drawBehind {
        val outset = 4.dp.toPx()
        translate(-outset, -outset) {
            drawOutline(shape.createOutline(Size(size.width + 2 * outset, size.height + 2 * outset), layoutDirection, this), tint)
        }
    }
}

/** True when the stage shows an app or a preview, and not the large character. */
private fun stageShowsApp(model: ShellViewModel): Boolean =
    model.stageToolId != null || model.tools.stagePreviews.current != null || model.liveWorkCard != null

/** The work card of the stage while a hand-off searches or builds a screen with no frame yet (plan "one scene", decision 21). */
@Composable private fun StageWorkCard(text: String) {
    Surface(Modifier.fillMaxWidth().testTag("stage_work_card"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** What the voice mode does now (spec 5.2.4). A mute has no status of its own, because the microphone state of the strip shows it. */
@StringRes private fun liveStatus(model: ShellViewModel): Int? = when {
    model.liveVoice.phase == LivePhase.CONNECTING -> R.string.live_voice_connecting
    model.liveVoice.phase == LivePhase.SPEAKING -> R.string.live_voice_speaking
    // Between two sentences of a walk, the strip keeps the speaking status (plan "one scene", decision 17).
    model.liveWalking && model.liveVoice.phase == LivePhase.LISTENING -> R.string.live_voice_speaking
    model.liveVoiceWorking -> R.string.live_voice_working
    model.liveVoice.phase == LivePhase.MUTED -> null
    else -> R.string.live_voice_listening
}

/**
 * The stage of the voice mode (plan D, Da1): all the space above the strip. It shows a card that waits for a tap and the plan of the
 * chat, then the app of the latest hand-off. Before the first app, it shows only the Alphy character, and the strip shows the status.
 */
@Composable internal fun DialogueStage(model: ShellViewModel, chat: Conversation?, cardActions: MiniAppActions, openTool: (String) -> Unit,
    openAutomation: (String) -> Unit, settings: () -> Unit, modifier: Modifier) {
    var shareText by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf(false) }
    val tool = model.stageToolId?.let { id -> model.tools.tools.find { it.id == id } }
    val previews = model.tools.stagePreviews
    val changing = changing(model, chat, tool)
    val preview = previews.current?.takeIf { it.covers(tool, changing) }
    val parts = newParts(tool)
    // A committed preview seeds the drawing of its stored revision, which the stage now holds.
    val seed = previews.current?.takeIf { tool != null && it.toolId == tool.id && it.revision == tool.revision }
    // A committed preview whose app never reached the stage, such as at the end of the mode, seeds no later stage.
    DisposableEffect(previews) { onDispose { previews.current?.toolId?.let(previews::drawn) } }
    val fold = LocalConfiguration.current.smallestScreenWidthDp >= DIALOGUE_WIDE_DP
    BoxWithConstraints(modifier.fillMaxWidth().testTag("dialogue_stage").pointerInput(model) { reportTouches(model::liveStageTouch) }) {
        // The gutter of an open mini-app (FocusedToolPane).
        val gutter = if (maxWidth >= 600.dp) 28.dp else 20.dp
        // In a short space, such as landscape with the keyboard, the decorative character goes.
        val character = minOf(160.dp, maxHeight / 4).takeIf { it >= 48.dp }
        // The canvas height of an open mini-app (FocusedToolPane), less at the fold size so the first control fits above the strip (Da2 ruling 1).
        val canvasHeight = stageCanvasHeight(maxHeight, fold)
        // Stage 6, F1: on a phone a square picture (the 300 × 300 picture of a template) takes the height that the other parts of the stage
        // leave, in one layout pass: the stage gives its column the height of the stage plus STAGE_FIT_MARK, each part above the app and each
        // padding takes its part of it, and the column of the app (DialogueColumns) gives its drawing what is left. Other apps, also wide
        // pictures, 3D scenes, and the fold, keep canvasHeight and an unbounded column.
        val shown = preview?.frame?.spec ?: tool?.let { remember(it.spec, it.ui, it.state) { ToolSpec.availablePersisted(it) } }
        val drawing = shown?.let { remember(it.ui) { dialogueSplit(it.ui)?.drawing == true } } == true
        val fit = shown != null && drawing && !fold && !shown.threeScene && shown.surfaces.values.singleOrNull()?.let { it.width == it.height } == true
        val stageHeight = constraints.maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .layout { measurable, outer ->
                val placeable = measurable.measure(if (fit) outer.copy(maxHeight = stageHeight + STAGE_FIT_MARK) else outer)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }.heightIn(min = maxHeight)
            .padding(horizontal = gutter, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp, if (stageShowsApp(model)) Alignment.Top else Alignment.CenterVertically)) {
            if (chat != null) {
                chat.messages.find { it.id == model.liveVoiceCardId }?.takeIf(::showsOnTheStage)?.let { message ->
                    ChatMessageItem(model, chat, message, cardActions, openTool, openAutomation, showLivePreviews = true,
                        pairedToolId = null, pairedDraftId = null, share = { shareText = it }, settings = settings,
                        linkError = { linkError = true }, errorText = stringResource(stageError(message.errorCode)))
                }
                // The plan of the chat, as the chat list shows it. A later hand-off keeps it while it waits for a tap.
                // A waiting plan card of an earlier subject leaves the stage, and the chat keeps it (plan "one scene", decision 21).
                model.agent.plan?.takeIf { it.conversationId == chat.id && it.id != model.liveHiddenPlan }?.let { AgentPlanCard(model.agent, it) }
            }
            val work = model.liveWorkCard
            when {
                preview != null -> StagePreviewContent(preview.frame, canvasHeight)
                work != null -> StageWorkCard(work)
                // One layer that stays while the change runs, so the app keeps its frames and timers (ToolEngine.acceptDraft).
                tool != null -> Column(Modifier.graphicsLayer { alpha = if (changing) CHANGING_ALPHA else 1f }
                    .then(if (changing) Modifier.testTag("stage_dimmed") else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    val mark: (@Composable (JSONObject) -> Modifier)? = remember(parts) {
                        if (parts.isEmpty()) null else { node -> if (node.toString() in parts) newPartMark() else Modifier }
                    }
                    CompositionLocalProvider(LocalNodeMark provides mark) {
                        StageApp(model.tools, tool, cardActions, canvasHeight, model.livePresses, seed, changing)
                    }
                }
                else -> character?.let { AlphyCharacter(model.liveVoice, it, Modifier.testTag("live_voice_character")) }
            }
        }
    }
    shareText?.let { ShareTextToNotes(it) { shareText = null } }
    if (linkError) ChatLinkErrorDialog { linkError = false }
}

/**
 * Reports each touch of the stage (plan "one scene", Task 6). A touch is down while any pointer is down. A cancel ends it, and so does
 * the end of the stage. It only watches, in the first pass, and takes no event, so each control, scroll, and drawing keeps its input.
 */
private suspend fun PointerInputScope.reportTouches(report: (Boolean) -> Unit) {
    awaitPointerEventScope {
        var down = false
        try {
            while (true) {
                val pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                if (pressed != down) { down = pressed; report(pressed) }
            }
        } finally { if (down) report(false) }
    }
}

/**
 * The app of the stage, drawn as an open mini-app draws it (FocusedToolPane): a 3D scene, a drawing with its host, or the app. The app draws
 * its title after its kind label. A 3D scene and a closed app draw no app, so the stage gives them the title. While [changing], its controls
 * are off.
 */
@Composable private fun StageApp(controller: ToolController, tool: ToolRecord, actions: MiniAppActions, canvasHeight: Dp,
    presses: Flow<VoicePress>?, seed: StagePreview?, changing: Boolean) {
    val spec = remember(tool.spec, tool.ui, tool.state) { ToolSpec.availablePersisted(tool) }
    val voicePresses = remember(presses, tool.id) { presses?.filter { it.toolId == tool.id } }
    if (spec == null || spec.threeScene) Text(spec?.title ?: ToolSpec.storedTitle(tool.spec), Modifier.fillMaxWidth().semantics { heading() },
        style = MaterialTheme.typography.headlineSmall)
    if (spec == null) { Text(stringResource(R.string.tool_closed_format)); return }
    val enabled = !changing && !controller.busy && !actions.covers(tool.id)
    val host = if (!spec.threeScene && spec.surfaces.isNotEmpty()) remember(controller, tool.id, tool.revision) { controller.artifactHost(tool) }
        else null
    // An app with no drawing of its own takes no seed, so its committed preview ends at once.
    if (seed != null && host == null) LaunchedEffect(seed) { controller.stagePreviews.drawn(tool.id) }
    val drawingSeed = remember(seed) { seed?.frame?.drawing?.let { ArtifactSnapshot(tool, 0, JSONObject(it)) } }
    var snapshot by remember(host) { mutableStateOf<ArtifactSnapshot?>(null) }
    val content: @Composable () -> Unit = {
        MiniAppContent(controller, tool, tool, spec.title, enabled, graphicsHost = host, graphicsSnapshot = snapshot,
            liveAllowed = !actions.covers(tool.id), allowVideo = true, dialogueTitle = spec.title, voicePresses = voicePresses)
    }
    when {
        spec.threeScene -> ThreeScenePane(controller, tool, spec, canvasHeight, null, enabled)
        // The drawing keeps its frames and timers while a change of its app runs: the save of the change takes the newest state (ToolEngine.acceptDraft).
        host != null -> CoveredToolSurface(tool, spec, true, canvasHeight, host, actions, drawingSeed = drawingSeed, content = content,
            input = !changing) {
            snapshot = it
            // Only a live snapshot of the committed revision ends the seed: a late one of the old revision would leave an empty frame.
            if (seed != null && it.tool.revision == seed.revision) controller.stagePreviews.drawn(tool.id)
        }
        else -> content()
    }
}

/** The top of a text that fits its place, and the end of a longer text, so the newest words stay in view. */
private val EndInView = Alignment.Vertical { size, space -> minOf(0, space - size) }

/** The words that the voice speaks now (plan D, Da3). A screen reader skips them, because the voice speaks them. */
@Composable private fun SpeechLine(voice: LiveVoiceSession) {
    if (voice.speech.isEmpty()) return
    val style = MaterialTheme.typography.bodyMedium
    // A fixed place of two lines: the stage does not move with each word, and one line showed one word after each wrap.
    Text(voice.speech.trim(), Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp)
        .height(with(LocalDensity.current) { style.lineHeight.toDp() } * 2).clipToBounds()
        .wrapContentHeight(EndInView, unbounded = true).testTag("live_voice_speech").semantics { hideFromAccessibility() },
        color = MaterialTheme.colorScheme.onSurface, style = style)
}

/**
 * The strip of the voice mode (plan D, Da1): the status with the microphone state, the words of the voice (Da3), then the composer row
 * with «+», the field, send, mute, and end (spec 5.7.1). [compact] makes the composer one row for the assistant sheet (spec 5.11.3).
 */
@Composable internal fun DialogueStrip(model: ShellViewModel, compact: Boolean, onEnd: () -> Unit, openContext: () -> Unit,
    settings: () -> Unit, onContextAnchorChanged: (ContextMenuAnchor?) -> Unit) {
    val voice = model.liveVoice
    val status = liveStatus(model)
    Column(Modifier.fillMaxWidth().testTag("dialogue_strip")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // The row keeps the height of the small character, so the strip does not move when the first app comes.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp).heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // An empty stage shows the large character, so the strip shows the voice indicator only beside an app (Da2 ruling 2).
            if (stageShowsApp(model)) AlphyCharacter(voice, 28.dp, Modifier.testTag("live_voice_character"))
            // Each text is a polite live region, so a screen reader hears a new status and a new microphone state (spec 5.2.4).
            Text(status?.let { stringResource(it) }.orEmpty(), Modifier.weight(1f).then(PoliteLiveRegion),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            // While the mode connects, the phase is CONNECTING even with the microphone button off.
            Text(stringResource(if (voice.userMuted || voice.phase == LivePhase.MUTED) R.string.live_voice_mic_off else R.string.live_voice_mic_on),
                Modifier.then(PoliteLiveRegion), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        SpeechLine(voice)
        Composer(model, openContext, compact = compact, onContextAnchorChanged = onContextAnchorChanged, settings = settings) { hasInput ->
            // Implementation choice: typed text goes as a normal message with its own send button (spec 5.7.1).
            if (hasInput) {
                RoundAction(Glyph.Arrow, stringResource(R.string.composer_send), model::sendLiveTyped, primary = true,
                    enabled = !model.attachmentBusy)
                Spacer(Modifier.width(5.dp))
            }
            RoundAction(if (voice.userMuted) Glyph.MicOff else Glyph.Mic,
                stringResource(if (voice.userMuted) R.string.live_voice_unmute else R.string.live_voice_mute),
                { voice.mute(!voice.userMuted) })
            Spacer(Modifier.width(5.dp))
            RoundAction(Glyph.Close, stringResource(R.string.live_voice_end), onEnd)
        }
    }
}
