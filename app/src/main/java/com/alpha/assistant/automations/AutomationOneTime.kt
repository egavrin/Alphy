package com.alpha.assistant.automations

import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

/** A foreground test authorizes one exact proposal and never changes the rule's approval. */
internal class AutomationOneTime(
    private val tools: ToolStore,
    private val charging: ()->Boolean?,
    private val accessFailure: (CapabilityRequest)->String?,
    private val readCalendar: suspend (CapabilityRequest)->CapabilityResult,
    private val render: suspend (ToolRecord,String)->ToolPreview,
    private val perform: suspend (CapabilityRequest,Authority)->CapabilityResult,
    private val clock: ()->Long = System::currentTimeMillis,
) {
    data class Request(val database: String,val ruleId: String,val revision: Int,val definition: String,val invalidation: String?)
    data class Step(val actionId: String,val capability: String,val target: String,val before: String?=null,val after: String?=null,
        val readAt: Long?=null,val from: Long?=null,val to: Long?=null,val uiChanged: Boolean=false,
        val toolId: String?=null,val toolRevision: Int?=null)
    internal data class Planned(val display: Step,val write: AutomationTools.TestWrite?=null,val frozenResult: String?=null)

    class Proposal internal constructor(val request: Request,val name: String,targets: Map<String,ToolRecord>,actions: List<Planned>) {
        val token=TOKEN_PREFIX+UUID.randomUUID()
        val steps: List<Step> = Collections.unmodifiableList(actions.map {it.display})
        internal val targets: Map<String,ToolRecord> = Collections.unmodifiableMap(LinkedHashMap(targets))
        internal val actions: List<Planned> = Collections.unmodifiableList(ArrayList(actions))
        private val lock=Any()
        private var state=State.READY
        private var job: Job?=null
        private enum class State { READY, CHECKING, CLAIMED, FINISHED, CANCELLED }

        fun cancel() {
            val running=synchronized(lock) {
                if(state==State.FINISHED || state==State.CANCELLED) return
                state=State.CANCELLED
                job
            }
            running?.cancel()
        }

        internal fun checkWith(job: Job): Boolean = synchronized(lock) {
            if(state!=State.READY) return false
            this.job=job
            state=State.CHECKING
            true
        }

        internal fun claim(block: ()->AutomationRun?): AutomationRun? = synchronized(lock) {
            if(state!=State.CHECKING) return null
            block()?.also {state=State.CLAIMED}
        }

        internal fun isClaimed() = synchronized(lock) {state==State.CLAIMED}
        internal fun isCancelled() = synchronized(lock) {state==State.CANCELLED}

        internal fun finish() = synchronized(lock) {
            if(state!=State.CANCELLED) state=State.FINISHED
            job=null
        }

        internal fun <T> commit(block: ()->T): T = synchronized(lock) {
            if(state!=State.CLAIMED) throw Stopped("cancelled","Проверка отменена. Остальные действия не выполнены.")
            block()
        }
    }

    class Stopped(val status: String,message: String): IllegalStateException(message)

    class Authority internal constructor(private val proposal: Proposal,private val job: Job,private val stop: ()->Stopped?) {
        fun stopped(): Stopped? = if(!job.isActive || !proposal.isClaimed()) Stopped("cancelled","Проверка отменена. Остальные действия не выполнены.") else stop()

        /** The lock covers the final local commit or startActivity call only. */
        fun <T> commit(block: ()->T): T = proposal.commit {
            stopped()?.let {throw it}
            block()
        }
    }

    suspend fun prepare(store: AutomationStore,request: Request): Proposal {
        val rule=current(store,request)
        val now=clock()
        checkConditions(rule,now)
        checkAccess(rule.definition)
        val targets=linkedMapOf<String,ToolRecord>()
        val predicted=mutableMapOf<String,ToolRecord>()
        val plan=rule.definition.actions.map {action ->
            currentCoroutineContext().ensureActive()
            when(action.capability) {
                "calendar.read","calendar.read.rolling" -> {
                    val resolved=AutomationDataflow.resolveRead(action,now)
                    val result=readCalendar(resolved)
                    val readAt=clock()
                    check(result.status=="succeeded") {result.message}
                    val exact=CapabilityResult.parse(result.json().toString())
                    val from=resolved.args.getLong("from")
                    val to=resolved.args.getLong("to")
                    val frozen=exact.copy(data=JSONObject(exact.data.toString()).put("range",resolved.args).put("readAt",readAt))
                    Planned(Step(action.id,action.capability,resolved.args.getLong("calendarId").toString(),
                        after=hideProviderIds(frozen).data.getJSONArray("events").toString(),readAt=readAt,from=from,to=to),
                        frozenResult=frozen.json().toString())
                }
                "tool.state.set" -> {
                    val id=action.args.getString("toolId")
                    val revision=action.args.getInt("revision")
                    val original=targets[id] ?: ArtifactCoordinator.get(tools,id).ordered {
                        AutomationTools.exactTarget(this,tools,id,revision)
                    }.also {targets[id]=it}
                    check(original.revision==revision) {"Версия мини-приложения изменилась."}
                    val write=AutomationTools.planTestWrite(predicted[id] ?: original,action,render)
                    predicted[id]=write.after
                    Planned(Step(action.id,action.capability,"${write.title} · ${write.field}",
                        displayValue(write.before,write.field),displayValue(write.after,write.field),uiChanged=write.before.ui!=write.after.ui,
                        toolId=id,toolRevision=revision),write)
                }
                else -> Planned(describeNative(action))
            }
        }
        current(store,request)
        validateTargets(targets)
        return Proposal(request,rule.definition.name,targets,plan)
    }

    suspend fun confirm(store: AutomationStore,proposal: Proposal,foreground: ()->Boolean = {true}): AutomationRun? {
        return try {
            coroutineScope {
                val job=checkNotNull(currentCoroutineContext()[Job])
                if(!proposal.checkWith(job)) return@coroutineScope null
                try {
                    val rule=current(store,proposal.request)
                    check(foreground()) {"Открой Alphy, чтобы выполнить проверку."}
                    val claim=validateTargets(proposal.targets) {
                        currentCoroutineContext().ensureActive()
                        check(foreground()) {"Открой Alphy, чтобы выполнить проверку."}
                        val now=clock()
                        checkConditions(rule,now)
                        checkAccess(rule.definition)
                        currentCoroutineContext().ensureActive()
                        store.claimOneTime(proposal,now)
                    } ?: return@coroutineScope null
                    val authority=Authority(proposal,job) {
                        when {
                            !foreground() -> Stopped("cancelled","Alphy свернута. Остальные действия не выполнены.")
                            store.oneTimeRule(proposal.request)==null -> Stopped("cancelled","Сценарий или его разрешение изменились.")
                            else -> firstAccessFailure(rule.definition)?.let {Stopped("revoked",it)}
                        }
                    }
                    val executor=AutomationExecutor(store,charging,{_,_->null}) {_,_,_->error("One-time execution requires its proposal.")}
                    executor.runOneTime(rule,claim,authority) {action ->
                        val item=proposal.actions.single {it.display.actionId==action.id}
                        when {
                            item.frozenResult!=null -> hideProviderIds(CapabilityResult.parse(item.frozenResult))
                            item.write!=null -> AutomationTools.commitTestWrite(tools,item.write,authority)
                            else -> perform(action,authority)
                        }
                    }
                } finally {proposal.finish()}
            }
        } catch(cancel: CancellationException) {
            currentCoroutineContext().ensureActive()
            if(!proposal.isCancelled()) throw cancel
            store.occurrence(proposal.request.ruleId,proposal.token)
        }
    }

    private fun checkConditions(rule: AutomationRule,at: Long) {
        val condition=rule.definition.conditionFailure(if(rule.definition.conditions.has("charging")) charging() else null,at)
        check(condition==null) {checkNotNull(condition)}
    }

    private fun firstAccessFailure(definition: AutomationDefinition): String? = try {
        definition.actions.firstNotNullOfOrNull(accessFailure)
    } catch(_: SecurityException) {"Android отозвал доступ. Проверь разрешения."}

    private fun checkAccess(definition: AutomationDefinition) {
        firstAccessFailure(definition)?.let {error(it)}
    }

    private suspend fun validateTargets(targets: Map<String,ToolRecord>) = validateTargets(targets) {}

    private suspend fun <T> validateTargets(targets: Map<String,ToolRecord>,block: suspend ()->T): T {
        val sorted=targets.values.sortedBy {it.id}
        suspend fun checkTarget(index: Int): T {
            val expected=sorted.getOrNull(index) ?: return block()
            return ArtifactCoordinator.get(tools,expected.id).ordered {
                check(AutomationTools.exactTarget(this,tools,expected.id,expected.revision)==expected) {
                    "Мини-приложение изменилось. Подготовь новую проверку."
                }
                checkTarget(index+1)
            }
        }
        return checkTarget(0)
    }

    private fun displayValue(record: ToolRecord,field: String): String {
        val value=JSONObject(record.state).get(field)
        if(value !is JSONArray) return value.toString()
        val visible=JSONArray()
        repeat(value.length()) {index -> visible.put(JSONObject(value.getJSONObject(index).toString()).apply {remove("id")})}
        return visible.toString()
    }

    private fun hideProviderIds(result: CapabilityResult): CapabilityResult {
        val visible=JSONObject(result.data.toString())
        val events=visible.getJSONArray("events")
        repeat(events.length()) {events.getJSONObject(it).remove("id")}
        return result.copy(data=visible)
    }

    private fun describeNative(action: CapabilityRequest): Step {
        val a=action.args
        return when(action.capability) {
            "notification.post" -> Step(action.id,action.capability,a.getString("title"),after=a.getString("text"))
            "app.open" -> Step(action.id,action.capability,a.getString("app"))
            "link.open" -> Step(action.id,action.capability,a.getString("url"))
            "maps.open" -> Step(action.id,action.capability,a.getString("query"))
            "calendar.create" -> Step(action.id,action.capability,a.getString("title"),after=a.optString("location").takeIf {it.isNotEmpty()},
                from=a.getLong("start"),to=a.getLong("end"))
            else -> error("Не удалось точно показать цель действия.")
        }
    }

    companion object {
        const val TOKEN_PREFIX="once:"

        fun request(store: AutomationStore,expected: AutomationRule): Request {
            val request=Request(store.databaseName,expected.id,expected.revision,expected.definition.json,store.approvalToken(expected.id))
            current(store,request)
            return request
        }

        private fun current(store: AutomationStore,request: Request) = checkNotNull(store.oneTimeRule(request)) {
            "Сценарий изменился, включён или удалён. Подготовь новую проверку выключенного сценария."
        }
    }
}
