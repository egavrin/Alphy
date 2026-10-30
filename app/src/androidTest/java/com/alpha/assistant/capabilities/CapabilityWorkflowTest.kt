package com.alpha.assistant.capabilities

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.*
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CapabilityWorkflowTest {
    private fun spec(source: String = "if(input.event.type==='action'){input.state.items.push({id:String(input.state.items.length),text:'Milk',done:false});return {state:input.state,effects:[{id:'notice',capability:'notification.post',args:{title:'List',text:'Updated'}}]};}return {state:input.state};") = JSONObject()
        .put("formatVersion",2).put("title","Shopping").put("description","Typed list")
        .put("capabilities",JSONArray().put("notification.post"))
        .put("stateSchema",JSONObject("""{"items":{"type":"list","maxItems":100,"fields":{"id":"string","text":"string","done":"boolean"}}}"""))
        .put("initialState",JSONObject("""{"items":[]}"""))
        .put("ui",JSONObject("""{"type":"column","children":[{"type":"list","text":"Items","bind":"items"},{"type":"button","text":"Add","action":"add"}]}"""))
        .put("source",source).toString()

    @Test fun collectionsAndEffectsSurviveReopenAndRejectStaleOrDuplicateExecution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "capability-test-${UUID.randomUUID()}.db"
        var answer = spec()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { emit(CloudEvent.Text(answer)) }
        }
        val store = ToolStore(context,name)
        try {
            val engine = ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
            val draft = engine.createDraft("Shopping list",null)
            engine.generate(draft.id,"fixture",emptyList(),CloudSession())
            assertTrue(store.effects(draft.id).isEmpty())
            val live = engine.accept(draft.id)
            val changed = engine.interact(live.id,live.revision,live.stateVersion,"add",null)
            assertEquals("Milk",JSONObject(changed.state).getJSONArray("items").getJSONObject(0).getString("text"))
            val effect = store.effects(live.id).single()
            assertEquals("awaitingUser",effect.result.status)
            assertTrue(runCatching { engine.interact(live.id,live.revision,live.stateVersion,"add",null) }.isFailure)
            val broker = CapabilityBroker(context,store)
            var calls = 0
            val denied = broker.execute(effect.key,true) { calls++; CapabilityResult("succeeded","Posted") }
            assertEquals("denied",denied.status); assertEquals(0,calls)
            val second = engine.interact(changed.id,changed.revision,changed.stateVersion,"add",null)
            broker.grant(live.id,"notification.post",true)
            val next = store.effects(live.id).first { it.stateVersion == second.stateVersion }
            assertEquals("succeeded",broker.execute(next.key,true) { calls++; CapabilityResult("succeeded","Posted") }.status)
            assertEquals("succeeded",broker.execute(next.key,true) { calls++; CapabilityResult("succeeded","Posted") }.status)
            assertEquals(1,calls)
            broker.grant(live.id,"notification.post",false)
            ToolStore(context,name).use { reopened ->
                assertEquals(second,reopened.tool(live.id))
                assertEquals(2,JSONObject(reopened.tool(live.id)!!.state).getJSONArray("items").length())
                assertFalse(CapabilityBroker(context,reopened).granted(live.id,"notification.post"))
            }
            answer = JSONObject(spec()).put("stateSchema",JSONObject("""{"items":"string"}""")).put("initialState",JSONObject("""{"items":""}""")).put("ui",JSONObject("""{"type":"text","text":"Items","bind":"items"}""")).toString()
            val incompatible = engine.createDraft("Change schema",live.id)
            assertTrue(runCatching { engine.generate(incompatible.id,"fixture",emptyList(),CloudSession()) }.isFailure)
            assertEquals(second,store.tool(live.id))
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun invalidEffectsAndRecordsFailClosed() {
        val valid = ToolSpec.parse(spec())
        listOf(
            """{"items":[{"id":"a","text":"A","done":false},{"id":"a","text":"B","done":true}]}""",
            """{"items":[{"id":"a","text":"A","done":false,"extra":"x"}]}""",
            """{"items":[{"id":"a","text":"A","done":"yes"}]}"""
        ).forEach { assertTrue(runCatching { valid.validateState(it) }.isFailure) }
        fun rejected(json: String) = assertTrue(runCatching { CapabilityCatalog.parseEffects(JSONArray(json),setOf("notification.post"),"action") }.isFailure)
        rejected("""[{"id":"a","capability":"notification.post","args":{"title":"x","text":"y","grant":true}}]""")
        rejected("""[{"id":"a","capability":"calendar.read","args":{}}]""")
        rejected("""[{"id":"a","capability":"notification.post","args":{"title":"x","text":"y"}},{"id":"a","capability":"notification.post","args":{"title":"x","text":"y"}}]""")
        assertTrue(runCatching { CapabilityCatalog.parseEffects(JSONArray("""[{"id":"a","capability":"notification.post","args":{"title":"x","text":"y"}}]"""),valid.capabilities,"preview") }.isFailure)
        listOf("javascript:alert(1)","file:///etc/passwd","http://localhost/","https://127.0.0.1/").forEach { url ->
            assertTrue(runCatching { CapabilityCatalog.request(JSONObject().put("id","a").put("capability","link.open").put("args",JSONObject().put("url",url))) }.isFailure)
        }
        assertEquals("launched",CapabilityResult("launched","Opened calendar form").status)
        assertNotEquals("succeeded",CapabilityResult("unknownOutcome","External app closed").status)
    }

    @Test fun previewEffectsAndInvalidRuntimeNeverChangeData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "capability-preview-${UUID.randomUUID()}.db"
        var answer = spec("return {state:input.state,effects:[{id:'notice',capability:'notification.post',args:{title:'x',text:'y'}}]};")
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { emit(CloudEvent.Text(answer)) }
        }
        ToolStore(context,name).use { store ->
            val engine = ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
            val draft = engine.createDraft("Preview",null)
            assertTrue(runCatching { engine.generate(draft.id,"fixture",emptyList(),CloudSession()) }.isFailure)
            assertTrue(store.tools().isEmpty()); assertTrue(store.effects(draft.id).isEmpty())
            answer = spec()
            engine.generate(draft.id,"fixture",emptyList(),CloudSession())
            val live = engine.accept(draft.id)
            val changed = engine.interact(live.id,live.revision,live.stateVersion,"add",null)
            val pending = store.effects(live.id).single()
            engine.interact(changed.id,changed.revision,changed.stateVersion,"add",null)
            val broker = CapabilityBroker(context,store)
            broker.grant(live.id,"notification.post",true)
            assertEquals("cancelled",broker.execute(pending.key,true) { error("Stale effect executed") }.status)
            val newest = store.effects(live.id).last()
            assertEquals("cancelled",broker.execute(newest.key,false) { error("Cancelled effect executed") }.status)
        }
        context.deleteDatabase(name)
    }
}
