package com.alpha.assistant.automations

import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.capabilities.CapabilityResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray

/** Native actions run here. Agent actions enqueue through the host. */
class AutomationExecutor(private val store: AutomationStore, private val charging: () -> Boolean?,
    private val accessFailure: (AutomationRule,CapabilityRequest) -> String?,
    private val preview: suspend (AutomationRule,CapabilityRequest,AutomationStep) -> CapabilityResult = { _,action,_ ->
        CapabilityResult("succeeded","Предполагаемое действие без выполнения.",org.json.JSONObject().put("preview",AutomationDataflow.describe(action)))
    },
    private val enqueueAgent: ((AutomationRule, AutomationEvent, Long) -> AutomationRun?)? = null,
    private val execute: suspend (AutomationRule,CapabilityRequest,AutomationStep) -> CapabilityResult) {
    suspend fun run(id: String, revision: Int, event: AutomationEvent, dry: Boolean = false, now: Long = System.currentTimeMillis()): AutomationRun? {
        if(event.token.startsWith(AutomationOneTime.TOKEN_PREFIX)) return null
        val rule = store.rule(id) ?: return null
        if(rule.revision != revision || !rule.definition.matches(event)) return null
        if (!dry && rule.definition.agent != null) return enqueueAgent?.invoke(rule, event, now)
        val claim = store.begin(rule,event,now,dry) ?: return store.occurrence(id,event.token)
        store.prune(now)
        val condition = rule.definition.conditionFailure(charging(),now)
        if(condition != null) return store.finish(claim,"skipped",condition)
        if(dry && rule.approvedRevision != revision) return store.finish(claim,"dryRun","План без доступа к личным данным. Сначала одобри версию, затем проверь фактические изменения.",
            JSONArray(rule.definition.actions.map { it.json().put("status","succeeded").put("message","Только план").put("preview",true).put("data",org.json.JSONObject().put("preview",AutomationDataflow.describe(it))) }).toString())
        return runClaim(rule,claim,now,dry)
    }

    private class OneTime(val authority: AutomationOneTime.Authority,val perform: suspend (CapabilityRequest)->CapabilityResult)

    internal suspend fun runOneTime(rule: AutomationRule,claim: AutomationRun,authority: AutomationOneTime.Authority,
        perform: suspend (CapabilityRequest)->CapabilityResult): AutomationRun {
        store.prune(claim.time)
        return runClaim(rule,claim,claim.time,false,OneTime(authority,perform))
    }

    private suspend fun runClaim(rule: AutomationRule,claim: AutomationRun,now: Long,dry: Boolean,oneTime: OneTime?=null): AutomationRun {
        val results = JSONArray()
        val previous=linkedMapOf<String,CapabilityResult>()
        fun stop(action: CapabilityRequest): AutomationRun? {
            if(oneTime!=null) return oneTime.authority.stopped()?.let {store.finish(claim,it.status,checkNotNull(it.message),results.toString())}
            if(!dry && !store.active(rule.id,rule.revision))
                return store.finish(claim,"cancelled","Сценарий выключен или изменён. Остальные действия отменены.",results.toString())
            val denied=accessFailure(rule,action) ?: return null
            if(!dry) store.attention(rule.id,rule.revision,denied)
            return store.finish(claim,"revoked",denied,results.toString())
        }
        for(action in rule.definition.actions) {
            val key = "${claim.id}:${action.id}"
            val unknown = CapabilityResult("unknownOutcome","Выполнение начато. Автоматический повтор запрещён.")

            var result=unknown
            val maxAttempts=if(!dry && oneTime==null && action.capability in setOf("calendar.read","calendar.read.rolling")) 2 else 1
            var attempts=0
            while(attempts < maxAttempts) {
                stop(action)?.let {return it}
                attempts++
                if(!dry) store.effect(claim.id,action.id,unknown.json().put("attempts",attempts).toString())
                val step=AutomationStep(key,now,previous.toMap(),dry)
                result = try {
                    when {
                        oneTime!=null -> oneTime.perform(action)
                        dry -> preview(rule,action,step)
                        else -> execute(rule,action,step)
                    }
                } catch(cancel: CancellationException) {
                    if(oneTime!=null) {
                        results.put(detail(unknown,action,attempts,dry))
                        store.finish(claim,unknown.status,unknown.message,results.toString())
                    }
                    throw cancel
                }
                catch(stopped: AutomationOneTime.Stopped) { CapabilityResult(stopped.status,checkNotNull(stopped.message)) }
                catch(_: SecurityException) { CapabilityResult("denied","Android отозвал доступ. Проверь разрешения.") }
                catch(_: Exception) { if(dry) CapabilityResult("failed","Не удалось рассчитать изменения. Данные не изменены.") else unknown }
                if(!dry) store.effect(claim.id,action.id,result.json().put("attempts",attempts).toString())
                if(result.status !in setOf("unavailable","failed")) break
            }
            results.put(detail(result,action,attempts,dry))
            previous[action.id]=result
            if(result.status != "succeeded") {
                if(!dry && oneTime==null && result.status in setOf("denied","revoked")) store.attention(rule.id,rule.revision,result.message)
                return store.finish(claim,result.status,result.message,results.toString())
            }
            if(oneTime!=null) store.finish(claim,claim.status,claim.message,results.toString())
        }
        if(dry) return store.finish(claim,"dryRun","Предполагаемые изменения рассчитаны. Данные и внешние действия не изменены.",results.toString())
        return store.finish(claim,"succeeded","Все разрешённые действия завершены. Результаты сохранены только на устройстве.",results.toString())
    }

    private fun detail(result: CapabilityResult,action: CapabilityRequest,attempts: Int,dry: Boolean) =
        result.json().put("action",action.id).put("attempts",attempts).put("preview",dry)
}
