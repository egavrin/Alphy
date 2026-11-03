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
    // Failure cases: catalog drift, executable planned entries, false success, discovery effects,
    // invalid arguments, revoked grants, unsupported actions, and stale picker results.
    @Test fun catalogDiscoveryAndBothModelPromptsUseOnlyBoundActions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val discovery = AndroidCapabilities(context).discover()
        val implemented = discovery.getJSONArray("implemented")
        assertEquals(CapabilityCatalog.ids, (0 until implemented.length()).map { implemented.getJSONObject(it).getString("id") }.toSet())
        assertEquals(CapabilityAction.entries.toSet(), CapabilityCatalog.definitions.map { it.action }.toSet())
        val planned = discovery.getJSONArray("planned")
        assertEquals(setOf("source.searchFetch","document.readShare","media.control","location.once","device.context","artifact.data"),
            CapabilityCatalog.planned.map { it.id }.toSet())
        assertEquals(CapabilityCatalog.planned.map { it.json().toString() },
            (0 until planned.length()).map { planned.getJSONObject(it).toString() })
        repeat(planned.length()) {
            val entry = planned.getJSONObject(it)
            assertFalse(entry.getBoolean("callable"))
            assertEquals("planned",entry.getString("implementation"))
            assertFalse(entry.has("argumentSchema"))
            assertFalse(entry.getString("id") in CapabilityCatalog.ids)
            assertTrue(runCatching { CapabilityCatalog.request(JSONObject().put("id","test").put("capability",entry.getString("id")).put("args",JSONObject())) }.isFailure)
        }
        repeat(implemented.length()) {
            val entry = implemented.getJSONObject(it)
            assertEquals("implemented",entry.getString("implementation"))
            assertTrue(entry.has("availability"))
            assertFalse(entry.getJSONObject("argumentSchema").getBoolean("additionalProperties"))
            assertTrue(entry.getJSONObject("access").getBoolean("toolGrant"))
            assertTrue(entry.getJSONObject("access").getBoolean("confirmation"))
            assertTrue(entry.has("result"))
        }
        val guidance = CapabilityCatalog.modelGuidance()
        val chatPrompt = ChatToolReply.instructions(emptyList())
        assertTrue(chatPrompt.contains(guidance))
        val name = "catalog-${UUID.randomUUID()}.db"
        var generationPrompt = ""
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                generationPrompt = request.turns.joinToString { it.text }
                emit(CloudEvent.Text(spec()))
            }
        }
        ToolStore(context,name).use { store ->
            val engine = ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
            val draft = engine.createDraft("Catalog fixture",null)
            engine.generate(draft.id,"fixture",emptyList(),CloudSession())
            assertTrue(generationPrompt.contains(guidance))
            listOf(chatPrompt,generationPrompt).forEach { prompt ->
                val callable = JSONArray(prompt.substringAfter("Alphy Android callable catalog (device availability is checked at execution): ")
                    .substringBefore(". Declare only these implemented IDs."))
                val callableIds = (0 until callable.length()).map { callable.getJSONObject(it).getString("id") }.toSet()
                assertEquals(CapabilityCatalog.ids,callableIds)
                CapabilityCatalog.planned.forEach { assertFalse(it.id in callableIds) }
            }
            assertTrue(store.effects(draft.id).isEmpty())
            val before = AndroidCapabilities(context).discover().toString()
            assertEquals(before,AndroidCapabilities(context).discover().toString())
            assertTrue(store.effects(draft.id).isEmpty())
        }
        context.deleteDatabase(name)
        assertEquals("launched",CapabilityCatalog.definition("calendar.create").resultStatus)
        assertEquals("launched",CapabilityCatalog.definition("maps.open").resultStatus)
        assertTrue(runCatching { CapabilityCatalog.request(JSONObject("""{"id":"x","capability":"unsupported.action","args":{}}""")) }.isFailure)
    }

    @Test fun pickerResultsCannotApplyAfterRevocationOrStateChange(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "catalog-picker-${UUID.randomUUID()}.db"
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                emit(CloudEvent.Text(spec().replace("notification.post","photo.pick")
                    .replace("args:{title:'List',text:'Updated'}","args:{}")))
            }
        }
        ToolStore(context,name).use { store ->
            val engine = ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
            val draft = engine.createDraft("Photo fixture",null)
            engine.generate(draft.id,"fixture",emptyList(),CloudSession())
            val live = engine.accept(draft.id)
            var current = engine.interact(live.id,live.revision,live.stateVersion,"add",null)
            val broker = CapabilityBroker(context,store)
            broker.grant(live.id,"photo.pick",true)
            val pending = store.effects(live.id).single()
            broker.execute(pending.key,true) { CapabilityResult("awaitingUser","Picker",JSONObject().put("launched",true)) }
            current = engine.interact(current.id,current.revision,current.stateVersion,"add",null)
            store.finishUserEffect(pending.key,CapabilityResult("succeeded","Selected",JSONObject().put("uri","content://fixture/photo")))
            assertEquals("cancelled",store.effect(pending.key)!!.result.status)
            val newest = store.effects(live.id).last()
            broker.execute(newest.key,true) { CapabilityResult("awaitingUser","Picker",JSONObject().put("launched",true)) }
            broker.grant(live.id,"photo.pick",false)
            store.finishUserEffect(newest.key,CapabilityResult("succeeded","Selected"))
            assertEquals("revoked",store.effect(newest.key)!!.result.status)
            assertFalse(store.effect(newest.key)!!.result.data.has("uri"))
            assertEquals(current,store.tool(live.id))
        }
        context.deleteDatabase(name)
    }
    private fun spec(source: String = "if(input.event.type==='action'){input.state.items.push({id:String(input.state.items.length),text:'Milk',done:false});return {state:input.state,effects:[{id:'notice',capability:'notification.post',args:{title:'List',text:'Updated'}}]};}return {state:input.state};") = JSONObject()
        .put("formatVersion",3).put("title","Shopping").put("description","Typed list")
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

    @Test fun previewEffectsAndInvalidRuntimeNeverChangeData(): Unit = runBlocking {
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
