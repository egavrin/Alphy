package com.alpha.assistant.tools.agent

import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.CloudTurn
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.semantic.SemanticRuntime
import com.alpha.assistant.semantic.SemanticUnavailable
import com.alpha.assistant.tools.AppLanguage
import com.alpha.assistant.tools.GenerationLanguage
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.ToolGenerationProgress
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSmokeFailure
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import com.alpha.assistant.tools.ToolUiDiagnostic
import com.alpha.assistant.tools.ToolUiValidationStage
import com.alpha.assistant.tools.ToolValidationFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class AppAgentRequest(
    val prompt: String,
    val base: ToolRecord?,
    val images: List<String>,
    val artifactId: String,
    val privateState: Boolean,
    val policy: ToolExecutionPolicy?,
    val extraInstructions: String = "",
    val onNote: (suspend (String) -> Unit)? = null,
    val progress: ((ToolGenerationProgress) -> Unit)? = null,
    val onStep: ((step: Int, tool: String, reply: String, result: String, durationNanos: Long) -> Unit)? = null,
    /** Test and fix: finish may keep the app unchanged, and edits need a reproduced problem first. */
    val testFix: Boolean = false,
)

internal class AppAgentResult(val preview: ToolPreview, val summary: String, val steps: Int, val changed: Boolean)

/**
 * One generation: the model calls one tool in each step, and the host replies with the tool result.
 * The host validates every version and runs the model's checks. Only a passing finish ends the loop.
 */
internal class AppAgent(
    private val service: ToolGenerationService,
    private val sandbox: SandboxClient,
    private val complete: suspend (turns: List<CloudTurn>, instructions: String) -> String,
) {
    private class Feedback(message: String) : Exception(message)

    private class Candidate(val definition: JSONObject, val spec: ToolSpec, val state: String, val preview: ToolPreview)

    private class Finished(val summary: String)

    /** True after the host accepted a changed version. Read it also after a failed run. */
    var edited = false
        private set

    /** Test and fix: true after a run on the unchanged app confirmed a problem. */
    var reproduced = false
        private set

    suspend fun run(request: AppAgentRequest, session: CloudSession): AppAgentResult {
        val previous = request.base?.let { ToolSpec.parsePersisted(it.spec) }
        // Device results, collections, and live state stay local. The model never sees their values or the saved layout.
        val privateApp = previous != null && (request.privateState || previous.capabilityResults || previous.collections.length() > 0)
        val instructions = AppAgentGuide.system(capabilityGuidance(request.prompt), request.extraInstructions)
        // shortcut: reads the "User request: ...\nWidget brief:" format of ChatToolReply.generationPrompt; a direct prompt is used whole.
        val requestLanguage = if (previous != null) null else
            GenerationLanguage.identifyText(request.prompt.substringAfter("User request: ").substringBefore("\nWidget brief:"))
        var task = taskMessage(request, previous, privateApp, requestLanguage)
        val history = mutableListOf<CloudTurn>()
        var draft: JSONObject? = previous?.let { JSONObject(it.providerDefinition()).apply { remove("formatVersion") } }
        var current: Candidate? = null
        val reproductions = mutableListOf<List<AppStep>>()
        var lastWriteIndex = -1
        var draftMatchesLastWrite = true
        var lastProblem = ""

        suspend fun checkActive() = session.checkActive(currentCoroutineContext())

        suspend fun validate(next: JSONObject, onReplay: ((List<String>) -> Unit)? = null): Candidate {
            val definition = JSONObject(next.toString())
            val tag = definition.remove("language")
            val language: AppLanguage? = when {
                tag != null -> try { GenerationLanguage.parse(tag, null, request.prompt) }
                    catch (_: Exception) { throw Feedback("language must be a lowercase language tag, for example \"en\" or \"ru\".") }
                previous?.appLanguage != null -> AppLanguage(previous.appLanguage, emptySet())
                else -> null
            }
            definition.put("formatVersion", 3)
            // A literal value in stateSchema names its type unambiguously.
            definition.optJSONObject("stateSchema")?.let { schema ->
                schema.keys().asSequence().toList().forEach { field ->
                    when (val value = schema.opt(field)) {
                        is Boolean -> schema.put(field, "boolean")
                        is Number -> schema.put(field, "number")
                        // A string that repeats its initial value is a literal, not a type name.
                        is String -> if (value !in scalarTypes && definition.optJSONObject("initialState")?.opt(field) == value) schema.put(field, "string")
                    }
                }
            }
            if (!definition.has("description")) definition.put("description", "")
            if (!definition.has("capabilities")) definition.put("capabilities", JSONArray())
            val spec = try {
                ToolSpec.parse(service.normalizeAgentDefinition(definition.toString()))
                    .withAppLanguage(language?.tag).withIcon(previous?.iconId)
            } catch (failure: ToolValidationFailure) {
                throw Feedback(listOfNotNull(failure.message, failure.uiDiagnostic?.correction,
                    failure.uiDiagnostic?.let { definitionHint(definition, it) }).joinToString(" "))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is JSONException) throw failure
                throw Feedback("The app definition is invalid: ${failure.message ?: failure.javaClass.simpleName}")
            }
            request.policy?.let { policy ->
                try { policy.validateDefinition(spec.json) } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { throw Feedback("The app breaks this request's policy: ${failure.message}") }
            }
            val state = if (previous == null || request.base == null) spec.initialState else try {
                ToolSpec.stateForRevision(previous, spec, request.base.state)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw Feedback("The new state schema cannot hold the saved data. Keep every existing state field with its type, " +
                    "and give new fields initial values. ${failure.message.orEmpty()}")
            }
            checkActive()
            val smoke = ToolGenerationSmoke(sandbox, spec, request.artifactId, (request.base?.revision ?: 0) + 1, state,
                checkActive = { checkActive() }, generation = service, policy = request.policy,
                observeReplay = onReplay?.let { { _: List<String> -> } })
            val preview = try {
                smoke.run()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ToolSmokeFailure) {
                AppSteps.sandboxFailure(failure.cause)?.let { throw it }
                onReplay?.invoke(failure.replay.orEmpty())
                throw Feedback("The smoke test failed. ${failure.correction()} Cause: ${AppSteps.failureText(failure)}")
            } catch (failure: CloudFailure) {
                AppSteps.sandboxFailure(failure)?.let { throw it }
                throw Feedback("The smoke test failed: ${failure.code}: ${failure.message}")
            } catch (failure: Exception) {
                AppSteps.sandboxFailure(failure)?.let { throw it }
                if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is JSONException) throw failure
                throw Feedback("The smoke test failed: ${failure.message ?: failure.javaClass.simpleName}")
            }
            if (language != null && GenerationLanguage.Check(language).mismatches(JSONObject(preview.ui), spec.title, spec.description)) {
                throw Feedback("The visible text is not in ${GenerationLanguage.name(language.tag)}. Translate every title, label, option, and message.")
            }
            presentationProblem(JSONObject(preview.ui), JSONObject(preview.state))?.let { throw Feedback(it) }
            return Candidate(definition, spec, state, preview)
        }

        suspend fun firstScreen(candidate: Candidate): String = smokeFor(candidate, request).probe {
            AppScreen.render(ui, state, surfaces, nowMillis, surfaceSizes).text
        }

        suspend fun accepted(tool: String, candidate: Candidate): String {
            current = candidate
            edited = true
            if (privateApp) return "$tool: OK. The app passed validation and the smoke test. $PRIVATE_SCREEN"
            return "$tool: OK. The app passed validation and the smoke test.\nFirst screen:\n${firstScreen(candidate)}\n$REVIEW_SCREEN"
        }

        suspend fun handle(reply: JSONObject): Any {
            reply.optString("note").trim().takeIf { it.isNotEmpty() }?.let { note -> request.onNote?.invoke(note.take(160)) }
            return when (val tool = reply.optString("tool")) {
                "write_app" -> {
                    request.progress?.invoke(ToolGenerationProgress.COMPLETING)
                    if (request.testFix && !reproduced) throw Feedback(REPRODUCE_FIRST)
                    if (privateApp) throw Feedback(PRIVATE_EDIT)
                    val app = objectArgument(reply, "app")
                    draft = app
                    lastWriteIndex = history.lastIndex
                    draftMatchesLastWrite = true
                    current = null
                    accepted(tool, validate(app))
                }
                "edit_app" -> {
                    request.progress?.invoke(ToolGenerationProgress.COMPLETING)
                    if (request.testFix && !reproduced) throw Feedback(REPRODUCE_FIRST)
                    val base = draft ?: throw Feedback("There is no app yet. Call write_app first.")
                    if (privateApp && reply.optJSONObject("set")?.let { it.has("ui") || it.has("initialState") } == true) throw Feedback(PRIVATE_EDIT)
                    val next = applyEdits(base, reply, uiLocked = privateApp)
                    draft = next
                    draftMatchesLastWrite = false
                    current = null
                    accepted(tool, validate(next))
                }
                "run" -> {
                    request.progress?.invoke(ToolGenerationProgress.VALIDATING)
                    val candidate = current ?: throw Feedback(noValidApp(draft))
                    val steps = try { AppSteps.parse(reply.opt("steps")) } catch (failure: AppStepError) { throw Feedback(failure.message.orEmpty()) }
                    val outcome = smokeFor(candidate, request).probe { AppSteps.run(this, steps, everyStep = false) to requested }
                    val (result, requested) = outcome
                    val confirms = request.testFix && !edited && result.failure != null && confirmsProblem(steps, result, request.prompt)
                    if (confirms) {
                        reproduced = true
                        reproductions += steps
                    }
                    buildString {
                        append("run: ").append(result.failure?.let { "FAILED at ${shown(it, result, privateApp)}" } ?: "OK, ${steps.size} steps ran.")
                        if (confirms) append("\nThis confirms the problem. Fix the app. finish runs these steps again.")
                        else if (request.testFix && !edited && result.failure != null)
                            append("\nThis does not confirm a problem. Only an app failure, or an expected value written in the user's request, confirms one.")
                        if (requested.isNotEmpty()) append("\nThe last operation requested: ${requested.joinToString()}.")
                        if (privateApp) append("\n").append(PRIVATE_SCREEN) else result.final?.let { append("\nScreen:\n").append(it.text) }
                    }
                }
                "finish" -> {
                    request.progress?.invoke(ToolGenerationProgress.VALIDATING)
                    val candidate = current ?: throw Feedback(noValidApp(draft))
                    if (!edited && !request.testFix) throw Feedback("Change the app before finish. The request asks for a change.")
                    if (edited) for (steps in reproductions) {
                        val outcome = smokeFor(candidate, request).probe { AppSteps.run(this, steps, everyStep = false) }
                        if (outcome.failure != null) return "finish: FAILED. The reproduced problem still fails: ${shown(outcome.failure, outcome, privateApp)}" +
                            (outcome.final?.takeUnless { privateApp }?.let { "\nScreen:\n${it.text}" } ?: "")
                    }
                    finish(reply, candidate, request, privateApp)
                }
                "describe_capability" -> {
                    val ids = reply.optJSONArray("ids") ?: throw Feedback("describe_capability needs ids.")
                    val known = CapabilityCatalog.definitions.associateBy { it.id }
                    "describe_capability: " + (0 until minOf(ids.length(), 6)).joinToString("\n") { index ->
                        val id = ids.optString(index)
                        known[id]?.json()?.apply { remove("title") }?.toString() ?: "$id is not available. Available: ${known.keys.joinToString()}"
                    }
                }
                else -> throw Feedback("Unknown tool \"$tool\". Use write_app, edit_app, run, describe_capability, or finish.")
            }
        }

        draft?.let { saved ->
            var smokeEvents = emptyList<String>()
            try {
                current = validate(saved) { smokeEvents = it }
            } catch (failure: Feedback) {
                // The saved app itself fails the host checks. Its failing smoke events are the reproduction.
                if (request.testFix) {
                    reproduced = true
                    reproductions += AppSteps.fromSmokeEvents(smokeEvents)
                }
                task += "\n\nThe current app fails the host check: ${failure.message}"
            }
        }

        for (step in 1..AppAgentGuide.MAX_STEPS) {
            checkActive()
            val turns = buildList {
                add(CloudTurn("user", task, request.images))
                history.forEachIndexed { index, turn ->
                    add(if (turn.role == "assistant" && index != lastWriteIndex) CloudTurn("assistant", compact(turn.text)) else turn)
                }
                if (!draftMatchesLastWrite && draft != null && isNotEmpty()) {
                    val last = removeAt(lastIndex)
                    add(last.copy(text = last.text + "\n\nCurrent app definition:\n" + modelView(checkNotNull(draft), privateApp)))
                }
            }
            val started = System.nanoTime()
            val reply = completeWithRetry(turns, instructions, session)
            history += CloudTurn("assistant", reply)
            val result = try {
                val parsed = parseReply(reply)
                handle(parsed)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Feedback) {
                val tool = runCatching { JSONObject(reply).optString("tool") }.getOrNull().orEmpty().ifEmpty { "reply" }
                lastProblem = failure.message.orEmpty()
                "$tool: REJECTED. ${failure.message}"
            }
            val tool = runCatching { JSONObject(reply).optString("tool") }.getOrNull().orEmpty()
            runCatching { request.onStep?.invoke(step, tool, reply, if (result is Finished) "finish: PASSED" else result.toString(), System.nanoTime() - started) }
            if (result is Finished) {
                val accepted = checkNotNull(current)
                return AppAgentResult(accepted.preview, result.summary, step, edited)
            }
            val remaining = AppAgentGuide.MAX_STEPS - step
            val footer = when {
                remaining == 0 -> ""
                remaining <= 3 -> "\n\n$remaining steps remain. Fix the app and call finish."
                else -> "\n\nStep $step of ${AppAgentGuide.MAX_STEPS} used."
            }
            history += CloudTurn("user", result.toString() + footer)
        }
        throw CloudFailure("widget_agent_incomplete",
            "Alphy could not finish this app in ${AppAgentGuide.MAX_STEPS} steps." +
                lastProblem.takeIf { it.isNotBlank() }?.let { " Last problem: ${it.take(300)}" }.orEmpty())
    }

    private suspend fun finish(reply: JSONObject, candidate: Candidate, request: AppAgentRequest, privateApp: Boolean): Any {
        val checks = reply.optJSONArray("checks") ?: throw Feedback("finish needs checks.")
        if (checks.length() !in 1..12) throw Feedback("Give 1 to 12 checks.")
        val parsed = (0 until checks.length()).map { index ->
            val check = checks.optJSONObject(index) ?: throw Feedback("Check ${index + 1} must be an object.")
            val name = check.optString("name").ifBlank { "check ${index + 1}" }.take(80)
            val steps = try { AppSteps.parse(check.opt("steps")) } catch (failure: AppStepError) {
                throw Feedback("check \"$name\": ${failure.message}")
            }
            if (steps.lastOrNull() !is AppStep.Expect) throw Feedback("check \"$name\" must end with an expectation after its last operation.")
            name to steps
        }
        val smoke = smokeFor(candidate, request)
        for ((name, steps) in parsed) {
            val outcome = smoke.probe { AppSteps.run(this, steps, everyStep = false) }
            if (outcome.failure != null) {
                return "finish: FAILED. check \"$name\": ${shown(outcome.failure, outcome, privateApp)}" +
                    (outcome.final?.takeUnless { privateApp }?.let { "\nScreen at the failure:\n${it.text}" } ?: "") +
                    "\nFix the app if it is wrong. Fix the check only if the expected value does not follow from the request."
            }
            outcome.final?.visibleText?.firstNotNullOfOrNull { longDecimal.find(it)?.value }?.let { value ->
                if (privateApp) return "finish: FAILED. check \"$name\": the screen shows a long decimal number. Round displayed numbers in the source."
                return "finish: FAILED. check \"$name\": the screen shows $value. Round displayed numbers in the source." +
                    "\nScreen:\n${outcome.final.text}"
            }
        }
        val summary = reply.optString("summary").trim().take(600)
        return Finished(summary)
    }

    /** Names the model's own keys or bind for a rejected node. The diagnostic itself holds only catalog terms. */
    private fun definitionHint(definition: JSONObject, diagnostic: ToolUiDiagnostic): String? {
        if (diagnostic.stage != ToolUiValidationStage.DEFINITION) return null
        var node: Any? = definition
        for (part in Regex("\\.([A-Za-z]+)|\\[(\\d+)]").findAll(diagnostic.path.removePrefix("$"))) {
            val (key, index) = part.destructured
            node = if (key.isNotEmpty()) (node as? JSONObject)?.opt(key) else (node as? JSONArray)?.opt(index.toInt())
        }
        return when (diagnostic.rule) {
            "component_properties" -> {
                val target = node as? JSONObject ?: return null
                val component = ToolUiCatalog.components[target.optString("type")] ?: return null
                val extra = target.keys().asSequence().filter { it !in component.properties }.toList().ifEmpty { return null }
                "This ${component.type} has unsupported properties: ${extra.joinToString()}. Allowed: ${component.properties.sorted().joinToString()}."
            }
            "component_binding_missing_path" -> (node as? String)?.let {
                "bind \"$it\" is not a field in both stateSchema and initialState. Declare it or bind an existing field."
            }
            else -> null
        }
    }

    private fun smokeFor(candidate: Candidate, request: AppAgentRequest) = ToolGenerationSmoke(sandbox, candidate.spec,
        request.artifactId, (request.base?.revision ?: 0) + 1, candidate.spec.initialState,
        checkActive = {}, generation = service, policy = request.policy)

    private suspend fun completeWithRetry(turns: List<CloudTurn>, instructions: String, session: CloudSession): String {
        var attempt = 0
        while (true) {
            try {
                return complete(turns, instructions)
            } catch (failure: CloudFailure) {
                // A blank completion is a provider glitch like a lost connection. The same request usually succeeds.
                val transient = failure.code in setOf("pre_headers_io", "pre_headers_timeout", "stream_io", "stream_timeout",
                    "widget_generation", "empty_response")
                if (!transient || ++attempt > 2) throw failure
                session.checkActive(currentCoroutineContext())
            }
        }
    }

    private fun parseReply(raw: String): JSONObject = try {
        JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    } catch (failure: JSONException) {
        // Only a position message is safe to quote. "Value X of type ..." repeats the reply itself.
        val reason = failure.message.orEmpty().takeIf { " at character " in it }?.substringBefore(" of ")?.take(120)
            ?: "the reply is not one JSON object"
        nativeCallReply(raw) ?: throw Feedback("Reply with one JSON object that names a tool. The JSON is invalid: $reason.")
    }

    /**
     * DeepSeek sometimes answers in its native call markup instead of JSON, and the opening tags can be missing.
     * The host reads the parameters it can see. It names an untagged first parameter and a missing tool by their shape.
     */
    private fun nativeCallReply(raw: String): JSONObject? {
        if (!nativeMarker.containsMatchIn(raw)) return null
        val reply = JSONObject()
        nativeInvoke.find(raw)?.let { reply.put("tool", it.groupValues[1]) }
        for (segment in nativeClose.split(raw).dropLast(1)) {
            val tag = nativeOpen.findAll(segment).lastOrNull()
            val body = (if (tag != null) segment.substring(tag.range.last + 1) else segment).trim()
            val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray }
            val name = tag?.groupValues?.get(1) ?: when (parsed) {
                is JSONArray -> if (parsed.optJSONObject(0)?.has("steps") == true) "checks" else "steps"
                is JSONObject -> if (parsed.has("ui") || parsed.has("source")) "app" else return null
                else -> return null
            }
            reply.put(name, if (tag?.groupValues?.get(2) == "true" || parsed == null) body else parsed)
        }
        if (!reply.has("tool")) reply.put("tool", when {
            reply.has("checks") -> "finish"
            reply.has("app") -> "write_app"
            reply.has("steps") -> "run"
            reply.has("set") || reply.has("source_edits") -> "edit_app"
            else -> return null
        })
        return reply
    }

    private fun objectArgument(reply: JSONObject, name: String): JSONObject = when (val value = reply.opt(name)) {
        is JSONObject -> value
        is String -> try { JSONObject(value) } catch (_: JSONException) { throw Feedback("$name must be a JSON object.") }
        else -> throw Feedback("$name must be a JSON object.")
    }

    private fun applyEdits(base: JSONObject, reply: JSONObject, uiLocked: Boolean): JSONObject {
        val next = JSONObject(base.toString())
        val set = reply.opt("set")?.let { it as? JSONObject ?: throw Feedback("set must be an object.") }
        val edits = reply.opt("source_edits")?.let { it as? JSONArray ?: throw Feedback("source_edits must be an array.") }
        if ((set == null || set.length() == 0) && (edits == null || edits.length() == 0)) throw Feedback("edit_app needs set or source_edits.")
        set?.keys()?.forEach { key ->
            if (key !in editableFields) throw Feedback("set cannot change \"$key\". Fields: ${editableFields.joinToString()}.")
            val value = set.get(key)
            if (value == JSONObject.NULL) next.remove(key) else next.put(key, value)
        }
        if (edits != null) {
            var source = next.optString("source")
            // The model often patches the ui as it wrote it. Compact JSON keeps its key order, so a copied node matches.
            val ui = next.optJSONObject("ui")?.toString().orEmpty()
            var nextUi = ui
            for (index in 0 until edits.length()) {
                val edit = edits.optJSONObject(index) ?: throw Feedback("source_edits[$index] must be an object.")
                val find = edit.optString("find")
                if (find.isEmpty() || !edit.has("replace")) throw Feedback("source_edits[$index] needs find and replace.")
                val inSource = occurrences(source, find)
                val inUi = if (uiLocked) 0 else occurrences(nextUi, find)
                when {
                    inSource == 1 && inUi == 0 -> source = source.replace(find, edit.getString("replace"))
                    inSource == 0 && inUi == 1 -> nextUi = nextUi.replace(find, edit.getString("replace"))
                    else -> throw Feedback("source_edits[$index].find matches ${inSource + inUi} times. " +
                        "It must match the current source${if (uiLocked) "" else " or the compact ui JSON"} exactly once.")
                }
            }
            next.put("source", source)
            if (nextUi != ui) next.put("ui", try { JSONObject(nextUi) } catch (_: JSONException) {
                throw Feedback("source_edits breaks the ui JSON. Replace whole JSON objects.")
            })
        }
        return next
    }

    private fun occurrences(text: String, part: String): Int {
        var count = 0
        var from = text.indexOf(part)
        while (from >= 0) {
            count++
            from = text.indexOf(part, from + 1)
        }
        return count
    }

    private fun noValidApp(draft: JSONObject?) = if (draft == null) "There is no app yet. Call write_app first."
        else "The current app did not pass validation. Fix it with edit_app or write_app first."

    private fun compact(reply: String): String {
        val json = runCatching { JSONObject(reply) }.getOrNull() ?: return reply.take(2000)
        if (json.has("app")) json.put("app", "(an earlier app version, omitted)")
        json.optJSONObject("set")?.let { set ->
            set.keys().asSequence().toList().forEach { key ->
                if (set.get(key).toString().length > 1500) set.put(key, "(omitted)")
            }
        }
        return json.toString()
    }

    private fun taskMessage(request: AppAgentRequest, previous: ToolSpec?, privateApp: Boolean, requestLanguage: String?): String = buildString {
        append("User request:\n").append(request.prompt.trim())
        val now = ZonedDateTime.now()
        append("\n\nToday is ").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd (EEEE), HH:mm", Locale.ENGLISH)))
            .append(", time zone ").append(now.zone.id).append('.')
        if (previous != null) {
            append("\n\nThis request changes the user's saved app. Change only what the request asks. ")
            append("Keep the other controls, texts, list columns, and layout as they are, also when you would design them differently. ")
            append("Keep every existing state field with its type so saved data stays. Give new fields initial values. ")
            append("Use edit_app for small changes.")
            previous.appLanguage?.let { append("\nThe app language is ").append(GenerationLanguage.name(it)).append('.') }
            if (privateApp) append("\n").append(PRIVATE_EDIT).append(' ').append(PRIVATE_SCREEN)
            append("\nCurrent app definition:\n").append(modelView(JSONObject(previous.providerDefinition()).apply { remove("formatVersion") }, privateApp))
        } else {
            append("\n\nBuild a new app. Write the interface in the language of the user's request text, unless that text names another language. ")
            append("The widget brief and reply excerpt can be in a different language. They do not set the interface language.")
            requestLanguage?.let { append(" The user's request text is in ").append(GenerationLanguage.name(it)).append('.') }
        }
    }

    private suspend fun capabilityGuidance(prompt: String): String {
        val ranked = try {
            // The request and brief come first. Source excerpts after them only add embedding time: about 0.1 s per 216 bytes.
            SemanticRuntime.get(sandbox.context).rank(prompt.take(1500), CapabilityCatalog.definitions,
                { "${it.title} (${it.id}). ${it.explanation}" }, cache = true)
                .filter { it.score >= 0.72f }.take(5).map { it.item.id }.toSet()
        } catch (_: SemanticUnavailable) {
            emptySet()
        }
        return "Device capabilities. Declare an ID in capabilities before an effect uses it. Use describe_capability for argument schemas.\n" +
            CapabilityCatalog.definitions.joinToString("\n") { "- ${it.id}: ${it.explanation.substringBefore(". ").take(140)}" } +
            (if (ranked.isEmpty()) "" else "\n" + CapabilityCatalog.modelGuidance(ranked, titles = false))
    }

    /** A private app keeps its layout and initial values out of model requests. */
    private fun modelView(definition: JSONObject, privateApp: Boolean): String =
        if (!privateApp) definition.toString() else JSONObject(definition.toString()).apply { remove("ui"); remove("initialState") }.toString()

    /** For a private app, a failed expectation does not show the actual value. */
    private fun shown(failure: String, outcome: AppSteps.Outcome, privateApp: Boolean): String =
        if (privateApp && outcome.failureKind == AppSteps.FailureKind.EXPECTATION) "step ${checkNotNull(outcome.failedStep) + 1}: the expectation did not pass"
        else failure

    /** An app failure, or a failed expectation whose value the user wrote, confirms a reported problem. */
    private fun confirmsProblem(steps: List<AppStep>, outcome: AppSteps.Outcome, prompt: String): Boolean =
        when (outcome.failureKind) {
            AppSteps.FailureKind.APP -> true
            AppSteps.FailureKind.EXPECTATION -> (steps[checkNotNull(outcome.failedStep)] as AppStep.Expect).let { expect ->
                val expected = (expect.text ?: expect.value?.let { if (it is Number) formatNumber(it) else it.toString() }).orEmpty().trim()
                expected.isNotEmpty() && prompt.contains(expected, ignoreCase = true)
            }
            else -> false
        }

    private fun formatNumber(value: Number): String = java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    /** First-screen problems that validation does not see: a placeholder word as a value, and grid buttons that look alike. */
    private fun presentationProblem(ui: JSONObject, state: JSONObject): String? {
        // A list row template repeats its buttons on purpose, so the walk follows children only.
        fun descendants(node: JSONObject): List<JSONObject> = listOf(node) + (node.optJSONArray("children")?.let { children ->
            (0 until children.length()).mapNotNull(children::optJSONObject).flatMap(::descendants)
        } ?: emptyList())
        val nodes = descendants(ui)
        for (node in nodes) {
            if (node.optString("type") !in setOf("metric", "text", "fact", "badge") || !node.has("bind")) continue
            val value = runCatching { ToolUiCatalog.boundText(node, state).trim() }.getOrNull() ?: continue
            if (value in placeholderValues) return "The value of \"${node.optString("text")}\" shows \"$value\". " +
                "When a value is unavailable, show a dash or a short message in the interface language."
        }
        for (grid in nodes.filter { it.optString("type") == "grid" }) {
            val buttons = descendants(grid).filter { it.optString("type") == "button" && it.has("action") }
            // Symbols such as + and − are usual in steppers. A word label must name its cell.
            val shared = buttons.groupBy { it.optString("text").trim() }.entries.firstOrNull { (label, same) ->
                label.any(Char::isLetter) && same.map { it.optString("action") }.distinct().size > 1
            } ?: continue
            return "Buttons with different actions in one grid share the label \"${shared.key}\". " +
                "Give each button a distinct label, such as its cell number or name, so the user can tell them apart."
        }
        return null
    }

    private companion object {
        const val REPRODUCE_FIRST = "Reproduce the reported problem with run on the current app before you change it."
        const val PRIVATE_EDIT = "This app keeps private device data. The host keeps its ui and initialState private and unchanged. " +
            "Use edit_app without ui or initialState."
        const val REVIEW_SCREEN = "Review this screen as the user: a calculator shows real results for sample inputs, not 0 or dashes, and no label or value repeats."
        const val PRIVATE_SCREEN = "The host shows no screen text for this private app."
        val editableFields = setOf("title", "description", "language", "capabilities", "stateSchema", "initialState", "ui", "source",
            "surfaces", "presentation", "hostControls", "capabilityResults", "collections")
        // Float artifacts such as 0.30000000000000004 have many digits. Conversion constants such as 0.621371 have six or fewer.
        private const val NATIVE = "<[\\uFF5C|]+\\s*DSML\\s*[\\uFF5C|]+\\s*"
        private val nativeMarker = Regex("[\\uFF5C|]+\\s*DSML\\s*[\\uFF5C|]+")
        private val nativeInvoke = Regex(NATIVE + "invoke name=\"([A-Za-z_]+)\"")
        private val nativeOpen = Regex(NATIVE + "parameter name=\"([A-Za-z_]+)\"(?:\\s+string=\"(true|false)\")?[^>]*>")
        private val nativeClose = Regex("</[\\uFF5C|]+\\s*DSML\\s*[\\uFF5C|]+\\s*parameter>")
        val scalarTypes = setOf("number", "string", "boolean")
        val placeholderValues = setOf("None", "null", "undefined", "NaN", "[object Object]")
        val longDecimal = Regex("\\d+\\.\\d{7,}")
    }
}
