package com.alpha.assistant.agent

import android.app.SearchManager
import android.provider.MediaStore
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.alpha.assistant.R
import com.alpha.assistant.automations.AutomationDraftTool
import com.alpha.assistant.automations.AutomationResumeResult
import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.automations.AutomationTools
import com.alpha.assistant.automations.conditionText
import com.alpha.assistant.automations.triggerText
import com.alpha.assistant.capabilities.AndroidCapabilities
import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.surfaces.ToolWidgets
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.semantic.SemanticRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class AgentActions(private val context: Context, name: String, private val memory: MemoryStore, private val tools: ToolStore) {
    private val semantic = SemanticRuntime.get(context)
    private val android = AndroidCapabilities(context)
    private val calendar = AgentCalendar(context, name)

    fun permissions(command: AgentCommand): List<String> =
        if (AgentCommand.isNative(command.function)) listOfNotNull(android.permission(command.function))
        else CapabilityCatalog.phoneContract(command.function).permissions

    fun hasPermission(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    suspend fun resolve(command: AgentCommand, conversation: String): List<AgentTarget> {
        if (permissions(command).any { !hasPermission(it) }) return emptyList()
        val id = command.function
        val args = command.args()
        if (AgentCommand.isNative(id)) {
            if (android.availability(id).getString("state") != "available") return emptyList()
            val definition = CapabilityCatalog.definition(id)
            val after = when (id) {
                com.alpha.assistant.capabilities.PHOTO_CAPTURE -> {
                    val lens = com.alpha.assistant.capabilities.PhotoLens.requested(args)
                    if (com.alpha.assistant.capabilities.PhotoLens.present(context)?.contains(lens) == false) return emptyList()
                    text(lens.label)
                }
                "notification.post" -> args.getString("title") + "\n" + args.getString("text")
                "calendar.create" -> "${args.getString("title")} · ${time(args.getLong("start"))} – ${time(args.getLong("end"))}\n${args.optString("location")}"
                "flashlight.set" -> text(if (args.getBoolean("enabled")) R.string.agent_enabled else R.string.agent_disabled)
                "vibration.play" -> context.getString(R.string.agent_vibration, args.getInt("milliseconds"))
                "link.open" -> args.getString("url")
                "maps.open" -> args.getString("query")
                "phone.dial" -> args.getString("number")
                "share.text" -> args.getString("text")
                else -> args.getString("app")
            }
            return listOf(AgentTarget(definition.title, if (id == "flashlight.set") text(R.string.agent_unknown_state) else "", after, command.arguments))
        }
        return when (id) {
            "calendar.open" -> single(R.string.agent_calendar_open, text(R.string.agent_launch))
            "calendar.read", "calendar.updateEvent", "calendar.select", "calendar.createOwned", "calendar.readOwned",
            "calendar.deleteOwned", "calendar.deleteAllOwned" -> calendar.resolve(id, args)
            "clock.setAlarm" -> single(R.string.agent_alarm, String.format(Locale.ROOT, "%02d:%02d %s",
                args.getInt("hour"), args.getInt("minute"), args.optString("label")).trim())
            "clock.setTimer" -> single(R.string.agent_timer, context.getString(R.string.agent_seconds, args.getInt("seconds")) +
                " " + args.optString("label"))
            "music.playSearch" -> listOf(AgentTarget("Music search", "", args.getString("query"), command.arguments))
            "maps.route" -> single(R.string.agent_route, "${args.getString("from")} → ${args.getString("to")}")
            "memory.read" -> {
                val facts = memory.search(args.getString("match"), 0.75f, 50)
                listOf(AgentTarget(text(R.string.memory_title), "", facts.joinToString("\n") { it.text }.ifEmpty { text(R.string.agent_empty) },
                    JSONArray(facts.map { "${it.id}:${it.revision}" }).toString()))
            }
            "memory.write" -> if (!args.has("match")) single(R.string.memory_add, args.getString("text")) else
                memory.search(args.getString("match"), 0.75f, 50).map { fact ->
                    AgentTarget(text(R.string.memory_edit), fact.text, args.getString("text"), JSONArray(listOf(fact.id, fact.revision)).toString())
                }
            "artifact.setState" -> artifactTargets(args)
            "automation.create" -> {
                val draft = AutomationDraftTool.bind(args.getJSONObject("definition").toString(), conversation, now = 0)
                val summary = buildList {
                    add(draft.name)
                    add(if (draft.type == "interval") context.getString(R.string.agent_interval, draft.trigger.getInt("minutes")) else triggerText(draft))
                    if (draft.conditions.length() > 0) add(conditionText(draft))
                    draft.actions.forEach { action -> add(if (action.capability == "agent.run") action.args.getString("instruction")
                        else "${action.args.getString("title")} · ${action.args.getString("text")}") }
                    add(text(R.string.agent_rule_inactive))
                }
                single(R.string.automation_editor_create, summary.joinToString("\n"))
            }
            "automation.pause", "automation.resume" -> AutomationStore(context, tools.automationDatabase).use { store ->
                semantic.rank(args.getString("name"), store.rules(), { it.definition.name })
                    .filter { it.score >= 0.75f }.take(50).map { it.item }.map { rule ->
                    AgentTarget(rule.definition.name, text(if (rule.enabled) R.string.agent_enabled else R.string.agent_disabled),
                        text(if (id == "automation.resume") R.string.agent_enabled else R.string.agent_disabled),
                        JSONArray(listOf(rule.id, rule.revision, rule.approvedRevision, rule.enabled, rule.attention)).toString())
                }
            }
            else -> emptyList()
        }
    }

    suspend fun execute(command: AgentCommand, target: AgentTarget, conversation: String, active: suspend () -> Boolean,
        launch: suspend (Intent) -> Unit): CapabilityResult {
        if (!active()) return result("cancelled", R.string.agent_step_stopped)
        if (permissions(command).any { !hasPermission(it) }) return result("denied", R.string.capability_permission_required)
        val id = command.function
        val args = command.args()
        if (AgentCommand.isNative(id)) return android.execute(CapabilityRequest("agent", id, args), true, launch)
        return when (id) {
            "calendar.open" -> open(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                .appendPath(System.currentTimeMillis().toString()).build()), launch)
            "calendar.read", "calendar.updateEvent", "calendar.select", "calendar.createOwned", "calendar.readOwned",
            "calendar.deleteOwned", "calendar.deleteAllOwned" -> calendar.apply(id, args, target, active)
            "clock.setAlarm" -> open(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, args.getInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, args.getInt("minute")).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label")), launch)
            "clock.setTimer" -> open(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, args.getInt("seconds"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label")), launch)
            "music.playSearch" -> open(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, args.getString("query")), launch)
            "maps.route" -> open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1").appendQueryParameter("origin", args.getString("from"))
                .appendQueryParameter("destination", args.getString("to")).build()).addCategory(Intent.CATEGORY_BROWSABLE), launch)
            "memory.read" -> CapabilityResult("succeeded", target.after)
            "memory.write" -> {
                if (!args.has("match")) memory.saveExplicitFact(args.getString("text")) else {
                    val key = JSONArray(target.identity)
                    memory.edit(key.getString(0), key.getLong(1), args.getString("text"))
                }
                result("succeeded", R.string.agent_memory_saved)
            }
            "artifact.setState" -> applyArtifact(args, target, active)
            "automation.create", "automation.pause", "automation.resume" ->
                AutomationStore(context, tools.automationDatabase).use { store ->
                    if (id == "automation.create") {
                        val definition = AutomationDraftTool.bind(args.getJSONObject("definition").toString(), conversation)
                        store.save(null, JSONObject(definition.json))
                        AutomationRuntime.notifyChanged(context)
                        result("succeeded", R.string.agent_rule_inactive)
                    } else {
                        val key = JSONArray(target.identity)
                        val rule = store.rule(key.getString(0))?.takeIf {
                            it.revision == key.getInt(1) && it.approvedRevision == key.getInt(2) && it.enabled == key.getBoolean(3) &&
                                it.attention == key.getString(4)
                        } ?: return result("cancelled", R.string.agent_target_changed)
                        if (id == "automation.pause") {
                            if (!active()) return result("cancelled", R.string.agent_step_stopped)
                            if (AutomationRuntime.pause(context, store, rule)) result("succeeded", R.string.agent_disabled)
                            else result("cancelled", R.string.agent_target_changed)
                        } else if (AutomationRuntime.resume(context, store, rule, active) == AutomationResumeResult.RESUMED) {
                            result("succeeded", R.string.agent_enabled)
                        } else result("denied", R.string.agent_rule_review)
                    }
                }
            else -> result("unavailable", R.string.agent_unavailable)
        }
    }

    private suspend fun artifactTargets(args: JSONObject): List<AgentTarget> {
        val field = args.getString("field")
        val eligible = tools.tools().filter { it.saved && !it.archived }.mapNotNull { record ->
            val spec = ToolSpec.availablePersisted(record.spec) ?: return@mapNotNull null
            if (spec.isSealedStandalone || spec.schema.opt(field) !is String) return@mapNotNull null
            record to spec
        }
        return semantic.rank(args.getString("app"), eligible, { (_, spec) -> "${spec.title}\n${spec.description}" })
            .filter { it.score >= 0.75f }.take(50).map { it.item.first }.mapNotNull { candidate ->
                try {
                    ArtifactCoordinator.get(tools, candidate.id).ordered {
                        val current = AutomationTools.exactTarget(this, tools, candidate.id, candidate.revision)
                        val spec = ToolSpec.parsePersisted(current.spec)
                        require(!spec.isSealedStandalone && spec.schema.opt(field) is String)
                        spec.validateState(JSONObject(current.state).put(field, args.get("value")).toString())
                        AgentTarget(spec.title, "$field = ${JSONObject(current.state).get(field)}", "$field = ${args.get("value")}", artifactIdentity(current))
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
    }

    private suspend fun applyArtifact(args: JSONObject, target: AgentTarget, active: suspend () -> Boolean): CapabilityResult {
        val key = JSONArray(target.identity)
        val id = key.getString(0)
        val updated = ArtifactCoordinator.get(tools, id).ordered {
            if (!active()) return@ordered false
            val old = AutomationTools.exactTarget(this, tools, id, key.getInt(1))
            if (artifactIdentity(old) != target.identity) return@ordered false
            val spec = ToolSpec.parsePersisted(old.spec)
            val field = args.getString("field")
            require(!spec.isSealedStandalone && spec.schema.opt(field) is String)
            val state = spec.validateState(JSONObject(old.state).put(field, args.get("value")).toString())
            val rendered = prepareOutput(AutomationTools.isolatedRenderer(context)(old, state.toString()))
            require(rendered.effects.isEmpty() && rendered.records.length() == 0 && JSONObject(rendered.state).toString() == state.toString())
            spec.validateUi(JSONObject(rendered.ui), state)
            val next = old.copy(state = rendered.state, ui = rendered.ui, stateVersion = old.stateVersion + 1)
            if (!active()) return@ordered false
            tools.commitTool(next, old, false, autonomous = true)
            committed(next, rendered)
            true
        }
        if (!updated) return result("cancelled", R.string.agent_target_changed)
        if (tools.primaryLibrary) ToolWidgets.updateAll(context)
        return result("succeeded", R.string.agent_artifact_saved)
    }

    private fun artifactIdentity(record: ToolRecord) = JSONArray(listOf(record.id, record.revision, record.stateVersion)).toString()
    private fun single(label: Int, after: String) = listOf(AgentTarget(text(label), "", after, ""))
    private fun text(id: Int) = context.getString(id)
    private fun result(status: String, id: Int) = CapabilityResult(status, text(id))
    private suspend fun open(intent: Intent, launch: suspend (Intent) -> Unit): CapabilityResult = try {
        launch(intent)
        result("launched", R.string.agent_launched)
    } catch (_: ActivityNotFoundException) { result("unavailable", R.string.agent_no_handler) }

    private fun time(epoch: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epoch))
}
