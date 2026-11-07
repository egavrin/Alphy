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
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
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

/** Captures real chat checkpoints. A saved artifact or accessibility label does not establish runtime usability. */
class ComparisonChatCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test
    fun captureFrozenSlot() {
        val encoded = InstrumentationRegistry.getArguments().getString("comparisonEnvelope")
        assumeNotNull(encoded)
        val input = JSONObject(String(Base64.decode(checkNotNull(encoded), Base64.URL_SAFE), Charsets.UTF_8))
        val runId = input.getString("run_id")
        val slotId = input.getString("slot_id")
        val request = input.getString("request")
        val reviewSeconds = input.getInt("review_seconds")
        val verifyTip = input.optBoolean("verify_tip")
        require(runId.matches(Regex("[a-f0-9]{32}")) && slotId.matches(Regex("[A-Za-z0-9:_-]{1,168}:candidate:[1-3]")))
        require(request.isNotBlank() && request.trim() == request && request.length <= MAX_CHAT_INPUT && '\u0000' !in request)
        require(reviewSeconds in 5..180)
        require(!verifyTip || slotId.startsWith("bench:tip:candidate:"))
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
                    }
                    val live = DeepSeekProvider("https://api.deepseek.com", onHttpCall = {
                        providerHttpCalls.incrementAndGet()
                        httpDispatches += JSONObject().put("stage", stage)
                            .put("at_ns", SystemClock.elapsedRealtimeNanos())
                    }) {}
                    live.respond(credential, request, session, emit)
                }
            }
            keys.save(marker).getOrThrow()
            failureAt = "launch"
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
            check(onMain { !vm.loadFailed && vm.snapshot.conversations.isEmpty() && vm.tools.tools.isEmpty() && vm.tools.drafts.isEmpty() })
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
            failureAt = "send"
            robot.edit(robot.text(R.string.composer_label), request)
            val draft = onMain { vm.snapshot.draft }
            check(draft.text == request && draft.attachments.isEmpty())
            report.put("fresh_draft", draft.id)
            val send = checkNotNull(visible(R.string.composer_send))
            report.put("send_action_uptime_ms", SystemClock.uptimeMillis())
            report.put("send_action_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            check(click(send)) { "The composer Send action did not run." }
            save("sent")
            failureAt = "chat-terminal"
            var previewObserved = false
            var sawAssistant = false
            await(240_000) {
                if (!previewObserved && visible(R.string.tool_preview_provisional) != null) {
                    previewObserved = true
                    report.put("provisional_visible_elapsed_ns", SystemClock.elapsedRealtimeNanos())
                    report.put("provisional_label_observed", true)
                    report.put("provisional_screen", screenshot("provisional"))
                }
                onMain {
                    val assistant = vm.snapshot.selected?.messages?.lastOrNull()?.takeIf { it.role == "assistant" }
                    if (assistant != null) sawAssistant = true
                    sawAssistant && !vm.tools.busy && vm.activeConversationId == null &&
                        assistant?.status in setOf("complete", "error", "cancelled", "interrupted")
                }
            }
            val chat = onMain { checkNotNull(vm.snapshot.selected) }
            val user = chat.messages.first()
            val assistant = chat.messages.last()
            check(user.role == "user" && assistant.replyTo == user.id && sha256(user.text) == input.getString("request_sha256"))
            report.put("fresh_conversation", chat.id).put("assistant_message", assistant.id)
                .put("assistant_status", assistant.status).put("operation_id", assistant.toolOperationId ?: JSONObject.NULL)
                .put("provisional_label_observed", previewObserved)
            val artifact = assistant.toolId
            report.put("chat_outcome", when {
                assistant.status != "complete" -> "request-failed"
                artifact != null && assistant.toolRevision != null -> "artifact-committed"
                else -> "complete-without-artifact"
            })
            if (assistant.status != "complete") {
                report.put("failure_code", ToolGenerationAttemptDiagnostic.safeCode(assistant.errorCode))
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
            save("review")
            SystemClock.sleep(reviewSeconds * 1_000L)
            report.put("review_screen", screenshot("review"))
            terminal = "complete"
        } catch (failure: Exception) {
            report.put("failure_stage", failureAt).put("failure_type", failure.javaClass.simpleName)
        } catch (failure: AssertionError) {
            report.put("failure_stage", failureAt).put("failure_type", failure.javaClass.simpleName)
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
                terminal = "failed"
                report.put("cleanup_failure_type", failure.javaClass.simpleName)
            } finally {
                CloudTestActivity.factory = null
                report.put("provider_invocations", JSONArray(providerInvocations.toList()))
                report.getJSONObject("measurements").put("provider_http_calls", providerHttpCalls.get())
                report.put("operation_trace", JSONObject().put("clock", "elapsed-realtime-ns")
                    .put("http_dispatches", JSONArray(httpDispatches.toList()))
                    .put("decision_rejections", JSONArray(decisionRejections.toList()))
                    .put("generation", JSONArray(generationEvents.map(::traceEvent))))
                save(terminal)
            }
        }
        check(terminal == "complete") { "Comparison capture failed. Inspect its sanitized report." }
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
                    put("selector_batch", event.selectorBatch).put("plan_path", event.planPath)
                    event.planShape?.let { put("plan_rule",it.rule).put("plan_observed_type",it.observedType) }
                }
                is ToolGenerationTraceEvent.Finished -> {
                    put("kind", "finished").put("outcome", event.outcome.name).put("failure_code", event.failureCode)
                    put("parts_only", event.partsOnly)
                }
            }
        }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.takeIf { it.isEnabled && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(30)
        }
        error("The capture checkpoint timed out.")
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
