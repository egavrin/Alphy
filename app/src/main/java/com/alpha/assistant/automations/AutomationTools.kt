package com.alpha.assistant.automations

import android.content.Context
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.*
import org.json.JSONObject

object AutomationTools {
    private fun localGeneration(context: Context): ToolGenerationService {
        val offline=object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { error("Автоматизации не обращаются к облаку") }
        }
        return ToolGenerationService(offline,SandboxClient(context))
    }
    fun renderer(context: Context,tools: ToolStore): suspend (ToolRecord,String) -> ToolPreview {
        val generation=localGeneration(context)
        return { tool,state ->
            ArtifactCoordinator.get(tools,tool.id).evaluate(generation,tool.copy(state=state),JSONObject().put("type","render"))
        }
    }
    internal fun isolatedRenderer(context: Context): suspend (ToolRecord,String)->ToolPreview {
        val generation=localGeneration(context)
        return {tool,state ->
            generation.evaluate(ToolSpec.parsePersisted(tool.spec),tool.id,tool.revision,state,JSONObject().put("type","render"),tool.ui)
        }
    }

    internal fun exactTarget(coordinator: ArtifactCoordinator,tools: ToolStore,id: String,revision: Int): ToolRecord {
        coordinator.ensureAvailable()
        val current=coordinator.current(tools,id,revision)
        check(current.saved && current==tools.tool(id) && !tools.artifactWorkPending(id)) {
            "У мини-приложения есть несохранённые изменения или незавершённое действие. Закрой его и повтори проверку."
        }
        return current
    }

    internal data class TestWrite(val before: ToolRecord,val after: ToolRecord,val field: String,val title: String,
        val surfaces: String?,val frames: Set<String>?,val timers: Map<String,Int>?,val playback: String?)

    internal suspend fun planTestWrite(old: ToolRecord,action: CapabilityRequest,
        render: suspend (ToolRecord,String)->ToolPreview): TestWrite {
        val spec=ToolSpec.parsePersisted(old.spec)
        val field=action.args.getString("field")
        require(spec.schema.get(field) is String) {"Новое значение не соответствует полю мини-приложения."}
        val state=nextState(spec,old,action)
        val rendered=render(old,state)
        require(rendered.effects.isEmpty() && rendered.records.length()==0 && JSONObject(rendered.state).toString()==JSONObject(state).toString()) {
            "Нельзя точно показать изменения мини-приложения. Проверка ничего не сохранила."
        }
        spec.validateState(rendered.state)
        spec.validateUi(JSONObject(rendered.ui),JSONObject(rendered.state))
        val next=old.copy(state=rendered.state,ui=rendered.ui,stateVersion=old.stateVersion+1)
        return TestWrite(old,next,field,spec.title,rendered.surfaces?.toString(),rendered.frames?.toSet(),rendered.timers?.toMap(),rendered.playback)
    }

    internal suspend fun commitTestWrite(tools: ToolStore,write: TestWrite,authority: AutomationOneTime.Authority): CapabilityResult =
        ArtifactCoordinator.get(tools,write.before.id).ordered {
            val expected=write.before
            val current=try {exactTarget(this,tools,expected.id,expected.revision)} catch(_: IllegalStateException) {null}
            if(current!=expected) {
                throw AutomationOneTime.Stopped("cancelled","Мини-приложение изменилось. Подготовь новую проверку.")
            }
            val output=prepareOutput(ToolPreview(ToolSpec.parsePersisted(write.after.spec),write.after.state,write.after.ui,
                surfaces=write.surfaces?.let(::JSONObject),frames=write.frames,timers=write.timers,playback=write.playback))
            authority.commit {
                tools.commitTool(write.after,write.before,false,autonomous=true)
                committed(write.after,output)
            }
            CapabilityResult("succeeded","Данные и интерфейс мини-приложения обновлены.")
        }

    private fun nextState(spec: ToolSpec,old: ToolRecord,action: CapabilityRequest): String {
        require(!spec.isSealedStandalone) {"Эти данные можно изменить только в мини-приложении."}
        return spec.validateState(JSONObject(old.state).put(action.args.getString("field"),action.args.get("value")).toString()).toString()
    }

    suspend fun execute(tools: ToolStore, action: CapabilityRequest, dry: Boolean,
        render: suspend (ToolRecord,String) -> ToolPreview, stillApproved: () -> Boolean): CapabilityResult = ArtifactCoordinator.get(tools,action.args.getString("toolId")).ordered {
        current(tools,action.args.getString("toolId"))
        if(!dry)flush(tools)
        val old=current(tools,action.args.getString("toolId"))
        check(!tools.artifactWorkPending(old.id)) {"A native result must finish before automation can change state."}
        check(!old.archived) { "Инструмент в архиве" }
        check(old.revision == action.args.getInt("revision")) { "Версия инструмента изменилась" }
        val spec=ToolSpec.parsePersisted(old.spec)
        val state=nextState(spec,old,action)
        val rendered=prepareOutput(render(old,state))
        require(rendered.effects.isEmpty() && JSONObject(rendered.state).toString() == JSONObject(state).toString()) { "Отображение изменило данные или запросило системное действие" }
        spec.validateState(rendered.state); spec.validateUi(JSONObject(rendered.ui),JSONObject(rendered.state))
        if(!dry) {
            check(stillApproved()) { "Сценарий выключен или изменён" }
            val next=old.copy(state=rendered.state,ui=rendered.ui,stateVersion=old.stateVersion+1)
            tools.commitTool(next,old,false,autonomous=true)
            committed(next,rendered)
        }
        val preview="${action.args.getString("field")} = ${action.args.get("value")}"
        CapabilityResult("succeeded",if(dry) "Изменения рассчитаны без сохранения." else "Данные и интерфейс инструмента обновлены.",JSONObject().put("preview",preview).put("state",JSONObject(state)).put("ui",JSONObject(rendered.ui)))
    }
}
