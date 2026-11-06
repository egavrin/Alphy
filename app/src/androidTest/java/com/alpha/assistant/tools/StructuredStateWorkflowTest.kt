package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure cases: schema ambiguity/limits, stale ownership/tokens/cursors, partial writes,
 * interrupted receipts, handler effects, transport overflow, privacy, and destructive upgrades. */
@RunWith(AndroidJUnit4::class)
class StructuredStateWorkflowTest {
    private fun spec(source: String = "return {state:input.state};"): ToolSpec = ToolSpec.parse(JSONObject("""{
      "formatVersion":3,"title":"Travel and medication","description":"Local records","capabilities":[],
      "collections":{"history":{"type":"object","fields":{"at":"number","dose":"string"}}},
      "stateSchema":{"trip":{"type":"object","fields":{"stops":{"type":"array","maxItems":8,"items":"string"},"at":"number"}},"board":{"type":"array","maxItems":9,"items":"number"},"count":"number"},
      "initialState":{"trip":{"stops":["Home","Clinic"],"at":1793577600000},"board":[0,1,0],"count":0},
      "ui":{"type":"button","text":"Save","action":"save"},"source":"placeholder"
    }""").put("source",source).toString())
    private fun tool(spec: ToolSpec, id: String = UUID.randomUUID().toString()) = ToolRecord(id,1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
    private fun operations(vararg json: String) = JSONArray().apply { json.forEach { put(JSONObject(it)) } }
    private fun put(key: String, token: String? = null) = """{"id":"save_$key","collection":"history","op":"put","key":"$key","expectedToken":${token?.let { "\"$it\"" } ?: "null"},"value":{"at":1793577600000,"dose":"5mg"}}"""
    private fun withStore(block: (ToolStore,String)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="structured-${UUID.randomUUID()}.db"; val store=ToolStore(context,name)
        try { block(store,name) } finally { store.close(); context.deleteDatabase(name); context.deleteDatabase("$name-automations.db") }
    }
    // Failure cases: list/array bounds, numeric token types, constructor extras, required IDs,
    // malformed names, nested/collection paths, field/depth/node budgets, and payload disclosure.
    @Test fun schemaFailuresIdentifyTheSafePathAndExactRuleWithoutChangingBounds() {
        fun candidate(schema: String, collections: String = "{}"): String = JSONObject(spec().json)
            .put("stateSchema",JSONObject(schema)).put("initialState",JSONObject().put("items",JSONArray()))
            .put("collections",JSONObject(collections)).toString()
        fun list(bound: String) = """{"items":{"type":"list","maxItems":17,"fields":{"id":"string"}}}"""
            .let { candidate(it).replace("\"maxItems\":17","\"maxItems\":$bound") }
        fun rejected(raw: String, rule: String, path: String, expected: String): String {
            val failure=runCatching {ToolSpec.parse(raw)}.exceptionOrNull()
            assertTrue(failure.toString(),failure is ToolValidationFailure)
            val message=failure!!.message.orEmpty()
            assertTrue(message,message.contains("Validation category: tool_state_schema."))
            assertTrue(message,message.contains("Schema rule: $rule."))
            assertTrue(message,message.contains("Path: $path."))
            assertTrue(message,message.contains("Expected: $expected."))
            assertTrue(message,message.contains("Observed:"))
            assertTrue(message.length <= 1400)
            assertFalse(message.contains("PRIVATE_PAYLOAD"))
            return message
        }
        for(bound in listOf("1","100")) ToolSpec.parse(list(bound))
        for(bound in listOf("0","101","200","\"100\"","100.0","1e2","null","true")) {
            val message=rejected(list(bound),"list.maxItems","stateSchema.items.maxItems","integer 1..100")
            if(bound == "200") assertTrue(message,message.contains("Observed: integer 200."))
        }
        ToolSpec.parse(candidate("""{"items":{"type":"array","maxItems":512,"items":"number"}}"""))
        rejected(candidate("""{"items":{"type":"array","maxItems":513,"items":"number"}}"""),"array.maxItems","stateSchema.items.maxItems","integer 1..512")
        rejected(candidate("""{"items":{"type":"object","fields":{"nested":{"type":"list","maxItems":200,"fields":{"id":"string"}}}}}"""),
            "list.maxItems","stateSchema.items.fields.nested.maxItems","integer 1..100")
        rejected(candidate("""{"items":"number"}""","""{"history":{"type":"array","maxItems":513,"items":"number"}}"""),
            "array.maxItems","collections.history.maxItems","integer 1..512")
        rejected(candidate("""{"items":{"type":"list","maxItems":100,"fields":{"id":"number"}}}"""),
            "list.id","stateSchema.items.fields.id","scalar string")
        rejected(candidate("""{"items":{"type":"list","maxItems":100,"fields":{"text":"string"}}}"""),
            "list.id","stateSchema.items.fields.id","scalar string")
        rejected(candidate("""{"items":{"type":"list","maxItems":100,"fields":{"id":"string","nested":{"type":"object","fields":{"value":"string"}}}}}"""),
            "list.field","stateSchema.items.fields.nested","scalar number, string, or boolean")
        rejected(candidate("""{"items":{"type":"list","maxItems":100,"fields":{"id":"string"},"PRIVATE_PAYLOAD":{"secret":"PRIVATE_PAYLOAD"}}}"""),
            "list.keys","stateSchema.items","exactly type, maxItems, fields")
        rejected(candidate("""{"items":{"type":"PRIVATE_PAYLOAD"}}"""),"schema.type","stateSchema.items.type","object, list, or array")
        rejected(candidate("""{"PRIVATE_PAYLOAD bad":{"type":"object","fields":{}}}"""),
            "schema.name","stateSchema[field#0]","ASCII letter followed by up to 47 letters, digits, or underscores; no prototype names")
        for(name in listOf("__proto__","prototype","constructor")) {
            rejected(candidate("""{"items":{"type":"object","fields":{"$name":"string"}}}"""),
                "schema.name","stateSchema.items.fields[field#0]","ASCII letter followed by up to 47 letters, digits, or underscores; no prototype names")
        }
        val wide=JSONObject(); repeat(33) {wide.put("f$it","number")}
        rejected(candidate(JSONObject().put("items",JSONObject().put("type","object").put("fields",wide)).toString()),
            "object.fields","stateSchema.items.fields","1..32 fields")
        var deep: Any="string"; repeat(7) {deep=JSONObject().put("type","array").put("maxItems",1).put("items",deep)}
        rejected(candidate(JSONObject().put("items",deep).toString()),"schema.depth",
            "stateSchema.items.items.items.items.items.items.items","depth at most 6")
        val nodes=JSONObject(); repeat(9) { index ->
            val fields=JSONObject(); repeat(32) {fields.put("f$it","number")}
            nodes.put("n$index",JSONObject().put("type","object").put("fields",fields))
        }
        val nodeFailure=runCatching {ToolSpec.parse(candidate(nodes.toString()))}.exceptionOrNull()!!
        assertTrue(nodeFailure.message.orEmpty(),nodeFailure.message.orEmpty().contains("Schema rule: schema.structuredNodes."))
        for(value in listOf("123456789","1234567890123")) {
            val malformed=listOf(
                Triple("""{"items":$value}""","schema.constructor","stateSchema.items"),
                Triple("""{"items":{"type":$value}}""","schema.type","stateSchema.items.type"),
                Triple("""{"items":{"type":"object","fields":$value}}""","object.fields","stateSchema.items.fields"),
                Triple("""{"items":{"type":"list","maxItems":100,"fields":{"id":$value}}}""","list.id","stateSchema.items.fields.id"),
                Triple("""{"items":{"type":"list","maxItems":100,"fields":{"id":"string","value":$value}}}""","list.field","stateSchema.items.fields.value"),
                Triple("""{"items":{"type":"array","maxItems":512,"items":$value}}""","schema.constructor","stateSchema.items.items")
            )
            malformed.forEach { (schema,rule,path) ->
                val message=runCatching {ToolSpec.parse(candidate(schema))}.exceptionOrNull()!!.message.orEmpty()
                assertTrue(message,message.contains("Schema rule: $rule."))
                assertTrue(message,message.contains("Path: $path."))
                assertTrue(message,message.contains("Observed: integer."))
                assertFalse(message,message.contains(value))
            }
        }
    }
    @Test fun nestedStateEpochRevisionAndRestart() = withStore { store,name ->
        val spec=spec(); val old=tool(spec); store.saveTool(old,true)
        assertEquals(1793577600000L,spec.validateState(old.state).getJSONObject("trip").getLong("at"))
        ToolStore(InstrumentationRegistry.getInstrumentation().targetContext,name).use { assertEquals(old,it.tool(old.id)) }
        val added=JSONObject(spec.json).apply {
            getJSONObject("stateSchema").put("extra","boolean"); getJSONObject("initialState").put("extra",true)
        }
        assertTrue(JSONObject(ToolSpec.stateForRevision(spec,ToolSpec.parse(added.toString()),old.state)).getBoolean("extra"))
        val removed=JSONObject(spec.json).apply { getJSONObject("collections").remove("history") }
        assertTrue(runCatching { ToolSpec.stateForRevision(spec,ToolSpec.parse(removed.toString()),old.state) }.isFailure)
        val list=JSONObject(spec.json).apply {
            getJSONObject("stateSchema").put("items",JSONObject("""{"type":"list","maxItems":2,"fields":{"id":"string","text":"string"}}"""))
            getJSONObject("initialState").put("items",JSONArray("""[{"id":"a","text":"A"}]"""))
        }
        val legacy=ToolSpec.parse(list.toString()); legacy.validateState(legacy.initialState)
        assertTrue(runCatching { legacy.validateState(JSONObject(legacy.initialState).put("items",JSONArray("""[{"id":"a","text":"A"},{"id":"a","text":"B"}]""")).toString()) }.isFailure)
    }
    @Test fun priorValidFlatListsKeepTheirStateAndRemainBounded() = withStore { store,name ->
        val schema=JSONObject(); val initial=JSONObject(); val full=JSONObject()
        repeat(30) { field ->
            val key="list$field"
            schema.put(key,JSONObject("""{"type":"list","maxItems":100,"fields":{"id":"string"}}"""))
            initial.put(key,JSONArray())
            full.put(key,JSONArray().apply {repeat(100) {put(JSONObject().put("id",it.toString()))}})
        }
        assertEquals(36021,full.toString().toByteArray().size)
        val original=JSONObject(spec().json).put("stateSchema",schema).put("initialState",initial)
        val definition=ToolSpec.parse(original.toString()); val saved=tool(definition).copy(state=full.toString())
        store.saveTool(saved,true); definition.validateState(saved.state)
        ToolStore(InstrumentationRegistry.getInstrumentation().targetContext,name).use { assertEquals(saved,it.tool(saved.id)); definition.validateState(it.tool(saved.id)!!.state) }
        assertEquals(saved.state,ToolSpec.stateForRevision(definition,definition,saved.state))
        val hostile=JSONObject(full.toString()); hostile.getJSONArray("list0").put(JSONObject().put("id","overflow"))
        assertTrue(runCatching {definition.validateState(hostile.toString())}.isFailure)
        val large=JSONObject(full.toString())
        repeat(100) {large.getJSONArray("list0").getJSONObject(it).put("id",it.toString().padStart(128,'x'))}
        // Use unique long IDs to isolate the byte budget rather than duplicate-ID validation.
        repeat(100) {large.getJSONArray("list1").getJSONObject(it).put("id",it.toString().padStart(128,'x'))}
        assertTrue(large.toString().toByteArray().size > 49152)
        assertTrue(runCatching {definition.validateState(large.toString())}.isFailure)
        val wide=JSONObject(); repeat(40) { index ->
            val fields=JSONObject().put("id","string"); repeat(11) {fields.put("v$it","number")}
            wide.put("list$index",JSONObject().put("type","list").put("maxItems",100).put("fields",fields))
        }
        val empty=JSONObject(); repeat(40) {empty.put("list$it",JSONArray())}
        ToolSpec.parse(JSONObject(original.toString()).put("stateSchema",wide).put("initialState",empty).toString())
    }
    @Test fun strictJsonRejectsCommentDepthBypassAndPreservesEscapes() {
        val attack="/*\"*/"+"{\"a\":".repeat(2000)+"0"+"}".repeat(2000)+"/*\"*/"
        assertTrue(attack.toByteArray().size < 65536)
        assertTrue(runCatching {SandboxContract.objectFrom(attack)}.exceptionOrNull() is IllegalArgumentException)
        for(raw in listOf("{a:1}","{\"a\":NaN}","{\"a\":1,}","{\"a\":1}//comment","{\"a\":'text'}","{\"a\":01}","{\"a\":true} trailing","{\"a\":1,\"\\u0061\":2}")) {
            assertTrue(raw,runCatching {SandboxContract.objectFrom(raw)}.isFailure)
        }
        val value="quote\" slash/ backslash\\ newline\n emoji \uD83D\uDE00"
        assertEquals(value,SandboxContract.objectFrom(JSONObject().put("text",value).toString()).getString("text"))
        assertTrue(runCatching {SandboxContract.objectFrom("{\"a\":"+"[".repeat(24)+"0"+"]".repeat(24)+"}")}.isFailure)
    }
    @Test fun terminalReceiptsRedactPayloadsAndStayBoundedAfterReadsAndDelete() = withStore { store,_ ->
        var live=tool(spec()); store.saveTool(live,true); val records=ToolRecords(store)
        fun act(ops: JSONArray): JSONObject {
            val receipt=records.commitAction(live,live.copy(stateVersion=live.stateVersion+1),ops)
            live=store.tool(live.id)!!; assertTrue(records.commitDelivery(receipt,live.copy(stateVersion=live.stateVersion+1)))
            live=store.tool(live.id)!!; assertFalse(records.commitDelivery(receipt,live.copy(stateVersion=live.stateVersion+1)))
            return JSONObject(receipt.event).getJSONArray("entries").getJSONObject(0)
        }
        val created=act(JSONArray().put(JSONObject(put("a")).apply {getJSONObject("value").put("dose","DELETE_PRIVATE_PAYLOAD")}))
        val token=created.getJSONObject("record").getString("token")
        repeat(140) {act(operations(if(it % 2 == 0) """{"id":"read","collection":"history","op":"get","key":"a"}""" else """{"id":"page","collection":"history","op":"page","order":"asc","limit":50,"cursor":null}"""))}
        act(operations("""{"id":"delete","collection":"history","op":"delete","key":"a","expectedToken":"$token"}"""))
        assertEquals("notFound",act(operations("""{"id":"read","collection":"history","op":"get","key":"a"}""")).getString("status"))
        store.readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(event)),0) FROM artifact_record_deliveries WHERE owner=?",arrayOf(live.id)).use {
            assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) <= 64); assertEquals(0,it.getInt(1))
        }
        val receipt=records.commitAction(live,live.copy(stateVersion=live.stateVersion+1),operations(put("rejected")))
        records.reject(receipt)
        store.readableDatabase.rawQuery("SELECT event,phase FROM artifact_record_deliveries WHERE delivery_id=?",arrayOf(receipt.id)).use {assertTrue(it.moveToFirst()); assertEquals("",it.getString(0)); assertEquals("rejected",it.getString(1))}
    }
    @Test fun schemaAndValueLimitsAndDuplicateKeys() {
        val spec=spec()
        for(name in listOf("__proto__","prototype","constructor")) {
            val bad=JSONObject(spec.json).apply { getJSONObject("stateSchema").getJSONObject("trip").getJSONObject("fields").put(name,"string") }
            assertTrue(name,runCatching { ToolSpec.parse(bad.toString()) }.isFailure)
        }
        assertTrue(runCatching { SandboxContract.objectFrom("""{"a":{"x":1,"x":2}}""") }.isFailure)
        val overflow=JSONObject(spec.initialState).put("board",JSONArray((0..9).toList()))
        assertTrue(runCatching { spec.validateState(overflow.toString()) }.isFailure)
        val badNumber=JSONObject(spec.initialState).put("count",9007199254740992L)
        assertTrue(runCatching { spec.validateState(badNumber.toString()) }.isFailure)
        val deep=JSONObject(spec.json); var node: Any="string"
        repeat(7) { node=JSONObject().put("type","array").put("maxItems",1).put("items",node) }
        deep.getJSONObject("stateSchema").put("deep",node)
        assertTrue(runCatching { ToolSpec.parse(deep.toString()) }.isFailure)
        val listBinding=JSONObject(spec.json).put("ui",JSONObject("""{"type":"list","text":"Bad","bind":"board"}"""))
        assertTrue(runCatching { ToolSpec.parse(listBinding.toString()) }.isFailure)
    }
    @Test fun schemaNodeFieldValueAndByteBudgets() {
        val original=spec()
        fun candidate(schema: JSONObject, state: JSONObject) = ToolSpec.parse(JSONObject(original.json).put("stateSchema",schema).put("initialState",state).toString())
        val fields=JSONObject(); val state=JSONObject()
        repeat(41) { fields.put("f$it","string"); state.put("f$it","") }
        assertTrue(runCatching {candidate(fields,state)}.isFailure)
        fields.remove("f40"); state.remove("f40")
        val forty=candidate(fields,state)
        assertTrue(runCatching {forty.validateState(JSONObject(state.toString()).put("f0","x".repeat(2049)).toString())}.isFailure)
        val large=JSONObject(state.toString()); repeat(30) {large.put("f$it","x".repeat(2048))}
        assertTrue(runCatching {forty.validateState(large.toString())}.isFailure)
        val nested=JSONObject(); repeat(33) {nested.put("f$it","number")}
        assertTrue(runCatching {candidate(JSONObject().put("obj",JSONObject().put("type","object").put("fields",nested)),JSONObject().put("obj",JSONObject()))}.isFailure)
        val nodes=JSONObject(); repeat(9) { index ->
            val children=JSONObject(); repeat(32) {children.put("v$it","number")}
            nodes.put("n$index",JSONObject().put("type","object").put("fields",children))
        }
        assertTrue(runCatching {candidate(nodes,JSONObject())}.isFailure)
        val array=JSONObject().put("type","array").put("maxItems",512).put("items","number")
        val grid=JSONObject().put("type","array").put("maxItems",8).put("items",array)
        val gridSpec=candidate(JSONObject().put("grid",grid),JSONObject().put("grid",JSONArray()))
        val rows=JSONArray(); repeat(8) {rows.put(JSONArray(List(512) {0}))}
        assertTrue(runCatching {gridSpec.validateState(JSONObject().put("grid",rows).toString())}.isFailure)
        array.put("maxItems",513)
        assertTrue(runCatching {candidate(JSONObject().put("bad",array),JSONObject().put("bad",JSONArray()))}.isFailure)
    }
    @Test fun cancellationOverflowAndCrashRetriesNeverReplayWrites() = withStore { store,_ ->
        val old=tool(spec()); store.saveTool(old,true); val records=ToolRecords(store)
        var guards=0
        assertTrue(runCatching {records.commitAction(old,old.copy(stateVersion=2),operations(put("a"))) {++guards < 2}}.isFailure)
        assertEquals(old,store.tool(old.id)); assertNull(records.pending(old.id))
        store.readableDatabase.rawQuery("SELECT count(*) FROM artifact_records WHERE owner=?",arrayOf(old.id)).use {assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0))}
        val operations=JSONArray()
        repeat(8) { index -> operations.put(JSONObject(put("r$index")).apply {getJSONObject("value").put("dose","x".repeat(2048))}) }
        assertTrue(runCatching {records.commitAction(old,old.copy(stateVersion=2),operations)}.isFailure)
        assertEquals(old,store.tool(old.id)); assertNull(records.pending(old.id))
        val receipt=records.commitAction(old,old.copy(stateVersion=2),operations(put("a")))
        val first=records.claimDelivery(old.id)!!; assertEquals(1,first.attempts)
        val second=records.claimDelivery(old.id)!!; assertEquals(2,second.attempts)
        assertFalse(records.commitDelivery(first,store.tool(old.id)!!.copy(stateVersion=3)))
        assertNull(records.claimDelivery(old.id))
        assertEquals(old.copy(stateVersion=2),store.tool(old.id))
        assertTrue(runCatching {records.commitAction(old,old.copy(stateVersion=2),operations(put("a")))}.isFailure)
        store.readableDatabase.rawQuery("SELECT count(*) FROM artifact_records WHERE owner=?",arrayOf(old.id)).use {assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0))}
        assertFalse(records.commitDelivery(receipt,store.tool(old.id)!!.copy(stateVersion=3)))
    }
    @Test fun recordsTokensOwnershipAtomicityAndDurableReceipt() = withStore { store,name ->
        val old=tool(spec()); store.saveTool(old,true); val records=ToolRecords(store)
        val next=old.copy(stateVersion=2)
        val receipt=records.commitAction(old,next,operations(put("a")))
        assertEquals(next,store.tool(old.id)); assertTrue(store.privateResults(old.id))
        val token=JSONObject(receipt.event).getJSONArray("entries").getJSONObject(0).getJSONObject("record").getString("token")
        assertTrue(runCatching { store.commitTool(next.copy(stateVersion=3),next,false) }.isFailure)
        assertTrue(records.commitDelivery(receipt,next.copy(stateVersion=3)))
        assertFalse(records.commitDelivery(receipt,next.copy(stateVersion=3)))
        val current=store.tool(old.id)!!
        assertTrue(runCatching { records.commitAction(current,current.copy(stateVersion=4),operations(put("a"))) }.isFailure)
        assertTrue(runCatching { records.commitAction(current,current.copy(stateVersion=4),operations(put("b"),put("a","0".repeat(64)))) }.isFailure)
        assertEquals(current,store.tool(old.id))
        val other=tool(spec()); store.saveTool(other,true)
        assertTrue(runCatching { records.commitAction(other,other.copy(stateVersion=2),operations(put("a",token))) }.isFailure)
        val updated=records.commitAction(current,current.copy(stateVersion=4),operations(put("a",token)))
        ToolStore(InstrumentationRegistry.getInstrumentation().targetContext,name).use { reopened ->
            val saved=ToolRecords(reopened).pending(old.id)!!
            assertEquals(updated,saved)
            assertTrue(ToolRecords(reopened).commitDelivery(saved,reopened.tool(old.id)!!.copy(stateVersion=5)))
        }
    }
    @Test fun cursorGenerationAndPageBounds() = withStore { store,_ ->
        var live=tool(spec()); store.saveTool(live,true); val records=ToolRecords(store)
        fun act(ops: JSONArray): JSONObject {
            val receipt=records.commitAction(live,live.copy(stateVersion=live.stateVersion+1),ops)
            live=store.tool(live.id)!!; assertTrue(records.commitDelivery(receipt,live.copy(stateVersion=live.stateVersion+1)))
            live=store.tool(live.id)!!; return JSONObject(receipt.event).getJSONArray("entries").getJSONObject(0)
        }
        act(operations(put("a"))); act(operations(put("b")))
        val page=act(operations("""{"id":"page","collection":"history","op":"page","order":"asc","limit":1,"cursor":null}"""))
        val cursor=page.getString("cursor"); assertEquals("a",page.getJSONArray("records").getJSONObject(0).getString("key"))
        act(operations(put("c")))
        val stale=act(operations("""{"id":"page","collection":"history","op":"page","order":"asc","limit":1,"cursor":"$cursor"}"""))
        assertEquals("staleCursor",stale.getString("status"))
        assertTrue(runCatching { act(operations("""{"id":"page","collection":"history","op":"page","order":"asc","limit":51,"cursor":null}""")) }.isFailure)
    }
    @Test fun unsupportedV7DatabasePreservesDataAndVersion() = withStore { store,name ->
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val path=context.getDatabasePath(name).path
        store.close()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use {
            it.execSQL("CREATE TABLE tools(id TEXT PRIMARY KEY,state TEXT)")
            it.execSQL("INSERT INTO tools VALUES('keep','PRESERVE_V7_STATE')")
            it.version=7
        }
        val failure=runCatching {ToolStore(context,name).use {it.readableDatabase}}.exceptionOrNull()
        assertTrue(failure.toString(),failure is ToolLibraryUnavailable)
        android.database.sqlite.SQLiteDatabase.openDatabase(path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(7,database.version)
            database.rawQuery("SELECT state FROM tools WHERE id='keep'",null).use {
                assertTrue(it.moveToFirst());assertEquals("PRESERVE_V7_STATE",it.getString(0))
            }
        }
    }

    @Test fun restoreKeepsNewSchemaDefaultsStateAndCollectionTokens() = withStore { store,name -> runBlocking {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val v1=spec()
            val v2=ToolSpec.parse(JSONObject(v1.json).apply {
                getJSONObject("stateSchema").put("extra","string")
                getJSONObject("initialState").put("extra","CURRENT_DEFAULT")
                put("ui",JSONObject("""{"type":"button","text":"Current button","action":"save"}"""))
            }.toString())
            val first=tool(v1)
            store.commitTool(first,null,true)
            val records=ToolRecords(store)
            val action=records.commitAction(first,first.copy(stateVersion=2),operations(put("a")))
            val token=JSONObject(action.event).getJSONArray("entries").getJSONObject(0).getJSONObject("record").getString("token")
            assertTrue(records.commitDelivery(action,first.copy(stateVersion=3)))
            val data=JSONObject(v2.initialState).put("extra","LIVE_VALUE").put("count",12).toString()
            val current=first.copy(revision=2,stateVersion=4,spec=v2.json,state=data,ui=v2.ui.toString())
            store.commitTool(current,store.tool(first.id),true)
            val offline=object: ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent)->Unit) = error("Restore stays local")
            }
            val engine=ToolEngine(store,ToolGenerationService(offline,SandboxClient(context)))
            val rule=JSONObject("""{"name":"Dependent writer","trigger":{"type":"manual"},"conditions":{},"actions":[{"id":"set","capability":"tool.state.set","args":{"toolId":"${current.id}","revision":2,"field":"count","value":9}}]}""")
            val stopped=com.alpha.assistant.automations.AutomationStore(context,"$name-automations.db").use { rules ->
                rules.save(null,rule).also { rules.approve(it.id,it.revision) }
            }
            val restored=engine.rollback(first.id,2,4,1)
            val metadata=store.revisions(first.id).first()
            assertEquals(listOf(StoppedAutomation(stopped.id,"Dependent writer")),metadata.stoppedAutomations)
            com.alpha.assistant.automations.AutomationStore(context,"$name-automations.db").use { rules ->
                val paused=rules.rule(stopped.id)!!
                assertFalse(paused.enabled)
                assertEquals(paused.attention,rules.history(stopped.id).single().message)
            }
            val definition=ToolSpec.parsePersisted(restored.spec)
            assertEquals(v1.source,definition.source);assertEquals(v1.ui.toString(),definition.ui.toString())
            assertEquals(v2.initialState,definition.initialState);assertEquals(v2.collections.toString(),definition.collections.toString())
            assertEquals(data,restored.state);assertEquals(3,restored.revision)
            val after=records.commitAction(restored,restored.copy(stateVersion=6),operations(put("a",token)))
            assertEquals("succeeded",JSONObject(after.event).getJSONArray("entries").getJSONObject(0).getString("status"))
    } }

    @Test fun restoreRejectsRenderEffectsStateChangesAndStaleSnapshots() = withStore { store,_ -> runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val offline=object: ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent)->Unit) = error("Restore stays local")
        }
        val engine=ToolEngine(store,ToolGenerationService(offline,SandboxClient(context)))
        val candidates=listOf(
            "return {state:input.state,effects:[{id:'buzz',capability:'vibration.play',args:{milliseconds:10}}]};",
            "return {state:input.state,records:[{id:'get',collection:'history',op:'get',key:'a'}]};",
            "input.state.count++; return {state:input.state};",
            "return {state:input.state,ui:{type:'invalid'}};"
        )
        for(source in candidates) {
            val previous=tool(ToolSpec.parse(JSONObject(spec(source).json).put("capabilities",JSONArray().put("vibration.play")).toString()))
            store.commitTool(previous,null,true)
            val good=spec()
            val current=previous.copy(revision=2,stateVersion=2,spec=good.json,ui=good.ui.toString())
            store.commitTool(current,previous,true)
            assertFalse(engine.restoreAvailability(current,1).available)
            assertTrue(runCatching {engine.rollback(current.id,2,2,1)}.isFailure)
            assertEquals(current,store.tool(current.id));assertEquals(2,store.revisions(current.id).size)
            assertTrue(store.effects(current.id).isEmpty());assertNull(ToolRecords(store).pending(current.id))
            val changed=current.copy(stateVersion=3)
            store.commitTool(changed,current,false)
            assertTrue(runCatching {engine.rollback(current.id,2,2,1)}.isFailure)
            assertEquals(changed,store.tool(current.id))
        }
    } }

    @Test fun publicRefinementUsesDeclaredUiAndKeepsLiveStateLocal() = withStore { store,_ -> runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val definition=ToolSpec.parse(JSONObject(spec().json).apply {
            remove("collections")
            getJSONObject("initialState").put("trip",JSONObject("""{"stops":["PUBLIC_DEFAULT"],"at":1}"""))
            getJSONObject("ui").put("text","DECLARED_BUTTON")
        }.toString())
        val first=tool(definition).copy(
            state=JSONObject(definition.initialState).put("trip",JSONObject("""{"stops":["LIVE_SENTINEL"],"at":1}""")).toString(),
            ui=JSONObject(definition.ui.toString()).put("text","RENDERED_SENTINEL").toString(),
            conversationId="original",lastConversationId="original"
        )
        store.commitTool(first,null,true)
        val requests=mutableListOf<CloudRequest>()
        val provider=object: ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent)->Unit) {
                requests.add(request);emit(CloudEvent.Text(definition.json))
            }
        }
        val engine=ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
        val draft=engine.createDraft("Change the button",first.id,"next")
        engine.generate(draft.id,"fixture",emptyList(),CloudSession())
        val current=engine.accept(draft.id)
        assertEquals("original",current.conversationId);assertEquals("next",current.lastConversationId)
        assertEquals(first.state,current.state)
        val turns=requests.flatMap {it.turns}.joinToString("\n") {it.text}
        assertTrue(turns.contains("DECLARED_BUTTON"));assertTrue(turns.contains("PUBLIC_DEFAULT"))
        assertFalse(turns.contains("RENDERED_SENTINEL"));assertFalse(turns.contains("LIVE_SENTINEL"))
        val saved=store.revisions(current.id).first()
        assertTrue(saved.label.isNotBlank());assertTrue(saved.createdAt>=first.createdAt)
    } }

    @Test fun artifactRecordCountAndByteQuotasRollbackTheWholeAction() = withStore { store,_ ->
        fun configured(bytes: Boolean): ToolSpec {
            val json=JSONObject(spec().json)
            val fields=if(bytes) JSONObject().apply {repeat(4) {put("dose$it","string")}} else JSONObject("""{"at":"number","dose":"string"}""")
            val collections=JSONObject(); repeat(3) {collections.put("history$it",JSONObject().put("type","object").put("fields",fields))}
            return ToolSpec.parse(json.put("collections",collections).toString())
        }
        fun seed(old: ToolRecord, bytes: Boolean): Int {
            var count=0; var total=0L
            val value=if(bytes) JSONObject().apply {repeat(4) {put("dose$it","x".repeat(1900))}} else JSONObject("""{"at":1793577600000,"dose":"x"}""")
            store.transaction {
                val statement=store.writableDatabase.compileStatement("INSERT INTO artifact_records VALUES(?,?,?,?,?,?)")
                try {
                    while(count < 20000) {
                        val key="record$count"; val token="a".repeat(64)
                        val size=JSONObject().put("key",key).put("token",token).put("value",value).toString().toByteArray().size
                        if(bytes && total+size > 20L*1024*1024) break
                        statement.clearBindings(); statement.bindString(1,old.id); statement.bindString(2,"history${count/10000}")
                        statement.bindString(3,key); statement.bindString(4,token); statement.bindString(5,value.toString()); statement.bindLong(6,size.toLong())
                        statement.executeInsert(); total+=size; count++
                    }
                } finally {statement.close()}
            }
            return count
        }
        for(bytes in listOf(false,true)) {
            val definition=configured(bytes); val old=tool(definition); store.saveTool(old,true)
            val count=seed(old,bytes); if(bytes) assertTrue(count in 2000..9999) else assertEquals(20000,count)
            val value=if(bytes) JSONObject().apply {repeat(4) {put("dose$it","x".repeat(2048))}} else JSONObject("""{"at":1793577600000,"dose":"x"}""")
            // The overflow record remains valid on its own, so only the artifact quota can reject the action.
            if(bytes) repeat(4) {value.put("dose$it","x".repeat(1950))}
            val op=JSONObject().put("id","overflow").put("collection",if(bytes) "history0" else "history2").put("op","put").put("key","overflow").put("expectedToken",JSONObject.NULL).put("value",value)
            assertTrue(JSONObject().put("key","overflow").put("token","a".repeat(64)).put("value",value).toString().toByteArray().size <= 8192)
            assertTrue(runCatching {ToolRecords(store).commitAction(old,old.copy(stateVersion=2),JSONArray().put(op))}.isFailure)
            assertEquals(old,store.tool(old.id)); assertNull(ToolRecords(store).pending(old.id))
            store.readableDatabase.rawQuery("SELECT count(*) FROM artifact_records WHERE owner=?",arrayOf(old.id)).use {assertTrue(it.moveToFirst()); assertEquals(count,it.getInt(0))}
        }
    }
    @Test fun engineRecoveryConsumesPendingReceiptOnceAndHandlersCannotRequestWork() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext; val name="record-recovery-${UUID.randomUUID()}.db"
        val provider=object: ChatProvider {override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent)->Unit) = error("Recovery must stay offline")}
        val source="if(input.event.type==='recordResults') input.state.count++; return {state:input.state};"
        val definition=spec(source); val old=tool(definition)
        try {
            ToolStore(context,name).use { store ->
                store.saveTool(old,true); val records=ToolRecords(store)
                records.commitAction(old,old.copy(stateVersion=2),operations(put("once")))
                assertEquals(1,records.claimDelivery(old.id)!!.attempts)
                val service=ToolGenerationService(provider,SandboxClient(context)); val event=JSONObject(records.pending(old.id)!!.event)
                for(output in listOf("records:[{id:'read',collection:'history',op:'get',key:'once'}]","effects:[{id:'post',capability:'notification.post',args:{title:'No',text:'No'}}]")) {
                    val bad=ToolSpec.parse(JSONObject(definition.json).put("capabilities",JSONArray().put("notification.post")).put("source","if(input.event.type==='recordResults') return {state:input.state,$output}; return {state:input.state};").toString())
                    assertTrue(runCatching {service.evaluate(bad,old.id,old.revision,old.state,event,old.ui)}.isFailure)
                }
                assertEquals(0,JSONObject(store.tool(old.id)!!.state).getInt("count")); assertTrue(store.effects(old.id).isEmpty())
            }
            ToolStore(context,name).use { reopened ->
                val engine=ToolEngine(reopened,ToolGenerationService(provider,SandboxClient(context)))
                reopened.resultOwners().forEach {engine.deliverCapabilityResults(it)}
                val delivered=reopened.tool(old.id)!!; assertEquals(1,JSONObject(delivered.state).getInt("count")); assertEquals(3,delivered.stateVersion)
                assertNull(ToolRecords(reopened).pending(old.id)); engine.deliverCapabilityResults(old.id); assertEquals(delivered,reopened.tool(old.id))
                reopened.readableDatabase.rawQuery("SELECT count(*) FROM artifact_records WHERE owner=?",arrayOf(old.id)).use {assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0))}
            }
        } finally {context.deleteDatabase(name)}
    }
    @Test fun tenThousandRecordPagesAndQuotaAreBounded() = withStore { store,_ ->
        var live=tool(spec()); store.saveTool(live,true); val records=ToolRecords(store)
        store.transaction {
            val statement=store.writableDatabase.compileStatement("INSERT INTO artifact_records VALUES(?,?,?,?,?,?)")
            try {
                repeat(10000) { index ->
                    statement.clearBindings(); statement.bindString(1,live.id); statement.bindString(2,"history")
                    statement.bindString(3,index.toString().padStart(5,'0')); statement.bindString(4,UUID.randomUUID().toString().replace("-","").repeat(2))
                    statement.bindString(5,"{\"at\":1793577600000,\"dose\":\"x\"}"); statement.bindLong(6,160); statement.executeInsert()
                }
            } finally {statement.close()}
        }
        var cursor: String?=null; var seen=0
        do {
            val op=JSONObject().put("id","page").put("collection","history").put("op","page").put("order","asc").put("limit",50).put("cursor",cursor ?: JSONObject.NULL)
            val receipt=records.commitAction(live,live.copy(stateVersion=live.stateVersion+1),JSONArray().put(op))
            val entry=JSONObject(receipt.event).getJSONArray("entries").getJSONObject(0)
            val rows=entry.getJSONArray("records"); assertTrue(rows.length() in 1..50)
            repeat(rows.length()) { assertEquals((seen++).toString().padStart(5,'0'),rows.getJSONObject(it).getString("key")) }
            assertTrue(entry.toString().toByteArray().size <= 8192)
            cursor=if(entry.isNull("cursor")) null else entry.getString("cursor")
            live=store.tool(live.id)!!; assertTrue(records.commitDelivery(receipt,live.copy(stateVersion=live.stateVersion+1))); live=store.tool(live.id)!!
        } while(cursor != null)
        assertEquals(10000,seen)
        assertTrue(runCatching {records.commitAction(live,live.copy(stateVersion=live.stateVersion+1),operations(put("overflow")))}.isFailure)
        assertEquals(live,store.tool(live.id))
        val desc=records.commitAction(live,live.copy(stateVersion=live.stateVersion+1),operations("""{"id":"page","collection":"history","op":"page","order":"desc","limit":1,"cursor":null}"""))
        assertEquals("09999",JSONObject(desc.event).getJSONArray("entries").getJSONObject(0).getJSONArray("records").getJSONObject(0).getString("key"))
    }
    @Test fun isolatedWorkflowPrivateRefinementAndNoHandlerEffects() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="structured-engine-${UUID.randomUUID()}.db"; val store=ToolStore(context,name)
        var capturedRequest: CloudRequest?=null
        var answer=spec("""if(input.event.type==='action')return {state:input.state,records:[{id:'save',collection:'history',op:'put',key:'a',expectedToken:null,value:{at:1793577600000,dose:'PRIVATE_MEDICATION'}}]}; if(input.event.type==='recordResults'){input.state.count=input.event.entries.length;} return {state:input.state};""").json
        val provider=object: ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent)->Unit) {
                if (request.replyWithFixturePlan(emit)) return
                capturedRequest=request; emit(CloudEvent.Text(answer))
            }
        }
        val service=ToolGenerationService(provider,SandboxClient(context)); val engine=ToolEngine(store,service)
        try {
            val draft=engine.createDraft("local tracker",null); engine.generate(draft.id,"fixture",emptyList(),CloudSession()); val old=engine.accept(draft.id)
            val live=engine.interact(old.id,old.revision,old.stateVersion,"save",null)
            assertEquals(1,JSONObject(live.state).getInt("count"))
            assertNull(ToolRecords(store).pending(old.id))
            answer=spec().json
            val refine=engine.createDraft("rename",old.id); engine.generate(refine.id,"fixture",emptyList(),CloudSession())
            val refinement=checkNotNull(capturedRequest).turns.first().text
            assertFalse(refinement.contains("Current state:"))
            assertFalse(refinement.contains("initialState\":{\"trip"))
            val bad=spec("return {state:input.state,records:[{id:'get',collection:'history',op:'get',key:'a'}]};")
            assertTrue(runCatching {service.evaluate(bad,"test",1,bad.initialState,JSONObject().put("type","render"),bad.ui.toString())}.isFailure)
            val input=JSONObject().put("artifactId","test").put("revision",1).put("state",JSONObject().put("large","x".repeat(65000))).put("event",JSONObject().put("type","recordResults").put("deliveryId",UUID.randomUUID().toString()).put("entries",JSONArray().put(JSONObject().put("large","y".repeat(1000)))))
            assertTrue(runCatching {SandboxContract.validateRequest("return {};",input.toString())}.isFailure)
        } finally {store.close(); context.deleteDatabase(name)}
    }
}
