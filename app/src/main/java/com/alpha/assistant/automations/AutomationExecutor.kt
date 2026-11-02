package com.alpha.assistant.automations

import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.capabilities.CapabilityResult
import org.json.JSONArray

/** No model, network client, or credentials are reachable from this executor. */
class AutomationExecutor(private val store: AutomationStore, private val charging: () -> Boolean?,
    private val accessFailure: (AutomationRule,CapabilityRequest) -> String?,
    private val preview: suspend (AutomationRule,CapabilityRequest,AutomationStep) -> CapabilityResult = { _,action,_ ->
        CapabilityResult("succeeded","Предполагаемое действие без выполнения.",org.json.JSONObject().put("preview",AutomationDataflow.describe(action)))
    },
    private val execute: suspend (AutomationRule,CapabilityRequest,AutomationStep) -> CapabilityResult) {
    suspend fun run(id: String, revision: Int, event: AutomationEvent, dry: Boolean = false, now: Long = System.currentTimeMillis()): AutomationRun? {
        val rule = store.rule(id) ?: return null
        if(rule.revision != revision || !rule.definition.matches(event)) return null
        val claim = store.begin(rule,event,now,dry) ?: return store.occurrence(id,event.token)
        store.prune(now)
        val condition = rule.definition.conditionFailure(charging(),now)
        if(condition != null) return store.finish(claim,"skipped",condition)
        if(dry && rule.approvedRevision != revision) return store.finish(claim,"dryRun","План без доступа к личным данным. Сначала одобри версию, затем проверь фактические изменения.",
            JSONArray(rule.definition.actions.map { it.json().put("status","succeeded").put("message","Только план").put("preview",true).put("data",org.json.JSONObject().put("preview",AutomationDataflow.describe(it))) }).toString())
        val results = JSONArray()
        val previous=linkedMapOf<String,CapabilityResult>()
        for(action in rule.definition.actions) {
            if(!dry && !store.active(id,revision)) return store.finish(claim,"cancelled","Сценарий выключен или изменён. Остальные действия отменены.",results.toString())
            val denied = accessFailure(rule,action)
            if(denied != null) {
                if(!dry) store.attention(id,revision,denied)
                return store.finish(claim,"revoked",denied,results.toString())
            }
            val key = "${claim.id}:${action.id}"
            val unknown = CapabilityResult("unknownOutcome","Выполнение начато. Автоматический повтор запрещён.")

            var result=unknown
            val maxAttempts=if(!dry && action.capability in setOf("calendar.read","calendar.read.rolling")) 2 else 1
            var attempts=0
            while(attempts < maxAttempts) {
                if(!dry && !store.active(id,revision)) return store.finish(claim,"cancelled","Сценарий выключен или изменён. Повтор отменён.",results.toString())
                val revoked=accessFailure(rule,action)
                if(revoked != null) {
                    if(!dry) store.attention(id,revision,revoked)
                    return store.finish(claim,"revoked",revoked,results.toString())
                }
                attempts++
                if(!dry) store.effect(claim.id,action.id,unknown.json().put("attempts",attempts).toString())
                val step=AutomationStep(key,now,previous.toMap(),dry)
                result = try {
                    if(dry) preview(rule,action,step) else execute(rule,action,step)
                }
                    catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch(_: SecurityException) { CapabilityResult("denied","Android отозвал доступ. Проверь разрешения.") }
                    catch(_: Exception) { if(dry) CapabilityResult("failed","Не удалось рассчитать изменения. Данные не изменены.") else unknown }
                if(!dry) store.effect(claim.id,action.id,result.json().put("attempts",attempts).toString())
                if(result.status !in setOf("unavailable","failed")) break
            }
            results.put(result.json().put("action",action.id).put("attempts",attempts).put("preview",dry))
            previous[action.id]=result
            if(result.status != "succeeded") {
                if(!dry && result.status in setOf("denied","revoked")) store.attention(id,revision,result.message)
                return store.finish(claim,result.status,result.message,results.toString())
            }
        }
        if(dry) return store.finish(claim,"dryRun","Предполагаемые изменения рассчитаны. Данные и внешние действия не изменены.",results.toString())
        return store.finish(claim,"succeeded","Все разрешённые действия завершены. Результаты сохранены только на устройстве.",results.toString())
    }
}
