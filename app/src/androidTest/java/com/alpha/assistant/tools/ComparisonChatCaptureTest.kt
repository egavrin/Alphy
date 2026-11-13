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
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.chat.MAX_CHAT_INPUT
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.ui.seedOpenChat
import com.alpha.assistant.weather.WeatherLocator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

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
        val input = JSONObject(String(Base64.decode(checkNotNull(encoded), Base64.URL_SAFE), Charsets.UTF_8))
        val runId = input.getString("run_id")
        val slotId = input.getString("slot_id")
        val request = input.getString("request")
        val reviewSeconds = input.getInt("review_seconds")
        val caseId = slotId.substringBeforeLast(":candidate:")
        val canvasWorkflow = caseId in setOf("canvas:arkanoid", "canvas:wave")
        val savedEdit = input.optBoolean("saved_edit")
        val verifyWorkflow = input.optBoolean("verify_workflow")
        require(!savedEdit || verifyWorkflow && caseId in setOf("extra:reference-points-ledger", "extra:russian-grid-game"))
        val verifyTip = input.optBoolean("verify_tip") || verifyWorkflow && caseId == "bench:tip"
        require(runId.matches(Regex("[a-f0-9]{32}")) && slotId.matches(Regex("[A-Za-z0-9:_-]{1,168}:candidate:[1-3]")))
        require(request.isNotBlank() && request.trim() == request && request.length <= MAX_CHAT_INPUT && '\u0000' !in request)
        require(reviewSeconds in 5..180)
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
        val providerInvocations = CopyOnWriteArrayList<String>()
        val httpDispatches = CopyOnWriteArrayList<JSONObject>()
        val decisionRejections = CopyOnWriteArrayList<JSONObject>()
        val acceptedOperations = CopyOnWriteArrayList<JSONObject>()
        val generationEvents = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val providerHttpCalls = AtomicInteger()
        val name = "comparison-$runId"
        val database = "$name.db"
        val databases = listOf(database, "$database-tools.db", "$database-tools.db-automations.db",
            "$database-courses.db", "$database-memory.db")
        val store = ShellStore(app, database)
        val keys = SecureKeyStore(app, name, name)
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
            val credential = OneUseCredential.receive(runId) { save("credentialReady") }
            val provider = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    check(key == marker)
                    val stage = request.generationStage?.name ?: if (request.chatDecision) "CHAT_DECISION" else "CHAT"
                    providerInvocations.add(stage)
                    session.decisionRejectionObserver = { event ->
                        decisionRejections += JSONObject().put("stage",event.stage).put("attempt",event.attempt)
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
                    val live = DeepSeekProvider("https://api.deepseek.com", onHttpCall = {
                        providerHttpCalls.incrementAndGet()
                        httpDispatches += JSONObject().put("stage", stage)
                            .put("at_ns", SystemClock.elapsedRealtimeNanos())
                    }) {}
                    val tracedRequest = if (request.chatDecision) request.copy(validateOperation = { decision ->
                        request.validateOperation?.invoke(decision)
                        acceptedOperations += JSONObject().put("kind", decision.kind).put("search", decision.search)
                            .put("at_ns", SystemClock.elapsedRealtimeNanos())
                    }) else request
                    live.respond(credential, tracedRequest, session, emit)
                }
            }
            keys.save(marker).getOrThrow()
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
                ShellViewModel(application, provider, store, keys, weatherLocator = noLocation).also { model = it }
            }
            val scenario = ActivityScenario.launch(CloudTestActivity::class.java)
            activity = scenario
            val vm = onMain { checkNotNull(model) }
            await(15_000) { onMain { !vm.loading && !vm.tools.busy } }
            check(onMain { !vm.loadFailed && vm.snapshot.conversations.isEmpty() &&
                vm.tools.tools.map { it.id } == listOfNotNull(savedId) && vm.tools.drafts.isEmpty() })
            onMain { vm.tools.generationTraceObserver = { generationEvents += it } }
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
            failureAt = "send"
            robot.edit(robot.text(R.string.composer_label), request)
            val draft = onMain {
                check(!vm.tools.busy && vm.activeConversationId == null)
                vm.snapshot.draft
            }
            check(draft.text == request && draft.attachments.isEmpty())
            report.put("fresh_draft", draft.id)
            val send = checkNotNull(visible(R.string.composer_send))
            report.put("send_action_uptime_ms", SystemClock.uptimeMillis())
            report.put("send_action_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            check(click(send)) { "The composer Send action did not run." }
            save("sent")
            failureAt = "chat-terminal"
            var previewObserved = false
            val settled = waitFor(240_000) {
                if (!previewObserved && visible(R.string.tool_preview_provisional) != null) {
                    previewObserved = true
                    report.put("provisional_visible_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                    report.put("provisional_label_observed", true)
                    report.put("provisional_screen", screenshot("provisional"))
                }
                onMain {
                    val assistant = vm.snapshot.selected?.messages?.lastOrNull()?.takeIf { it.role == "assistant" }
                    !vm.tools.busy && vm.activeConversationId == null && assistant?.status in terminalChatStatuses
                }
            }
            if (!settled) {
                val deadline = onMain {
                    captureChatState(vm).apply {
                        val at = getLong("at_ns")
                        val last = generationEvents.lastOrNull { it.atNanos <= at }
                        put("generation_last", last?.let(::traceEvent) ?: JSONObject.NULL)
                        put("provider_http_calls", providerHttpCalls.get())
                    }
                }
                report.put("capture_deadline", deadline)
                if (!deadline.getBoolean("terminal") || !deadline.getBoolean("idle")) {
                    terminal = "unresolved"
                    report.put("failure_stage", failureAt)
                    report.put("deadline_screen", screenshot("deadline"))
                    return
                }
            }
            val chat = onMain { checkNotNull(vm.snapshot.selected) }
            val user = chat.messages.first()
            val assistant = chat.messages.last()
            check(user.role == "user" && assistant.replyTo == user.id && sha256(user.text) == input.getString("request_sha256"))
            report.put("fresh_conversation", chat.id).put("assistant_message", assistant.id)
                .put("assistant_status", assistant.status).put("operation_id", assistant.toolOperationId ?: JSONObject.NULL)
                .put("provisional_label_observed", previewObserved)
            val decision = acceptedOperations.lastOrNull()?.optString("kind")
            decision?.let { report.put("chat_decision", it) }
            report.put("assistant_text", assistant.text)
            val sentAt = report.getLong("send_action_elapsed_ns")
            report.getJSONObject("measurements").put("terminal_chat_ms",
                (SystemClock.elapsedRealtimeNanos() - sentAt) / 1_000_000)
            if (decision == "device_plan") {
                onMain { vm.agent.plan?.takeIf { it.conversationId == chat.id } }?.let { plan ->
                    report.put("phone_plan", JSONObject().put("phase", plan.phase.name)
                        .put("steps", JSONArray(plan.steps.map { it.command.json() }))
                        .put("results", JSONArray(plan.results)))
                }
            }
            generationEvents.filterIsInstance<ToolGenerationTraceEvent.Attempt>().lastOrNull {
                it.operationId.toString() == assistant.toolOperationId && it.disposition == ToolGenerationDisposition.FINISH
            }?.let { report.put("terminal_attempt", traceEvent(it)) }
            val artifact = assistant.toolId
            report.put("chat_outcome", when {
                assistant.status != "complete" -> "request-failed"
                artifact != null && assistant.toolRevision != null -> "artifact-committed"
                else -> "complete-without-artifact"
            })
            if (assistant.status != "complete") {
                report.put("failure_code", ToolGenerationAttemptDiagnostic.safeCode(assistant.errorCode))
                if (canvasWorkflow) report.put("scene_diagnostic", assistant.error?.take(400))
            }
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
            terminal = "complete"
        } catch (failure: Exception) {
            report.put("failure_stage", failureAt).put("failure_type", failure.javaClass.simpleName)
            if (canvasWorkflow) report.put("failure_detail", failure.message?.take(400))
        } catch (failure: AssertionError) {
            report.put("failure_stage", failureAt).put("failure_type", failure.javaClass.simpleName)
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
                if (ownsStore) {
                    store.close()
                    keys.clear().getOrThrow()
                    app.deleteSharedPreferences(name)
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
                    databases.forEach(app::deleteDatabase)
                }
            } catch (failure: Exception) {
                if (terminal != "unresolved") terminal = "failed"
                report.put("cleanup_failure_type", failure.javaClass.simpleName)
            } finally {
                CloudTestActivity.factory = null
                report.put("provider_invocations", JSONArray(providerInvocations.toList()))
                report.getJSONObject("measurements").put("provider_http_calls", providerHttpCalls.get())
                report.put("operation_trace", JSONObject().put("clock", "elapsed-realtime-ns")
                    .put("http_dispatches", JSONArray(httpDispatches.toList()))
                    .put("decision_rejections", JSONArray(decisionRejections.toList()))
                    .put("accepted_operations", JSONArray(acceptedOperations.toList()))
                    .put("generation", JSONArray(generationEvents.map(::traceEvent))))
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
        proof.put("edit_status", assistant.status).put("edit_error_code", ToolGenerationAttemptDiagnostic.safeCode(assistant.errorCode))
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
        val people = editable.single { matches(it, "people|person|guest|diner|party|split") }
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
                observedState = projectToolState(spec, JSONObject(current.state), localPartClock(app))
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
        proof.put("step", "reopen-library-item")
        robot.reveal(spec.title, null)
        robot.click(spec.title)
        robot.waitFor(close)
        proof.put("step", "reopen-values")
        val reopened = state()
        check(equal(value(bill, reopened), 80.0) && equal(value(slider, reopened), 15.0) && equal(value(people, reopened), 4.0))
        proof.put("reopened", verifyResults(23.0))
        check(screenshot("tip-reopened"))
        proof.put("status", "passed").put("step", "complete")
    }

    private fun traceEvent(event: ToolGenerationTraceEvent): JSONObject = JSONObject()
        .put("operation_id", event.operationId.toString()).put("attempt", event.attempt).put("at_ns", event.atNanos)
        .apply {
            when (event) {
                is ToolGenerationTraceEvent.Stage -> {
                    put("kind", if (event.started) "stage-start" else "stage-end").put("stage", event.stage.id)
                    put("selector_batch", event.selectorBatch).put("provider_stage", event.providerStage?.name)
                }
                is ToolGenerationTraceEvent.Attempt -> {
                    put("kind", "attempt").put("stage", event.stage.id).put("outcome", event.outcome.id)
                    put("disposition", event.disposition.id).put("failure_code", event.failureCode)
                    put("validation_category", event.validationCategory?.id)
                    put("validation_rule", event.validationRule)
                    put("behavior", event.behavior)
                    put("behavior_binding", event.behaviorBinding?.report())
                    put("smoke", event.smoke)
                    put("selector_batch", event.selectorBatch).put("plan_path", event.planPath)
                    event.planShape?.let { put("plan_rule",it.rule).put("plan_observed_type",it.observedType) }
                }
                is ToolGenerationTraceEvent.Repair -> {
                    put("kind", "repair").put("provider_stage", event.providerStage.name)
                    put("repair_number", event.repairNumber).put("mode", event.mode.wire).put("result", event.result)
                    put("response_bytes", event.responseBytes).put("candidate_bytes", event.candidateBytes)
                    put("changes", event.changes).put("source_edits", event.sourceEdits).put("duration_ns", event.durationNanos)
                }
                is ToolGenerationTraceEvent.Transport -> event.failure.let { failure ->
                    put("kind", "transport").put("provider_stage", failure.stage.name)
                    put("http_attempt", failure.httpAttempt).put("phase", failure.phase.wire)
                    put("failure_code", failure.code).put("retry_requested", failure.retryRequested)
                }
                is ToolGenerationTraceEvent.Finished -> {
                    put("kind", "finished").put("outcome", event.outcome.name).put("failure_code", event.failureCode)
                    put("parts_only", event.partsOnly)
                    put("route", event.route?.id)
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
