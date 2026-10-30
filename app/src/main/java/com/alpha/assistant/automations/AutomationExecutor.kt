package com.alpha.assistant.automations

import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.capabilities.CapabilityResult
import org.json.JSONArray

/** No model, network client, or credentials are reachable from this executor. */
class AutomationExecutor(private val store: AutomationStore, private val charging: () -> Boolean?,
    private val accessFailure: (AutomationRule,CapabilityRequest) -> String?,
    private val execute: suspend (AutomationRule,CapabilityRequest,String) -> CapabilityResult) {
    suspend fun run(id: String, revision: Int, event: AutomationEvent, dry: Boolean = false, now: Long = System.currentTimeMillis()): AutomationRun? {
        val rule = store.rule(id) ?: return null
        if(rule.revision != revision || !rule.definition.matches(event)) return null
        val claim = store.begin(rule,event,now,dry) ?: return store.occurrence(id,revision,event.token)
        store.prune(now)
        val condition = rule.definition.conditionFailure(charging(),now)
        if(condition != null) return store.finish(claim,"skipped",condition)
        if(dry) {
            val access=rule.definition.actions.firstNotNullOfOrNull { accessFailure(rule,it) }
            return store.finish(claim,"dryRun",access ?: "Проверка без действий: триггер и условия приняты. Системные действия и данные не изменены.")
        }
        val results = JSONArray()
        for(action in rule.definition.actions) {
            if(!store.active(id,revision)) return store.finish(claim,"cancelled","Сценарий выключен или изменён. Остальные действия отменены.",results.toString())
            val denied = accessFailure(rule,action)
            if(denied != null) {
                store.attention(id,revision,denied)
                return store.finish(claim,"revoked",denied,results.toString())
            }
            val key = "${claim.id}:${action.id}"
            val unknown = CapabilityResult("unknownOutcome","Выполнение начато. Автоматический повтор запрещён.")
            store.effect(claim.id,action.id,unknown.json().toString())
            var result=unknown
            val maxAttempts=if(action.capability == "calendar.read") 2 else 1
            var attempts=0
            while(attempts < maxAttempts) {
                if(!store.active(id,revision)) return store.finish(claim,"cancelled","Сценарий выключен или изменён. Повтор отменён.",results.toString())
                val revoked=accessFailure(rule,action)
                if(revoked != null) {
                    store.attention(id,revision,revoked)
                    return store.finish(claim,"revoked",revoked,results.toString())
                }
                attempts++
                store.effect(claim.id,action.id,unknown.json().put("attempts",attempts).toString())
                result = try { execute(rule,action,key) }
                    catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch(_: SecurityException) { CapabilityResult("denied","Android отозвал доступ. Проверь разрешения.") }
                    catch(_: Exception) { unknown }
                store.effect(claim.id,action.id,result.json().put("attempts",attempts).toString())
                if(result.status !in setOf("unavailable","failed")) break
            }
            results.put(result.json().put("action",action.id).put("attempts",attempts))
            if(result.status != "succeeded") {
                if(result.status in setOf("denied","revoked")) store.attention(id,revision,result.message)
                return store.finish(claim,result.status,result.message,results.toString())
            }
        }
        return store.finish(claim,"succeeded","Все разрешённые действия завершены. Результаты сохранены только на устройстве.",results.toString())
    }
}
