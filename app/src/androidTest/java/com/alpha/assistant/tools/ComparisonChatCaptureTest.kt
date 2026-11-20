package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.images.ImageProvider
import com.alpha.assistant.images.ImageSettings
import com.alpha.assistant.images.imageProviders
import com.alpha.assistant.tools.agent.GenerationSettings
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.chat.MAX_CHAT_INPUT
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudChatProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.ui.removeShellIndexes
import com.alpha.assistant.ui.seedOpenChat
import com.alpha.assistant.weather.WeatherLocator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import com.alpha.assistant.ui.isolatedLegacyKeys
import com.alpha.assistant.ui.isolatedModels
import com.alpha.assistant.ui.ISOLATED_IMAGES
import com.alpha.assistant.ui.ISOLATED_VOICE

private val terminalChatStatuses = setOf("complete", "error", "cancelled", "interrupted")

/** Read on the main thread before any capture cleanup can change the app state. */
internal fun captureChatState(vm: ShellViewModel): JSONObject {
    val conversation = vm.snapshot.selected
    val assistant = conversation?.messages?.lastOrNull()?.takeIf { it.role == "assistant" }
    val busy = vm.tools.busy
    val active = vm.activeConversationId
    return JSONObject().put("at_ns", SystemClock.elapsedRealtimeNanos())
        .put("conversation", conversation?.id ?: JSONObject.NULL)
        .put("active_conversation", active ?: JSONObject.NULL)
        .put("assistant_status", assistant?.status ?: JSONObject.NULL)
        .put("operation_id", assistant?.toolOperationId ?: JSONObject.NULL)
        .put("tool_busy", busy)
        .put("idle", !busy && active == null)
        .put("terminal", assistant?.status in terminalChatStatuses)
}

/** Captures real chat checkpoints. A saved artifact or accessibility label does not establish runtime usability. */
class ComparisonChatCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    private fun firstCanvasLabel(node: JSONObject): String? {
        if (node.optString("type") == "canvas") return node.getString("text")
        val children = node.optJSONArray("children") ?: return null
        repeat(children.length()) { firstCanvasLabel(children.getJSONObject(it))?.let { label -> return label } }
        return null
    }

    @Test fun replayGeneratedCanvasGameFromChat() {
        val origin = InstrumentationRegistry.getArguments().getString("canvasReplayRun")
        assumeNotNull(origin)
        require(checkNotNull(origin).matches(Regex("[a-f0-9]{32}")))
        val files = checkNotNull(app.getExternalFilesDir(null))
        val captured = File(files, "comparison-capture/$origin/artifact.json").readText()
        val artifact = JSONObject(captured)
        val id = artifact.getString("artifact_id")
        val revision = artifact.getInt("artifact_revision")
        val spec = ToolSpec.parsePersisted(artifact.getJSONObject("spec").toString())
        check(spec.presentation == "immersive")
        val evidence = JSONObject().put("origin", origin).put("artifact_sha256", sha256(captured)).put("status", "failed")
        val directory = File(files, "canvas-replay/$origin").apply { check(mkdirs()) }
        val fixture = ShellFixture("canvas-replay")
        val robot = ShellRobot(fixture.name)
        try {
            val chat = "canvas-chat"
            fixture.store.saveConversation(Conversation(chat, "Generated game", 1, 2, messages = listOf(
                Message("request", "user", "Generate an immersive Arkanoid game.", 1, "sent"),
                Message("answer", "assistant", spec.description, 2, "complete", replyTo = "request", toolId = id, toolRevision = revision),
            )))
            fixture.store.seedOpenChat(chat)
            ToolStore(app, "${fixture.store.databaseName}-tools.db").use { store ->
                store.saveTool(ToolRecord(id, revision, 1, spec.json, spec.initialState, spec.ui.toString(), chat, true), revision = true)
            }
            fixture.install(saveKey = false)
            fixture.launch().use {
                val vm = checkNotNull(fixture.model)
                val checks = CanvasLiveChecks(robot, evidence) { name ->
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                }
                val card = robot.text(R.string.tool_open)
                fun score(state: String) = JSONObject(state).getDouble("score")
                fun state() = onMain { vm.tools.tools.single { tool -> tool.id == id }.state }
                robot.click(card)
                checks.game(spec.title, score(spec.initialState), { score(state()) }) {
                    onMain { vm.openSurface(null) }
                    robot.waitGone(robot.text(R.string.tool_game_controls_open))
                    robot.click(card)
                }
                checks.captureCompact(spec.title) {
                    robot.waitFor(robot.text(R.string.tool_game_controls_open))
                    robot.waitUntil {
                        val viewport = JSONObject(state())
                        viewport.getDouble("vh") / viewport.getDouble("vw") > 1.8
                    }
                }
                evidence.put("status", "passed")
            }
        } catch (failure: Throwable) {
            evidence.put("failure", failure.message)
            throw failure
        } finally {
            File(directory, "report.json").writeText(evidence.toString(2))
            fixture.model?.let { vm -> runBlocking { vm.tools.closeAndJoin() } }
            fixture.remove()
        }
    }

    @Test
    fun captureFrozenSlot() {
        val encoded = InstrumentationRegistry.getArguments().getString("comparisonEnvelope")
        assumeNotNull(encoded)
        capture(JSONObject(String(Base64.decode(checkNotNull(encoded), Base64.URL_SAFE), Charsets.UTF_8)))
    }

    /** Failure case: one process measured one cold request, so no number showed a warm request (ruling OD-8). */
    @Test
    fun aRepeatedCaptureReportsEachRequestApart() {
        val prompt = "Build a local counter that adds one."
        val counter = JSONObject("""{"title":"Counter","description":"Adds one","capabilities":[],"stateSchema":{"count":"number"},
            "initialState":{"count":0},"ui":{"type":"column","children":[{"type":"metric","text":"Count","bind":"count"},
            {"type":"button","text":"Add","action":"add"}]},
            "source":"const s=input.state,e=input.event;if(e.type==='action'&&e.actionId==='add')s.count++;return {state:s};"}""")
        val settings = GenerationSettings(app)
        val switchAtCalls = CopyOnWriteArrayList<Boolean>()
        val scripted = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                switchAtCalls += settings.planStep(AiProvider.OPENAI)
                val reply = if (request.chatDecision) JSONObject().put("kind", "create").put("text", "Ready.")
                    .put("brief", "Create a counter that adds one").put("search", "none").toString()
                else request.agentReply(counter.toString())
                // The live provider reports each accepted decision. The capture reads the decision kind from that report.
                if (request.chatDecision) request.validateOperation?.invoke(ChatToolReply.decode(reply))
                emit(CloudEvent.Text(reply))
                emit(CloudEvent.RoundComplete(reply, false))
            }
        }
        val runId = UUID.randomUUID().toString().replace("-", "")
        val input = JSONObject().put("run_id", runId).put("slot_id", "fixture:repeat:candidate:1").put("request", prompt)
            .put("review_seconds", 5).put("chat_seconds", 60).put("plan_step", false).put("repeat", 2)
            .put("request_sha256", sha256(prompt)).put("revision", "0".repeat(40))
        for (field in listOf("plan_sha256", "execution_sha256", "source_sha256", "build_sha256")) input.put(field, "0".repeat(64))
        val directory = File(checkNotNull(app.getExternalFilesDir(null)), "comparison-capture/$runId")
        try {
            // The capture sets the envelope value and restores the earlier one, so the earlier one is the opposite here.
            withPlanStep(app, AiProvider.OPENAI, true) {
                try {
                    capture(input, scripted)
                } catch (failure: IllegalStateException) {
                    val report = File(directory, "report.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
                    throw AssertionError(listOf("failure_stage", "failure_type", "failure_frame").joinToString { report?.opt(it).toString() }, failure)
                }
                assertTrue(switchAtCalls.isNotEmpty() && switchAtCalls.none { it })
                assertTrue(settings.planStep(AiProvider.OPENAI))
                val first = JSONObject(File(directory, "report.json").readText())
                assertEquals(2, first.getInt("repeat"))
                assertFalse(first.getBoolean("plan_step"))
                val second = first.getJSONObject("second")
                for (report in listOf(first, second)) {
                    assertEquals("artifact-committed", report.getString("chat_outcome"))
                    assertEquals("create", report.getString("chat_decision"))
                    assertEquals(JSONArray(listOf("CHAT_DECISION", "AGENT", "AGENT")).toString(), report.getJSONArray("provider_invocations").toString())
                    val measurements = report.getJSONObject("measurements")
                    for (field in listOf("first_ui_ms", "completed_ui_ms", "terminal_chat_ms")) assertFalse(field, measurements.isNull(field))
                    val trace = report.getJSONObject("operation_trace")
                    assertEquals(3, measurements.getInt("provider_http_calls"))
                    assertEquals(3, trace.getJSONArray("http_dispatches").length())
                    val events = trace.getJSONArray("generation").let { array -> List(array.length()) { array.getJSONObject(it) } }
                    val phases = events.filter { it.getString("kind") == "phase" }
                    assertEquals(listOf("draft_start", "draft_end", "plan_skipped:plan_step", "capability_guidance_start",
                        "capability_guidance_end", "agent_call_start"), phases.map { it.getString("name") })
                    assertEquals(setOf("operation_id", "attempt", "at_ns", "kind", "name", "detail", "ms"), phases.first().keys().asSequence().toSet())
                    assertEquals(setOf(report.getString("operation_id")), events.map { it.getString("operation_id") }.toSet())
                    // The Open action of this request's own app, not a card of the earlier chat, ends its time.
                    val finished = events.single { it.getString("kind") == "finished" }.getLong("at_ns")
                    assertTrue(report.getLong("send_action_elapsed_ns") + (measurements.getLong("completed_ui_ms") + 1) * 1_000_000 > finished)
                }
                assertNotEquals(first.getString("operation_id"), second.getString("operation_id"))
                assertNotEquals(first.getString("fresh_conversation"), second.getString("fresh_conversation"))
                val firstEnd = first.getJSONObject("operation_trace").getJSONArray("generation").let { it.getJSONObject(it.length() - 1).getLong("at_ns") }
                assertTrue(firstEnd < second.getLong("send_action_elapsed_ns"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** [scripted] replaces the account and the live provider, so a device test can run the capture with no model call. */
    private fun capture(input: JSONObject, scripted: ChatProvider? = null) {
        val runId = input.getString("run_id")
        val slotId = input.getString("slot_id")
        val request = input.getString("request")
        val reviewSeconds = input.getInt("review_seconds")
        val caseId = slotId.substringBeforeLast(":candidate:")
        val canvasWorkflow = caseId in setOf("canvas:arkanoid", "canvas:wave")
        val savedEdit = input.optBoolean("saved_edit")
        val verifyWorkflow = input.optBoolean("verify_workflow")
        // Opt-in pictures and screenshot review for a showcase run. The cleanup restores both settings.
        val pictures = input.optBoolean("images")
        val screenshotReview = input.optBoolean("screenshot_review")
        // The switch of the plan step for the provider of the account. withPlanStep restores the earlier value before the cleanup closes the model.
        val planStep = input.optBoolean("plan_step")
        // 2 sends the request once more in a new conversation of the same process, after the first one is done.
        val repeat = input.optInt("repeat", 1)
        require(!savedEdit || verifyWorkflow && caseId in setOf("extra:reference-points-ledger", "extra:russian-grid-game"))
        val verifyTip = input.optBoolean("verify_tip") || verifyWorkflow && caseId == "bench:tip"
        require(repeat == 1 || repeat == 2 && !verifyWorkflow && !verifyTip)
        require(runId.matches(Regex("[a-f0-9]{32}")) && slotId.matches(Regex("[A-Za-z0-9:_-]{1,168}:candidate:[1-3]")))
        require(request.isNotBlank() && request.trim() == request && request.length <= MAX_CHAT_INPUT && '\u0000' !in request)
        require(reviewSeconds in 5..180)
        val chatSeconds = input.optInt("chat_seconds", 240)
        require(chatSeconds in 60..900)
        require(!verifyTip || caseId == "bench:tip")
        require(!verifyWorkflow || caseId == "bench:tip" || canvasWorkflow || caseId in Plan5WorkflowChecks.cases)
        val report = JSONObject().put("run_id", runId).put("slot_id", slotId)
        for (field in listOf("plan_sha256", "request_sha256", "execution_sha256", "revision", "source_sha256", "build_sha256")) {
            val value = input.getString(field)
            require(value.matches(Regex(if (field == "revision") "[a-f0-9]{40}" else "[a-f0-9]{64}")))
            report.put(field, value)
        }
        require(sha256(request) == report.getString("request_sha256"))
        val directory = File(checkNotNull(app.getExternalFilesDir(null)), "comparison-capture/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Expected a new evidence directory." }
        val reportFile = AtomicFile(File(directory, "report.json"))
        fun save(phase: String) {
            report.put("phase", phase)
            val stream = reportFile.startWrite()
            try {
                stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
                reportFile.finishWrite(stream)
            } catch (failure: Exception) {
                reportFile.failWrite(stream)
                throw failure
            }
        }
        report.put("device", JSONObject().put("model", Build.MODEL).put("os", Build.VERSION.RELEASE)
            .put("fingerprint", Build.FINGERPRINT))
        report.put("measurements", JSONObject().put("first_ui_ms", JSONObject.NULL)
            .put("completed_ui_ms", JSONObject.NULL).put("provider_http_calls", JSONObject.NULL)
            .put("correction_lineage", JSONObject.NULL).put("runtime_usability", JSONObject.NULL))
        report.put("repeat", repeat)
        // The evidence of the request that runs now is the last one.
        val requests = CopyOnWriteArrayList(listOf(RequestEvidence()))
        val providerHttpCalls = AtomicInteger()
        val name = "comparison-$runId"
        val database = "$name.db"
        val databases = listOf(database, "$database-tools.db", "$database-tools.db-automations.db",
            "$database-courses.db", "$database-memory.db")
        val store = ShellStore(app, database)
        val keys = SecureKeyStore(app, name, name)
        val imageSettings = ImageSettings(app, keys, ISOLATED_IMAGES)
        val marker = "comparison-fixture-marker"
        var activity: ActivityScenario<CloudTestActivity>? = null
        var model: ShellViewModel? = null
        var ownsStore = false
        var failureAt = "fresh-state"
        var terminal = "failed"
        try {
            check(databases.none { app.getDatabasePath(it).exists() } && keys.read().getOrThrow() == null)
            ownsStore = true
            val initial = store.load()
            check(initial.conversations.isEmpty() && initial.selected == null && initial.newDraft.text.isEmpty())
            report.put("fresh_store", database)
            failureAt = "credential"
            val account = if (scripted != null) AiAccount(AiProvider.OPENAI, "scripted")
                else OneUseCredential.receive(runId) { save("credentialReady") }
            val chatModel = account.liveChatModel()
            report.put("provider", account.provider.id).put("model", chatModel)
            val settings = GenerationSettings(app)
            settings.screenshotReview = screenshotReview
            withPlanStep(app, account.provider, planStep) {
                report.put("pictures", pictures).put("screenshot_review", screenshotReview).put("plan_step", planStep)
                val provider = object : ChatProvider {
                    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                        check(key == marker)
                        val stage = request.generationStage?.name ?: if (request.chatDecision) "CHAT_DECISION" else "CHAT"
                        val evidence = requests.last()
                        evidence.invocations.add(stage)
                        session.decisionRejectionObserver = { event ->
                            evidence.decisionRejections += JSONObject().put("stage",event.stage).put("attempt",event.attempt)
                                .put("rule",event.shape.rule).put("path",event.shape.path)
                                .put("observed_type",event.shape.observedType).put("correcting",event.correcting)
                                .put("at_ns",SystemClock.elapsedRealtimeNanos())
                                .apply {
                                    event.shape.decisionFields?.let {
                                        put("missing_fields", JSONArray(it.missing)).put("extra_field_count", it.extraCount)
                                            .put("kind_class", it.kind.name.lowercase(java.util.Locale.ROOT)).put("wrapped_decision", it.wrappedDecision)
                                    }
                                }
                        }
                        fun dispatched() {
                            providerHttpCalls.incrementAndGet()
                            evidence.httpDispatches += JSONObject().put("stage", stage)
                                .put("at_ns", SystemClock.elapsedRealtimeNanos())
                        }
                        // A scripted model makes no HTTP call, so each of its replies counts as one dispatch.
                        val live = if (scripted != null) scripted.also { dispatched() }
                            else CloudChatProvider({ account }, Endpoints.PRODUCTION, chatModel = { chatModel }, onHttpCall = ::dispatched) {}
                        val tracedRequest = if (request.chatDecision) request.copy(validateOperation = { decision ->
                            request.validateOperation?.invoke(decision)
                            evidence.acceptedOperations += JSONObject().put("kind", decision.kind).put("search", decision.search)
                                .put("at_ns", SystemClock.elapsedRealtimeNanos())
                        }) else request
                        live.respond(account.key, tracedRequest, session, emit)
                    }
                }
                keys.save(marker, account.provider).getOrThrow()
                if (pictures) {
                    // Pictures use the key of the account, so its provider must generate images (spec 11).
                    failureAt = "images"
                    imageSettings.setEnabled(true).getOrThrow()
                }
                // The shell holds only the marker. The live image request gets the key of the account, as the chat request does.
                val pictureProviders = imageProviders(Endpoints.PRODUCTION).mapValues { (_, live) ->
                    ImageProvider { key, model, intent -> check(key == marker); live.generate(account.key, model, intent) }
                }
                failureAt = "launch"
                val toolDatabase = "$database-tools.db"
                val savedId = if (savedEdit) {
                    val asset = if (caseId == "extra:reference-points-ledger") "saved-edit-ledger" else "saved-edit-grid"
                    val frozen = instrumentation.context.assets.open("behavior-replay/$asset.json").bufferedReader().use { it.readText() }
                    report.put("frozen_artifact_sha256", sha256(frozen))
                    val artifact = JSONObject(frozen)
                    val spec = ToolSpec.parsePersisted(artifact.getJSONObject("spec").toString())
                    val id = artifact.getString("artifact_id")
                    ToolStore(app, toolDatabase).use { tools ->
                        tools.saveTool(ToolRecord(id, artifact.getInt("artifact_revision"), 1, spec.json,
                            spec.initialState, spec.ui.toString(), null, true), revision = true)
                    }
                    id
                } else null
                val noLocation = object : WeatherLocator {
                    override fun hasPermission() = false
                    override suspend fun locate(): Nothing = error("No device location in a comparison fixture.")
                }
                CloudTestActivity.factory = { application ->
                    ShellViewModel(application, provider, store, keys, weatherLocator = noLocation, legacy = isolatedLegacyKeys(application), models = isolatedModels(application), imageStore = ISOLATED_IMAGES, voiceStore = ISOLATED_VOICE, imageProviders = pictureProviders).also { model = it }
                }
                val scenario = ActivityScenario.launch(CloudTestActivity::class.java)
                activity = scenario
                val vm = onMain { checkNotNull(model) }
                await(15_000) { onMain { !vm.loading && !vm.tools.busy } }
                check(onMain { !vm.loadFailed && vm.snapshot.conversations.isEmpty() &&
                    vm.tools.tools.map { it.id } == listOfNotNull(savedId) && vm.tools.drafts.isEmpty() })
                onMain { vm.tools.generationTraceObserver = { requests.last().generation += it } }
                val robot = ShellRobot(name)
                fun visible(id: Int): AccessibilityNodeInfo? {
                    if (robot.diagnostics.freshRoot()?.packageName?.toString() != app.packageName) return null
                    return robot.find(robot.text(id))?.takeIf { it.isVisibleToUser }
                }
                fun screenshot(name: String): Boolean {
                    var focused = false
                    scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                    if (!focused || robot.diagnostics.freshRoot()?.packageName?.toString() != app.packageName) return false
                    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
                    try {
                        return File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
                if (savedId != null) {
                    failureAt = "workflow"
                    fun savedTool() = onMain { vm.tools.tools.single { it.id == savedId } }
                    val title = ToolSpec.parsePersisted(savedTool().spec).title
                    onMain { vm.openSurface(savedId) }
                    robot.waitFor(robot.text(R.string.tool_refine))
                    val proof = JSONObject().put("status", "failed")
                    report.put("workflow", proof)
                    fun reopen() {
                        Plan5WorkflowChecks.reopenSaved(robot, title)
                    }
                    var edited = false
                    val refine: (String) -> Unit = {
                        check(!edited && it == request && providerHttpCalls.get() == 0)
                        report.put("send_action_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                        submitSavedEdit(vm, robot, ::savedTool, it, proof, directory)
                        check(providerHttpCalls.get() > 0)
                        edited = true
                    }
                    val checks = Plan5WorkflowChecks(robot, proof, ::savedTool, ::screenshot, toolDatabase)
                    if (caseId == "extra:reference-points-ledger") checks.ledger(::reopen, refine)
                    else checks.grid(::reopen, refineVictory = refine)
                    check(edited)
                    proof.put("status", "passed")
                    report.put("chat_outcome", "artifact-committed").put("artifact_id", savedId)
                        .put("artifact_revision", savedTool().revision)
                    terminal = "complete"
                    return
                }
                /**
                 * Sends the request from the composer of the new chat and measures it into [into]: the report, or its object
                 * "second". [stage] prefixes the failure stages and the screenshots. Null when the chat did not settle in time.
                 */
                fun measuredRequest(into: JSONObject, stage: String): Message? {
                    failureAt = stage + "send"
                    robot.edit(robot.text(R.string.composer_label), request)
                    val draft = onMain {
                        check(!vm.tools.busy && vm.activeConversationId == null)
                        vm.snapshot.draft
                    }
                    check(draft.text == request && draft.attachments.isEmpty())
                    into.put("fresh_draft", draft.id)
                    val send = checkNotNull(visible(R.string.composer_send))
                    into.put("send_action_uptime_ms", SystemClock.uptimeMillis())
                    into.put("send_action_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                    check(click(send)) { "The composer Send action did not run." }
                    save(stage + "sent")
                    failureAt = stage + "chat-terminal"
                    var previewObserved = false
                    // The app card's Open action marks the first usable app. It is read before any test step after the chat settles.
                    var openVisibleAt: Long? = null
                    val settled = waitFor(chatSeconds * 1_000L) {
                        if (!previewObserved && visible(R.string.tool_preview_provisional) != null) {
                            previewObserved = true
                            into.put("provisional_visible_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                            into.put("provisional_label_observed", true)
                            into.put("provisional_screen", screenshot(stage + "provisional"))
                        }
                        if (openVisibleAt == null && visible(R.string.tool_open) != null) openVisibleAt = SystemClock.elapsedRealtimeNanos()
                        onMain {
                            val assistant = vm.snapshot.selected?.messages?.lastOrNull()?.takeIf { it.role == "assistant" }
                            !vm.tools.busy && vm.activeConversationId == null && assistant?.status in terminalChatStatuses
                        }
                    }
                    val evidence = requests.last()
                    if (!settled) {
                        val deadline = onMain {
                            captureChatState(vm).apply {
                                val at = getLong("at_ns")
                                val last = evidence.generation.lastOrNull { it.atNanos <= at }
                                put("generation_last", last?.let(::traceEvent) ?: JSONObject.NULL)
                                put("provider_http_calls", evidence.httpDispatches.size)
                            }
                        }
                        into.put("capture_deadline", deadline)
                        if (!deadline.getBoolean("terminal") || !deadline.getBoolean("idle")) {
                            terminal = "unresolved"
                            report.put("failure_stage", failureAt)
                            report.put("deadline_screen", screenshot(stage + "deadline"))
                            return null
                        }
                    }
                    val chat = onMain { checkNotNull(vm.snapshot.selected) }
                    val user = chat.messages.first()
                    val assistant = chat.messages.last()
                    check(user.role == "user" && assistant.replyTo == user.id && sha256(user.text) == input.getString("request_sha256"))
                    into.put("fresh_conversation", chat.id).put("assistant_message", assistant.id)
                        .put("assistant_status", assistant.status).put("operation_id", assistant.toolOperationId ?: JSONObject.NULL)
                        .put("provisional_label_observed", previewObserved)
                    val decision = evidence.acceptedOperations.lastOrNull()?.optString("kind")
                    decision?.let { into.put("chat_decision", it) }
                    into.put("assistant_text", assistant.text)
                    val sentAt = into.getLong("send_action_elapsed_ns")
                    into.getJSONObject("measurements").put("terminal_chat_ms",
                        (SystemClock.elapsedRealtimeNanos() - sentAt) / 1_000_000)
                    if (openVisibleAt == null && assistant.toolId != null) waitFor(5_000) {
                        (visible(R.string.tool_open) != null).also { if (it) openVisibleAt = SystemClock.elapsedRealtimeNanos() }
                    }
                    val provisionalAt = into.optLong("provisional_visible_elapsed_ns", 0L).takeIf { it > 0 }
                    listOfNotNull(provisionalAt, openVisibleAt).minOrNull()?.let {
                        into.getJSONObject("measurements").put("first_ui_ms", (it - sentAt) / 1_000_000)
                    }
                    openVisibleAt?.let { into.getJSONObject("measurements").put("completed_ui_ms", (it - sentAt) / 1_000_000) }
                    if (decision == "device_plan") {
                        onMain { vm.agent.plan?.takeIf { it.conversationId == chat.id } }?.let { plan ->
                            into.put("phone_plan", JSONObject().put("phase", plan.phase.name)
                                .put("steps", JSONArray(plan.steps.map { it.command.json() }))
                                .put("results", JSONArray(plan.results)))
                        }
                    }
                    into.put("chat_outcome", when {
                        assistant.status != "complete" -> "request-failed"
                        assistant.toolId != null && assistant.toolRevision != null -> "artifact-committed"
                        else -> "complete-without-artifact"
                    })
                    if (assistant.status != "complete") {
                        into.put("failure_code", safeGenerationCode(assistant.errorCode))
                        if (canvasWorkflow) into.put("scene_diagnostic", assistant.error?.take(400))
                    }
                    return assistant
                }
                val assistant = measuredRequest(report, "") ?: return
                val artifact = assistant.toolId
                if (artifact != null) {
                    report.put("artifact_id", artifact).put("artifact_revision", assistant.toolRevision)
                    fun saved() = onMain { vm.tools.tools.singleOrNull { it.id == artifact } }
                    await(5_000) { saved() != null }
                    val record = checkNotNull(saved())
                    check(record.revision == assistant.toolRevision)
                    val definition = JSONObject().put("artifact_id", artifact).put("artifact_revision", record.revision)
                        .put("spec", JSONObject(record.spec)).toString()
                    File(directory, "artifact.json").writeText(definition)
                    report.put("artifact_sha256", sha256(definition))
                }
                failureAt = "review"
                report.put("chat_screen", screenshot("chat"))
                if (artifact != null) {
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    var open: AccessibilityNodeInfo? = null
                    while (open == null && SystemClock.uptimeMillis() < deadline) {
                        open = visible(R.string.tool_open)
                        if (open == null) SystemClock.sleep(50)
                    }
                    if (open != null) report.put("card_visible_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                    report.put("open_card_action", open?.let(::click) == true)
                }
                if (verifyTip && artifact != null) {
                    failureAt = "tip-interaction"
                    check(report.optBoolean("open_card_action"))
                    val callsBeforeInteraction = providerHttpCalls.get()
                    fun savedTool() = onMain { vm.tools.tools.singleOrNull { it.id == artifact } }
                    await(5_000) { savedTool() != null }
                    try {
                        verifyTipCalculator(robot, scenario, { checkNotNull(savedTool()) }, report, ::screenshot)
                    } finally {
                        if (report.optJSONObject("tip_interaction")?.optString("status") != "passed") screenshot("tip-failure")
                    }
                    check(providerHttpCalls.get() == callsBeforeInteraction)
                } else if (verifyTip) {
                    report.put("tip_interaction", JSONObject().put("status", "not-generated"))
                }
                if (verifyWorkflow) {
                    failureAt = "workflow"
                    val proof = JSONObject().put("status", "not-generated")
                    report.put("workflow", proof)
                    if (artifact != null) {
                        proof.put("status", "failed")
                        check(report.optBoolean("open_card_action"))
                        if (verifyTip) {
                            check(report.getJSONObject("tip_interaction").getString("status") == "passed")
                        } else if (canvasWorkflow) {
                            save("review")
                            val checks = CanvasLiveChecks(robot, proof) { name ->
                                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                                try {
                                    File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                } finally { bitmap.recycle() }
                            }
                            val saved = onMain { vm.tools.tools.single { it.id == artifact } }
                            val spec = ToolSpec.parsePersisted(saved.spec)
                            if (caseId == "canvas:wave") {
                                check(canvasSurfaceIds(spec.ui).isNotEmpty())
                                val sceneLabel = checkNotNull(firstCanvasLabel(spec.ui))
                                val amplitude = checks.slider("Amplitude", sceneLabel)
                                checks.captureCompact(sceneLabel) { robot.waitUntil { checks.progress("Amplitude") == amplitude } }
                            } else {
                                check(spec.presentation == "immersive") { "The generated game was not immersive." }
                                fun score(values: JSONObject): Double? {
                                    val key = values.keys().asSequence().firstOrNull { it.equals("score", true) || it.endsWith("_score") }
                                    return key?.let { (values.opt(it) as? Number)?.toDouble() }
                                }
                                val initial = checkNotNull(score(JSONObject(spec.initialState))) { "The game has no observable Score." }
                                checks.game(spec.title, initial, { onMain { score(JSONObject(vm.tools.tools.single { it.id == artifact }.state)) } }) {
                                    onMain { vm.openSurface(null) }
                                    robot.waitGone(robot.text(R.string.tool_game_controls_open))
                                    robot.click(robot.text(R.string.tool_open))
                                }
                                checks.captureCompact(spec.title) { robot.waitFor(robot.text(R.string.tool_game_controls_open)) }
                            }
                        } else {
                            val toolDatabase = "$database-tools.db"
                            fun savedTool() = onMain { vm.tools.tools.single { it.id == artifact } }
                            fun reopen() {
                                val before = savedTool()
                                Plan5WorkflowChecks.reopenSaved(robot, ToolSpec.parsePersisted(before.spec).title, fromChat = true)
                                val after = savedTool()
                                check(after.revision == before.revision)
                            }
                            var localCalls = providerHttpCalls.get()
                            Plan5WorkflowChecks(robot, proof, ::savedTool, ::screenshot, toolDatabase).run(caseId, ::reopen) {
                                check(providerHttpCalls.get() == localCalls)
                                submitSavedEdit(vm, robot, ::savedTool, it, proof, directory)
                                check(providerHttpCalls.get() > localCalls)
                                localCalls = providerHttpCalls.get()
                            }
                            check(providerHttpCalls.get() == localCalls)
                        }
                        proof.put("status", "passed")
                    }
                }
                if (canvasWorkflow && verifyWorkflow) check(report.optJSONObject("workflow")?.optString("status") == "passed")
                save("review")
                SystemClock.sleep(reviewSeconds * 1_000L)
                report.put("review_screen", screenshot("review"))
                if (repeat == 2) {
                    // The warm request: the same request in a new conversation of this process. The top level keeps the first one.
                    onMain {
                        vm.openSurface(null)
                        vm.select(null)
                    }
                    // Closing the app refreshes the library (busy for a moment). The Open card of the first chat must be gone,
                    // or it would end the time of the second request.
                    await(15_000) { onMain { !vm.tools.busy && vm.activeConversationId == null } && visible(R.string.tool_open) == null }
                    val second = JSONObject().put("measurements", JSONObject().put("first_ui_ms", JSONObject.NULL)
                        .put("completed_ui_ms", JSONObject.NULL).put("provider_http_calls", JSONObject.NULL))
                    report.put("second", second)
                    requests += RequestEvidence()
                    measuredRequest(second, "second-") ?: return
                }
                terminal = "complete"
            }
        } catch (failure: Exception) {
            report.put("failure_stage", failureAt).put("failure_type", failure.javaClass.simpleName)
                .put("failure_frame", failure.stackTrace.firstOrNull { it.className.startsWith("com.alpha") }?.toString())
            if (canvasWorkflow) report.put("failure_detail", failure.message?.take(400))
        } catch (failure: AssertionError) {
            report.put("failure_stage", failureAt).put("failure_type", failure.javaClass.simpleName)
                .put("failure_frame", failure.stackTrace.firstOrNull { it.className.startsWith("com.alpha") }?.toString())
            if (canvasWorkflow) report.put("failure_detail", failure.message?.take(400))
        } finally {
            try {
                val vm = onMain { model }
                if (vm != null) onMain { vm.cancelRequest() }
                activity?.close()
                if (vm != null) {
                    runBlocking { vm.tools.closeAndJoin() }
                    vm.courseStore.close()
                    vm.memoryStore.close()
                }
                GenerationSettings(app).screenshotReview = false
                if (pictures) imageSettings.setEnabled(false).getOrThrow()
                if (ownsStore) {
                    store.close()
                    keys.clear().getOrThrow()
                    app.deleteSharedPreferences(name)
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
                    databases.forEach(app::deleteDatabase)
                    removeShellIndexes(app, database)
                }
            } catch (failure: Exception) {
                if (terminal != "unresolved") terminal = "failed"
                report.put("cleanup_failure_type", failure.javaClass.simpleName)
            } finally {
                CloudTestActivity.factory = null
                putEvidence(report, requests.first())
                if (requests.size == 2) putEvidence(report.getJSONObject("second"), requests.last())
                save(terminal)
                if (savedEdit) check(terminal == "complete") { "Saved edit capture failed. Inspect its sanitized report." }
            }
        }
        check(terminal == "complete") { "Comparison capture failed. Inspect its sanitized report." }
    }

    private fun submitSavedEdit(vm: ShellViewModel, robot: ShellRobot, saved: () -> ToolRecord,
        request: String, proof: JSONObject, directory: File) {
        val before = saved()
        val refine = robot.text(R.string.tool_refine)
        robot.reveal(refine, null)
        robot.click(refine)
        robot.edit(robot.text(R.string.composer_label), request)
        val messagesBefore = onMain {
            check(!vm.tools.busy && vm.activeConversationId == null)
            checkNotNull(vm.snapshot.selected).messages.size
        }
        robot.click(robot.text(R.string.composer_send))
        await(300_000) {
            onMain {
                !vm.tools.busy && vm.activeConversationId == null &&
                    vm.snapshot.selected?.messages?.let { messages ->
                        messages.size > messagesBefore && messages.lastOrNull()?.status in
                            setOf("complete", "error", "cancelled", "interrupted")
                    } == true
            }
        }
        val assistant = onMain { checkNotNull(vm.snapshot.selected).messages.last() }
        proof.put("edit_status", assistant.status).put("edit_error_code", safeGenerationCode(assistant.errorCode))
        val revised = saved()
        check(revised.revision == before.revision + 1) { "Refinement did not publish." }
        val definition = JSONObject().put("artifact_id", revised.id).put("artifact_revision", revised.revision)
            .put("spec", JSONObject(revised.spec)).toString()
        File(directory, "refinement.json").writeText(definition)
        proof.put("refinement_sha256", sha256(definition))
        val open = robot.text(R.string.tool_open)
        robot.reveal(open, null)
        robot.click(open)
        robot.waitFor(robot.text(R.string.tool_refine))
    }

    private fun verifyTipCalculator(
        robot: ShellRobot,
        scenario: ActivityScenario<CloudTestActivity>,
        saved: () -> ToolRecord,
        report: JSONObject,
        screenshot: (String) -> Boolean,
    ) {
        val proof = JSONObject().put("status", "failed")
        report.put("tip_interaction", proof)
        fun nodes(root: JSONObject): Sequence<JSONObject> = sequence {
            yield(root)
            root.optJSONArray("children")?.let { children ->
                repeat(children.length()) { yieldAll(nodes(children.getJSONObject(it))) }
            }
        }
        val original = saved()
        val spec = ToolSpec.parsePersisted(original.spec)
        check(original.saved && spec.capabilities.isEmpty())
        proof.put("app_language", spec.appLanguage)
        check(spec.appLanguage == "en")
        val controls = nodes(JSONObject(original.ui)).toList()
        fun label(node: JSONObject) = node.getString("text")
        fun matches(node: JSONObject, expression: String) = Regex(expression, RegexOption.IGNORE_CASE).containsMatchIn(label(node))
        val editable = controls.filter { it.optString("type") in setOf("numberInput", "input") }
        proof.put("step", "find-controls")
        val bill = editable.single { matches(it, "bill|amount|subtotal|cost") }
        val peoplePattern = "people|person|guest|diner|party|split"
        // The people count can also be a value between − and + buttons.
        val stepper = if (editable.any { matches(it, peoplePattern) }) null else controls.filter { it.optString("type") == "row" }
            .firstNotNullOfOrNull { row ->
                val children = row.optJSONArray("children")?.let { array -> (0 until array.length()).map(array::getJSONObject) }.orEmpty()
                val count = children.singleOrNull { it.has("bind") && it.optString("type") in setOf("metric", "text") && matches(it, peoplePattern) }
                val down = children.singleOrNull { it.optString("type") == "button" && matches(it, "^\\s*[−–-]\\s*$|fewer|less|decrease|minus") }
                val up = children.singleOrNull { it.optString("type") == "button" && matches(it, "^\\s*\\+\\s*$|more|increase|plus") }
                if (count != null && down != null && up != null) Triple(count, down, up) else null
            }
        val people = stepper?.first ?: editable.single { matches(it, peoplePattern) }
        val slider = controls.single { it.optString("type") == "slider" }
        check(bill != people)
        val numberOrCurrency = Regex("""\s*\p{Sc}?\s*(-?\d+(?:\.\d+)?)\s*\p{Sc}?\s*""")
        fun value(node: JSONObject, state: JSONObject): Double? {
            val raw = ToolUiCatalog.value(state, node.getString("bind"))
            return if (raw is Number) raw.toDouble()
            else numberOrCurrency.matchEntire(raw.toString())?.groupValues?.get(1)?.toDoubleOrNull()
        }
        fun state() = JSONObject(saved().state)
        fun equal(actual: Double?, expected: Double) = actual != null && kotlin.math.abs(actual - expected) < 0.00001
        fun apply(node: JSONObject) {
            val apply = robot.text(R.string.tool_generated_apply_en, label(node))
            val finish = robot.text(R.string.tool_live_input_finish_en, label(node))
            val text = if (ToolUiCatalog.isLiveInput(node) &&
                (robot.find(finish) != null || robot.find(apply) == null)) finish else apply
            robot.reveal(text, null)
            robot.click(text)
        }
        fun edit(node: JSONObject, amount: Number) {
            if (stepper != null && node === stepper.first) {
                repeat(20) {
                    val current = checkNotNull(value(node, state()))
                    if (equal(current, amount.toDouble())) return
                    val button = label(if (current < amount.toDouble()) stepper.third else stepper.second)
                    robot.reveal(button, null)
                    robot.click(button)
                    await(5_000) { !equal(value(node, state()), current) }
                    robot.waitGone(robot.text(R.string.tool_working))
                }
                error("The people count did not reach $amount.")
            }
            if (equal(value(node, state()), amount.toDouble())) return
            robot.reveal(label(node), null)
            robot.edit(label(node), amount.toString())
            apply(node)
            await(5_000) { equal(value(node, state()), amount.toDouble()) }
            robot.waitGone(robot.text(R.string.tool_working))
        }
        proof.put("step", "bill")
        val originalBill = value(bill, state())
        proof.put("bill_before", originalBill)
        if (equal(originalBill, 80.0)) {
            val alternative = if (bill.optString("type") == "numberInput") {
                val (min, max) = ToolUiCatalog.bounds(bill)
                if (min < 80.0) maxOf(min, 79.0) else minOf(max, 81.0)
            } else 79.0
            check(alternative != 80.0)
            edit(bill, alternative)
            proof.put("bill_alternative", value(bill, state()))
        }
        edit(bill, 80)
        proof.put("bill_after", value(bill, state()))
        proof.put("step", "tip-slider")
        val (min, max) = ToolUiCatalog.bounds(slider)
        val sliderProof = JSONObject().put("min", min).put("max", max).put("step", slider.opt("step"))
        proof.put("slider", sliderProof)
        check(15.0 in min..max && min < max)
        fun setTip(amount: Double) {
            robot.waitGone(robot.text(R.string.tool_working))
            val setProgress = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
            fun adjustable(candidate: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (candidate.isEnabled && candidate.isVisibleToUser &&
                    candidate.actionList.any { it.id == setProgress }) return candidate
                return (0 until candidate.childCount).firstNotNullOfOrNull { candidate.getChild(it)?.let(::adjustable) }
            }
            var available = robot.diagnostics.freshRoot()?.let(::adjustable)
            repeat(10) {
                if (available == null && robot.scrollForward()) {
                    available = robot.diagnostics.freshRoot()?.let(::adjustable)
                }
            }
            sliderProof.put("adjustable", available != null)
            val target = checkNotNull(available)
            val range = checkNotNull(target.rangeInfo)
            val requestedProgress = range.min + ((amount - min) / (max - min)).toFloat() * (range.max - range.min)
            val progress = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, requestedProgress)
            }
            sliderProof.put("progress_before", range.current.toDouble()).put("progress_requested", requestedProgress.toDouble())
            val accepted = target.performAction(setProgress, progress)
            sliderProof.put("action_accepted", accepted)
            check(accepted)
            if (!ToolUiCatalog.isLiveInput(slider)) apply(slider)
            try {
                if (ToolUiCatalog.isLiveInput(slider)) {
                    val fallback = robot.text(R.string.tool_generated_apply_en, label(slider))
                    await(5_000) { equal(value(slider, state()), amount) || robot.find(fallback)?.isEnabled == true }
                    if (!equal(value(slider, state()), amount)) {
                        sliderProof.put("used_apply_fallback", true)
                        apply(slider)
                    }
                }
                await(5_000) { equal(value(slider, state()), amount) }
                robot.waitGone(robot.text(R.string.tool_working))
            } finally {
                sliderProof.put("saved_tip", value(slider, state()))
            }
        }
        if (equal(value(slider, state()), 15.0)) setTip(if (min == 15.0) max else min)
        setTip(15.0)
        proof.put("step", "people")
        edit(people, 4)

        fun verifyResults(share: Double): JSONArray {
            val expected = listOf("tip|gratuity" to 12.0, "total" to 92.0, "person|share|each|split" to share)
            var observedState = JSONObject()
            var observedNodes = emptyList<JSONObject?>()
            await(10_000) {
                val current = saved()
                observedState = JSONObject(current.state)
                val outputs = nodes(JSONObject(current.ui)).filter {
                    it.has("bind") && it.optString("type") in setOf("text", "metric", "fact", "badge")
                }.toList()
                observedNodes = expected.map { (pattern, amount) ->
                    outputs.firstOrNull { matches(it, pattern) && equal(value(it, observedState), amount) }
                }
                observedNodes.all { it != null }
            }
            return JSONArray(expected.zip(observedNodes).map { (target, found) ->
                val node = checkNotNull(found)
                val display = ToolUiCatalog.display(node, observedState)
                if (node.optString("type") in setOf("metric", "fact")) {
                    check(robot.reveal(label(node), null).isVisibleToUser)
                    check(robot.reveal(display.removePrefix("${label(node)}: "), null).isVisibleToUser)
                } else check(robot.reveal(display, null).isVisibleToUser)
                JSONObject().put("label", label(node)).put("expected", target.second).put("observed", value(node, observedState))
            })
        }
        proof.put("step", "recalculate")
        edit(people, 2)
        proof.put("changed", verifyResults(46.0))
        edit(people, 4)
        proof.put("initial", verifyResults(23.0))
        check(screenshot("tip-result"))

        proof.put("step", "reopen")
        val close = robot.text(R.string.tool_library_back)
        robot.click(close)
        robot.waitGone(close)
        scenario.recreate()
        // The app was opened from its chat card, so closing it returns to that chat.
        proof.put("step", "reopen-chat-card")
        val open = robot.text(R.string.tool_open)
        robot.reveal(open, null)
        robot.click(open)
        robot.waitFor(close)
        proof.put("step", "reopen-values")
        val reopened = state()
        check(equal(value(bill, reopened), 80.0) && equal(value(slider, reopened), 15.0) && equal(value(people, reopened), 4.0))
        proof.put("reopened", verifyResults(23.0))
        check(screenshot("tip-reopened"))
        proof.put("status", "passed").put("step", "complete")
    }

    /** The provider calls and the generation trace of one request. A repeated capture keeps one for each request. */
    private class RequestEvidence {
        val invocations = CopyOnWriteArrayList<String>()
        val httpDispatches = CopyOnWriteArrayList<JSONObject>()
        val decisionRejections = CopyOnWriteArrayList<JSONObject>()
        val acceptedOperations = CopyOnWriteArrayList<JSONObject>()
        val generation = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
    }

    /** Writes the calls and the trace of one request into [into]: the report, or its object "second". */
    private fun putEvidence(into: JSONObject, evidence: RequestEvidence) {
        into.put("provider_invocations", JSONArray(evidence.invocations.toList()))
        into.getJSONObject("measurements").put("provider_http_calls", evidence.httpDispatches.size)
        into.put("operation_trace", JSONObject().put("clock", "elapsed-realtime-ns")
            .put("http_dispatches", JSONArray(evidence.httpDispatches.toList()))
            .put("decision_rejections", JSONArray(evidence.decisionRejections.toList()))
            .put("accepted_operations", JSONArray(evidence.acceptedOperations.toList()))
            .put("generation", JSONArray(evidence.generation.map(::traceEvent))))
    }

    private fun traceEvent(event: ToolGenerationTraceEvent): JSONObject = JSONObject()
        .put("operation_id", event.operationId.toString()).put("attempt", event.attempt).put("at_ns", event.atNanos)
        .apply {
            when (event) {
                is ToolGenerationTraceEvent.Transport -> event.failure.let { failure ->
                    put("kind", "transport").put("provider_stage", failure.stage.name)
                    put("http_attempt", failure.httpAttempt).put("phase", failure.phase.wire)
                    put("failure_code", failure.code).put("retry_requested", failure.retryRequested)
                }
                is ToolGenerationTraceEvent.AgentStep -> {
                    put("kind", "agent-step").put("tool", event.tool).put("reply", event.reply).put("result", event.result)
                        .put("duration_ms", event.durationNanos / 1_000_000)
                        .put("model_calls", JSONArray(event.completions.map { call ->
                            JSONObject().put("first_text_ms", call.firstTextNanos?.div(1_000_000) ?: JSONObject.NULL)
                                .put("end_ms", call.endNanos / 1_000_000).put("text_chars", call.textChars)
                                .put("completed", call.completed)
                        }))
                }
                is ToolGenerationTraceEvent.Finished -> {
                    put("kind", "finished").put("outcome", event.outcome.name).put("failure_code", event.failureCode)
                }
                is ToolGenerationTraceEvent.Phase -> {
                    put("kind", "phase").put("name", event.name).put("detail", event.detail ?: JSONObject.NULL)
                        .put("ms", event.ms ?: JSONObject.NULL)
                }
            }
        }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.takeIf { it.isEnabled && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        check(waitFor(timeoutMs, condition)) { "The capture checkpoint timed out." }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(30)
        }
        return false
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
