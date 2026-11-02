package com.alpha.assistant.automations

object AutomationSchedule {
    const val MAX_LATENESS = 60L*60000
    suspend fun deliver(store: AutomationStore, rule: AutomationRule, at: Long, now: Long, executor: AutomationExecutor): AutomationRun? {
        if(!rule.definition.scheduled || rule.nextAt != at || at > now || !store.active(rule.id,rule.revision)) return null
        val event=AutomationEvent("schedule",rule.definition.occurrenceToken(at))
        val result=if(now-at > MAX_LATENESS) {
            store.begin(rule,event,now,false)?.let { store.finish(it,"missed","Запуск задержан более чем на 60 минут. Действия не выполнялись.") }
                ?: store.occurrence(rule.id,event.token)
        } else executor.run(rule.id,rule.revision,event,false,now)
        store.next(rule.id,rule.revision,null)
        return result
    }
}
