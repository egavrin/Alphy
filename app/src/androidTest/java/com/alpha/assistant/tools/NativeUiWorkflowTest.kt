package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure cases: unknown/hidden properties, paths, payload types, stale keys, unsafe resources, and all size limits.
 * Native boundary tests isolate rejection before sandbox or device effects. Root acceptance covers rendered workflows.
 */
@RunWith(AndroidJUnit4::class)
class NativeUiWorkflowTest {
    private fun definition(ui: String, schema: String = "{\"count\":\"number\"}", state: String = "{\"count\":0}") = JSONObject()
        .put("formatVersion",2).put("title","Native fixture").put("description","Local UI fixture").put("capabilities",JSONArray())
        .put("stateSchema",JSONObject(schema)).put("initialState",JSONObject(state)).put("ui",JSONObject(ui))
        .put("source","return {state:input.state};").toString()
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    private fun validate(ui: String, schema: String = "{\"count\":\"number\"}", state: String = "{\"count\":0}") = ToolSpec.parse(definition(ui,schema,state))
    private fun action(spec: ToolSpec, id: String, value: Any?, state: String = spec.initialState) =
        ToolUiCatalog.validateAction(spec.ui,id,value,JSONObject(state))

    @Test fun existingCounterAndLegacyListKeepContracts() {
        val spec=validate("""{"type":"column","children":[{"type":"text","text":"Count","bind":"count"},{"type":"button","text":"Add","action":"add"}]}""")
        action(spec,"add",null); rejects {action(spec,"add",1)}
        val list=validate("""{"type":"list","text":"Items","bind":"items","action":"choose"}""",
            """{"items":{"type":"list","maxItems":100,"fields":{"id":"string","text":"string"}}}""",
            """{"items":[{"id":"a","text":"First"}]}""")
        action(list,"choose","a"); rejects {action(list,"choose","missing")}
    }
    @Test fun nestedPatientFormAndTypedActions() {
        val spec=validate(NativeUiFixtures.medicalUi,NativeUiFixtures.schema,NativeUiFixtures.state)
        action(spec,"name","Ada"); action(spec,"age",42); action(spec,"consent",true)
        action(spec,"visitTime",570); action(spec,"visitDate","2028-02-29"); action(spec,"dose",2.5)
        for((id,value) in listOf("age" to "42","age" to -1,"consent" to 1,"visitTime" to 1440,"visitTime" to 1.5,"visitDate" to "2027-02-29","visitDate" to "2028-02-29T00:00Z","dose" to Double.NaN)) rejects {action(spec,id,value)}
    }
    @Test fun pageSelectionAcceptsOnlyPresentStableKeysAndRelativeFields() {
        val spec=validate(NativeUiFixtures.medicationUi,NativeUiFixtures.schema,NativeUiFixtures.state)
        action(spec,"medication","m1"); rejects {action(spec,"medication","m2")}
        rejects {action(spec,"medication","m1",JSONObject(spec.initialState).put("medications",JSONArray()).toString())}
        rejects {validate(NativeUiFixtures.medicationUi.replace("value.name","value.secret"),NativeUiFixtures.schema,NativeUiFixtures.state)}
        rejects {validate(NativeUiFixtures.medicationUi,NativeUiFixtures.schema,JSONObject(NativeUiFixtures.state).apply {
            getJSONArray("medications").put(getJSONArray("medications").getJSONObject(0))
        }.toString())}
    }
    @Test fun pathsMustExistInSchemaAndStateWithoutPrototypeOrArrayAccess() {
        for(path in listOf("patient.missing","patient.__proto__","patient.name.x","medications.0","patient[name]","patient..name")) {
            rejects {validate("""{"type":"text","text":"Bad","bind":"$path"}""",NativeUiFixtures.schema,NativeUiFixtures.state)}
        }
        val spec=validate(NativeUiFixtures.medicalUi,NativeUiFixtures.schema,NativeUiFixtures.state)
        rejects {spec.validateUi(spec.ui,JSONObject(spec.initialState).apply {getJSONObject("patient").remove("name")})}
    }
    @Test fun hiddenAndDisabledAncestorsRejectForgedActionsButStillValidateDescendants() {
        for(prop in listOf("visible","enabled")) {
            val spec=validate("""{"type":"card","$prop":false,"children":[{"type":"button","text":"Save","action":"save"}]}""")
            rejects {action(spec,"save",null)}
            rejects {validate("""{"type":"card","$prop":false,"children":[{"type":"input","text":"Bad","bind":"missing","action":"bad"}]}""")}
        }
        rejects {validate("""{"type":"button","text":"Save","action":"save","enabled":"false"}""")}
    }
    @Test fun optionsAreTypedFiniteAndCurrent() {
        val spec=validate("""{"type":"select","text":"Mode","bind":"count","action":"mode","options":[{"text":"First","value":0},{"text":"Next","value":1}]}""")
        action(spec,"mode",1); rejects {action(spec,"mode","1")}; rejects {action(spec,"mode",2)}
        rejects {validate("""{"type":"tabs","text":"Bad","bind":"count","action":"mode","options":[{"text":"Bad","value":"1"}]}""")}
    }
    @Test fun travelTableAndChartsCoverEmptyConstantNegativeAndUnequalSeries() {
        validate(NativeUiFixtures.travelUi,NativeUiFixtures.schema,NativeUiFixtures.state)
        for(values in listOf("[]","[4,4,4]","[-4,-2,-8]","[-4,0,8]")) {
            val array=JSONArray(values); val categories=JSONArray().apply {repeat(array.length()) {put("Day ${it+1}")}}
            val state=JSONObject(NativeUiFixtures.state).put("categories",categories).put("distances",array).toString()
            val spec=validate(NativeUiFixtures.chartUi,NativeUiFixtures.schema,state)
            val range=ToolUiCatalog.chartRange(spec.ui,JSONObject(state)); assertTrue(range.first.isFinite() && range.second.isFinite() && range.second > range.first)
        }
        rejects {validate(NativeUiFixtures.chartUi,NativeUiFixtures.schema,JSONObject(NativeUiFixtures.state).put("distances",JSONArray("[1]")).toString())}
    }
    @Test fun tableAndChartLimitsRejectOverflowRatherThanOmit() {
        val table=JSONObject(NativeUiFixtures.travelUi)
        table.put("columns",JSONArray().apply {repeat(9) {put(JSONObject().put("text","Column $it").put("field","name"))}})
        rejects {validate(table.toString(),NativeUiFixtures.schema,NativeUiFixtures.state)}
        val chart=JSONObject(NativeUiFixtures.chartUi).put("series",JSONArray().apply {repeat(3) {put(JSONObject().put("text","Series $it").put("bind","distances"))}})
        rejects {validate(chart.toString(),NativeUiFixtures.schema,NativeUiFixtures.state)}
        val state=JSONObject(NativeUiFixtures.state).put("categories",JSONArray().apply {repeat(257) {put("$it")}}).put("distances",JSONArray().apply {repeat(257) {put(it)}})
        rejects {validate(NativeUiFixtures.chartUi,NativeUiFixtures.schema,state.toString())}
    }
    @Test fun structuralLimitsAndUnknownStyleAreStrict() {
        rejects {validate(JSONObject().put("type","column").put("children",JSONArray().apply {repeat(51) {put(JSONObject("""{"type":"text","text":"Child"}"""))}}).toString())}
        var deep=JSONObject("""{"type":"text","text":"Too deep"}"""); repeat(9) {deep=JSONObject().put("type","column").put("children",JSONArray().put(deep))}; rejects {validate(deep.toString())}
        val leaves=JSONArray().apply {repeat(50) {put(JSONObject().put("type","text").put("text","x".repeat(300)))}}
        val large=JSONObject().put("type","column").put("children",JSONArray().apply {repeat(3) {put(JSONObject().put("type","column").put("children",leaves))}})
        rejects {validate(large.toString())}
        rejects {validate("""{"type":"text","text":"Bad","style":{"url":"https://example.com"}}""")}
        for(type in listOf("Path","Canvas","Motion","Transform","Stage","Surface","Ticker","html")) rejects {validate("""{"type":"$type","text":"Unsupported"}""")}
    }
    @Test fun resourcesAcceptOnlyOpaqueHandlesAndWidgetsDoNotExposeRecords() {
        validate("""{"type":"image","text":"Selected photo","resourceId":"${"a".repeat(64)}"}""")
        rejects {validate("""{"type":"image","text":"Bad","resourceId":"https://example.com/a.png"}""")}
        rejects {validate("""{"type":"image","text":"Bad","resourceId":"file:///secret"}""")}
        val spec=validate(NativeUiFixtures.medicationUi,NativeUiFixtures.schema,NativeUiFixtures.state)
        val label=ToolUiCatalog.widgetLabel(spec.ui,JSONObject(spec.initialState))
        assertFalse(label.contains("PRIVATE_MEDICATION")); assertFalse(label.contains("1 records")); assertTrue(label.contains("Open"))
        assertEquals(ToolUiCatalog.Projection.SUMMARY,ToolUiCatalog.component("table").projection)
    }
    @Test fun expandedCollectionsOptionsAndImagesHaveAggregateBudgets() {
        val schema="""{"items":{"type":"array","maxItems":512,"items":{"type":"object","fields":{"id":"string","text":"string"}}}}"""
        val state=JSONObject().put("items",JSONArray().apply {repeat(512) {put(JSONObject().put("id","r$it").put("text","Row $it"))}}).toString()
        val list=JSONObject("""{"type":"list","text":"Rows","bind":"items"}""")
        validate(list.toString(),schema,state)
        val repeated=JSONObject().put("type","column").put("children",JSONArray().apply {
            repeat(5) {put(JSONObject().put("type","column").put("children",JSONArray().apply {repeat(50) {put(list)}}))}
        })
        rejects {validate(repeated.toString(),schema,state)}
        val options=JSONArray().apply {repeat(30) {put(JSONObject().put("text","Option $it").put("value",it))}}
        val tabs=JSONObject().put("type","column").put("children",JSONArray().apply {repeat(3) {group ->
            put(JSONObject().put("type","column").put("children",JSONArray().apply {repeat(30) {index ->
                put(JSONObject().put("type","tabs").put("text","Choices").put("bind","count").put("action","choice${group}_$index").put("options",options))
            }}))
        }})
        rejects {validate(tabs.toString())}
        val image=JSONObject().put("type","image").put("text","Photo").put("resourceId","a".repeat(64))
        validate(JSONObject().put("type","column").put("children",JSONArray().apply {repeat(4) {put(image)}}).toString())
        rejects {validate(JSONObject().put("type","column").put("children",JSONArray().apply {repeat(5) {put(image)}}).toString())}
    }
    @Test fun widgetsRedactNativeHandlesFromEveryScalarProjection() {
        val handle="a".repeat(64)
        val state=JSONObject().put("photo",handle)
        for(type in listOf("text","metric","badge")) {
            val node=JSONObject().put("type",type).put("text","Photo").put("bind","photo")
            val label=ToolUiCatalog.widgetLabel(node,state)
            assertFalse(label,label.contains(handle)); assertTrue(label,label.contains("Open"))
        }
        val embedded=JSONObject().put("type","text").put("text","Photo $handle").put("bind","photo")
        assertFalse(ToolUiCatalog.widgetLabel(embedded,state).contains(handle))
        val ordinary=JSONObject().put("type","text").put("text","Count").put("bind","count")
        assertEquals("Count: 4",ToolUiCatalog.widgetLabel(ordinary,JSONObject().put("count",4)))
    }
    // Row-action failures: duplicate IDs, unknown properties, invalid relative paths, disabled rows,
    // hidden/disabled parents, forged/removed keys, reordered rows, and expanded-button overflow.
    @Test fun rowActionsUseCurrentStableKeysAndDeclaredBooleanAvailability() {
        val spec=validate(ItineraryFixture.ui,ItineraryFixture.schema,ItineraryFixture.state)
        action(spec,"mapStop","stop0"); action(spec,"calendarStop","stop0"); action(spec,"selectStop","stop0")
        rejects {action(spec,"mapStop",0)}; rejects {action(spec,"mapStop","forged")}
        val state=JSONObject(spec.initialState); val rows=state.getJSONArray("stops")
        rows.getJSONObject(0).put("available",false)
        rejects {action(spec,"mapStop","stop0",state.toString())}
        action(spec,"calendarStop","stop0",state.toString())
        state.put("stops",JSONArray().put(rows.getJSONObject(1)).put(rows.getJSONObject(0)))
        action(spec,"calendarStop","stop0",state.toString())
        state.put("stops",JSONArray().put(rows.getJSONObject(1)))
        rejects {action(spec,"calendarStop","stop0",state.toString())}
        for(flag in listOf("visible","enabled")) {
            val parent=JSONObject().put("type","column").put(flag,false).put("children",JSONArray().put(spec.ui))
            rejects {ToolUiCatalog.validateAction(parent,"mapStop","stop0",JSONObject(spec.initialState))}
        }
        val label=ToolUiCatalog.widgetLabel(spec.ui,JSONObject(spec.initialState))
        assertFalse(label.contains("Stop 0")); assertTrue(label.contains("Open"))
        assertNotNull(spec.action(spec.ui,"mapStop"))
    }
    @Test fun rowActionIdsRequireStringsWithoutJsonCoercion() {
        for(id in listOf(true,false,JSONObject.NULL,1,1.5)) {
            val ui=JSONObject(ItineraryFixture.ui).put("rowActions",JSONArray().put(JSONObject().put("action",id).put("text","Action")))
            rejects {validate(ui.toString(),ItineraryFixture.schema,ItineraryFixture.state)}
        }
        for(id in listOf("mapStop","true","null")) {
            val ui=JSONObject(ItineraryFixture.ui).put("rowActions",JSONArray().put(JSONObject().put("action",id).put("text","Action")))
            val spec=validate(ui.toString(),ItineraryFixture.schema,ItineraryFixture.state)
            action(spec,id,"stop0")
        }
    }
    @Test fun rowActionSchemaAndExpandedBudgetAreStrict() {
        for(actions in listOf(
            "[{\"action\":\"mapStop\",\"text\":\"Map\",\"emphasis\":\"danger\"}]",
            "[{\"action\":\"mapStop\",\"text\":\"Map\",\"enabledField\":\"name\"}]",
            "[{\"action\":\"mapStop\",\"text\":\"Map\",\"enabledField\":\"missing\"}]",
            "[{\"action\":\"mapStop\",\"text\":\"Map\"},{\"action\":\"mapStop\",\"text\":\"Again\"}]",
            "[]"
        )) rejects {validate(JSONObject(ItineraryFixture.ui).put("rowActions",JSONArray(actions)).toString(),ItineraryFixture.schema,ItineraryFixture.state)}
        val actions=JSONArray().apply {repeat(4) {put(JSONObject().put("action","row$it").put("text","Action $it"))}}
        rejects {validate(JSONObject(ItineraryFixture.ui).put("rowActions",actions).toString(),ItineraryFixture.schema,ItineraryFixture.state)}
        val state=JSONObject(ItineraryFixture.state).put("stops",ItineraryFixture.rows(512)).toString()
        rejects {validate(ItineraryFixture.ui,ItineraryFixture.schema,state)}
        val withoutActions=JSONObject(ItineraryFixture.ui).apply {remove("rowActions")}
        validate(withoutActions.toString(),ItineraryFixture.schema,state)
        val collision=JSONObject().put("type","column").put("children",JSONArray().put(JSONObject(ItineraryFixture.ui))
            .put(JSONObject("""{"type":"button","text":"Collision","action":"mapStop"}""")))
        rejects {validate(collision.toString(),ItineraryFixture.schema,ItineraryFixture.state)}
    }
    @Test fun allNativeWorkflowJUnitMethodsReturnVoid() {
        javaClass.declaredMethods.filter {it.isAnnotationPresent(Test::class.java)}.forEach {assertEquals(it.name,Void.TYPE,it.returnType)}
    }
    // Diagnostics can fail through private values, incorrect categories, altered retries, or observer exceptions.
    @Test fun uiDiagnosticsPreserveFailuresAndExposeOnlyStructuralLocations() {
        val secret="SECRET_SENTINEL"
        var tooDeep=JSONObject().put("type","divider")
        repeat(9) {tooDeep=JSONObject().put("type","column").put("children",JSONArray().put(tooDeep))}
        val tooMany=JSONObject().put("type","column").put("children",JSONArray().apply {repeat(6) {
            put(JSONObject().put("type","column").put("children",JSONArray().apply {repeat(43) {put(JSONObject().put("type","divider"))}}))
        }})
        val tooManyImages=JSONObject().put("type","column").put("visible",false).put("children",JSONArray().apply {repeat(5) {
            put(JSONObject().put("type","image").put("text",secret).put("resourceId","a".repeat(64)))
        }})
        val cases=listOf(
            """{"type":"$secret","text":"$secret"}""" to ToolValidationCategory.UI_STRUCTURE,
            """{"type":"text","text":"$secret","$secret":"$secret"}""" to ToolValidationCategory.UI_PROPERTIES,
            """{"type":"column","visible":false,"children":[{"type":"text","text":"$secret","bind":"$secret"}]}""" to ToolValidationCategory.UI_BINDINGS,
            """{"type":"button","text":"$secret","action":"$secret!"}""" to ToolValidationCategory.UI_ACTIONS,
            """{"type":"text","text":42}""" to ToolValidationCategory.UI_PROPERTIES,
            """{"type":"column","children":[]}""" to ToolValidationCategory.UI_STRUCTURE,
            """{"type":"column","visible":false,"children":["$secret"]}""" to ToolValidationCategory.UI_STRUCTURE,
            """{"type":"image","text":"$secret","resourceId":"content://$secret"}""" to ToolValidationCategory.UI_STRUCTURE,
            """{"type":"text","text":"$secret","style":{"$secret":"$secret"}}""" to ToolValidationCategory.UI_PROPERTIES,
            tooDeep.toString() to ToolValidationCategory.UI_STRUCTURE,
            tooMany.toString() to ToolValidationCategory.UI_STRUCTURE,
            tooManyImages.toString() to ToolValidationCategory.UI_STRUCTURE,
            JSONObject().put("type","column").put("children",JSONArray().apply {repeat(51) {put(JSONObject().put("type","divider"))}}).toString() to ToolValidationCategory.UI_STRUCTURE,
            JSONObject().put("type","text").put("text",secret.repeat(3000)).toString() to ToolValidationCategory.UI_STRUCTURE
        )
        for((ui,category) in cases) {
            val raw=JSONObject(definition(ui)).put("source","/* $secret */ return {state:input.state};").toString()
            val ordinary=runCatching {ToolSpec.parse(raw)}.exceptionOrNull() as ToolValidationFailure
            val diagnostics=mutableListOf<ToolUiDiagnostic>()
            val observed=runCatching {ToolSpec.parse(raw,diagnostics::add)}.exceptionOrNull() as ToolValidationFailure
            assertEquals(category,observed.category); assertEquals(ordinary.message,observed.message)
            assertEquals(1,diagnostics.size)
            val diagnostic=diagnostics.single()
            assertEquals(ToolUiValidationStage.DEFINITION,diagnostic.stage)
            assertEquals(category,diagnostic.category)
            assertTrue(diagnostic.path.startsWith("$.ui")); assertFalse(diagnostic.toString().contains(secret))
            val throwing=runCatching {ToolSpec.parse(raw) {throw CancellationException(secret)}}.exceptionOrNull()
            assertEquals(ordinary.message,throwing?.message)
        }
        val diagnostics=mutableListOf<ToolUiDiagnostic>()
        val spec=validate("""{"type":"text","text":"$secret","bind":"count"}""")
        rejects {spec.validateUi(spec.ui,JSONObject().put("count",secret),ToolUiValidationStage.STATE,diagnostics::add)}
        assertEquals(ToolUiValidationStage.STATE,diagnostics.single().stage)
        assertFalse(diagnostics.toString().contains(secret))
        assertEquals("$.ui.bind",diagnostics.single().path)
        val rows=JSONArray().apply {repeat(512) {put(JSONObject().put("id","row$it").put("text",secret))}}
        val lists=JSONObject().put("type","column").put("children",JSONArray().apply {repeat(3) {
            put(JSONObject().put("type","list").put("text",secret).put("bind","items"))
        }})
        val raw=definition(lists.toString(),"""{"items":{"type":"array","maxItems":512,"items":{"type":"object","fields":{"id":"string","text":"string"}}}}""",JSONObject().put("items",rows).toString())
        diagnostics.clear()
        val ordinary=runCatching {ToolSpec.parse(raw)}.exceptionOrNull() as ToolValidationFailure
        val observed=runCatching {ToolSpec.parse(raw,diagnostics::add)}.exceptionOrNull() as ToolValidationFailure
        assertEquals(ordinary.message,observed.message); assertEquals(ToolValidationCategory.UI_STRUCTURE,observed.category)
        assertEquals("render_work_limit",diagnostics.single().rule); assertEquals("$.ui.children[1]",diagnostics.single().path)
        assertFalse(diagnostics.toString().contains(secret))
    }
    // Failure cases: lost snapshots, private payloads in feedback, missing labels, and invalid preserved date values.
    @Test fun labelAndDateFailuresCarrySafeSnapshotsWithoutAnObserver() {
        val secret="SECRET_UI_PAYLOAD"
        var icon=JSONObject().put("type","icon").put("name","Plus").put("action","secretAction")
        repeat(4) {icon=JSONObject().put("type","column").put("children",JSONArray().put(icon))}
        val raw=JSONObject(definition(icon.toString())).put("title",secret).put("source","/* $secret */ return {state:input.state};").toString()
        val failure=runCatching {ToolSpec.parse(raw)}.exceptionOrNull() as ToolValidationFailure
        val diagnostic=checkNotNull(failure.uiDiagnostic)
        assertEquals("accessible_label",diagnostic.rule)
        assertEquals("$.ui.children[0].children[0].children[0].children[0].text",diagnostic.path)
        assertEquals("label_present",diagnostic.expected); assertEquals("missing",diagnostic.observed)
        assertEquals(ToolUiValidationStage.DEFINITION,diagnostic.stage)
        assertEquals(ToolValidationCategory.UI_STRUCTURE,failure.category)
        assertEquals(ToolValidationCategory.UI_STRUCTURE.feedback,failure.message)
        assertFalse(diagnostic.toString().contains(secret)); assertFalse(failure.message!!.contains(diagnostic.path))
        for(observer in listOf<(ToolUiDiagnostic)->Unit>({}, {throw CancellationException(secret)})) {
            val observed=runCatching {ToolSpec.parse(raw,observer)}.exceptionOrNull() as ToolValidationFailure
            assertEquals(failure.message,observed.message); assertEquals(diagnostic,observed.uiDiagnostic)
        }
        val labeled=JSONObject(icon.toString())
        var leaf=labeled
        repeat(4) {leaf=leaf.getJSONArray("children").getJSONObject(0)}
        leaf.put("text","Add")
        validate(labeled.toString())
        validate("""{"type":"text","text":"Score","bind":"count"}""")
        val schema=JSONObject().put(secret,"string").toString()
        val dateUi="""{"type":"column","children":[{"type":"column","children":[{"type":"text","text":"Visit"},{"type":"dateInput","text":"Visit date","bind":"$secret","action":"date"}]}]}"""
        for(date in listOf("2027-02-29","2028-02-30","","29/02/2028","2028-02-29T00:00Z",secret)) {
            val bad=runCatching {validate(dateUi,schema,JSONObject().put(secret,date).toString())}.exceptionOrNull() as ToolValidationFailure
            val detail=checkNotNull(bad.uiDiagnostic)
            assertEquals("date_value",detail.rule); assertEquals("$.ui.children[0].children[1].bind",detail.path)
            assertEquals("iso_date",detail.expected); assertEquals("string",detail.observed)
            assertEquals(ToolValidationCategory.UI_STRUCTURE,bad.category)
            assertFalse(detail.toString().contains(secret)); if(date.isNotEmpty()) assertFalse(detail.toString().contains(date))
        }
        val leap=validate(dateUi,schema,JSONObject().put(secret,"2028-02-29").toString())
        val stateFailure=runCatching {leap.validateUi(leap.ui,JSONObject().put(secret,""))}.exceptionOrNull() as ToolValidationFailure
        val stateDiagnostic=checkNotNull(stateFailure.uiDiagnostic)
        assertEquals(ToolUiValidationStage.STATE,stateDiagnostic.stage)
        assertEquals("date_value",stateDiagnostic.rule)
        val missing=JSONObject(definition("""{"type":"divider"}""")).put("ui",secret).toString()
        val missingFailure=runCatching {ToolSpec.parse(missing)}.exceptionOrNull() as ToolValidationFailure
        val missingDiagnostic=checkNotNull(missingFailure.uiDiagnostic)
        assertEquals("ui_object",missingDiagnostic.rule)
        assertEquals("string",missingDiagnostic.observed)
    }
    @Test fun diagnosticObserverDoesNotChangeGenerationRequestsFeedbackOrRetryCount(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val valid=definition("""{"type":"text","text":"Count","bind":"count"}""")
        val invalid=definition("""{"type":"SECRET_COMPONENT","text":"SECRET_LABEL"}""")
        val missingLabel=definition("""{"type":"icon","name":"Plus","action":"secretAction"}""")
        val invalidDate=definition("""{"type":"dateInput","text":"Visit date","bind":"SECRET_BIND","action":"date"}""","""{"SECRET_BIND":"string"}""","""{"SECRET_BIND":"SECRET_DATE"}""")
        val validDate=definition("""{"type":"dateInput","text":"Visit date","bind":"SECRET_BIND","action":"date"}""","""{"SECRET_BIND":"string"}""","""{"SECRET_BIND":"2028-02-29"}""")
        val previewInvalid=JSONObject(valid).put("source","return {state:input.state,ui:{type:'SECRET_PREVIEW',text:'SECRET_LABEL'}};").toString()
        suspend fun run(outputs: List<String>, observer: ((ToolUiDiagnostic)->Unit)?, cancel: Boolean = false): Pair<List<CloudRequest>,String> {
            val requests=mutableListOf<CloudRequest>()
            val provider=object: ChatProvider {
                override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                    requests.add(request)
                    if(cancel) throw CancellationException("SECRET_CANCEL")
                    emit(CloudEvent.Text(outputs[requests.lastIndex]))
                }
            }
            val service=if(observer == null) ToolGenerationService(provider,SandboxClient(context)) else ToolGenerationService(provider,SandboxClient(context),observer)
            val result=try {
                service.generate("offline","Synthetic request",null,emptyList(),CloudSession(),"synthetic")
                "accepted"
            } catch(failure: Exception) {failure.javaClass.name+":"+failure.message}
            return requests to result
        }
        for(outputs in listOf(listOf(invalid,valid),listOf(invalid,invalid),listOf(missingLabel,valid),listOf(invalidDate,validDate),listOf(previewInvalid,valid),listOf(previewInvalid,previewInvalid),listOf(valid))) {
            val diagnostics=mutableListOf<ToolUiDiagnostic>()
            val ordinary=run(outputs,null)
            assertEquals(ordinary,run(outputs,diagnostics::add))
            assertEquals(ordinary,run(outputs,observer={throw IllegalStateException("SECRET_OBSERVER")}))
            assertEquals(if(outputs.first() == valid) 1 else 2,ordinary.first.size)
            assertFalse(diagnostics.toString().contains("SECRET_"))
            if(outputs.first() == previewInvalid) assertEquals(ToolUiValidationStage.PREVIEW,diagnostics.first().stage)
            if(ordinary.first.size == 2) {
                val correction=ordinary.first[1].turns.last().text
                val diagnostic=diagnostics.first()
                assertEquals(outputs.first(),ordinary.first[1].turns[1].text)
                assertTrue(correction.contains("UI rule: ${diagnostic.rule}."))
                assertTrue(correction.contains("Path: ${diagnostic.path}."))
                assertTrue(correction.contains("Expected: ${diagnostic.expected}."))
                assertTrue(correction.contains("Observed type: ${diagnostic.observed}."))
                assertTrue(correction.contains("Stage: ${diagnostic.stage.name}."))
                assertFalse(correction.contains("SECRET_")); assertFalse(correction.contains("secretAction"))
                assertFalse(ordinary.second.contains("UI rule:")); assertFalse(ordinary.second.contains("$.ui"))
            }
        }
        assertEquals(run(listOf(valid),null,true),run(listOf(valid),{error("observer")},true))
    }
    @Test fun engineRejectsForgedAndStaleActionsBeforeEvaluation(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext; val name="native-ui-${UUID.randomUUID()}.db"
        val provider=object: ChatProvider {override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent)->Unit)=error("Offline fixture")}
        ToolStore(context,name).use {store ->
            val spec=validate(NativeUiFixtures.medicalUi,NativeUiFixtures.schema,NativeUiFixtures.state)
            val old=ToolRecord(UUID.randomUUID().toString(),1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
            store.saveTool(old,true); val engine=ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
            assertTrue(runCatching {engine.interact(old.id,1,1,"age","42")}.isFailure); assertEquals(old,store.tool(old.id))
            val next=engine.interact(old.id,1,1,"age",42); assertEquals(2,next.stateVersion)
            assertTrue(runCatching {engine.interact(old.id,1,1,"age",42)}.isFailure); assertEquals(next,store.tool(old.id))
        }; context.deleteDatabase(name)
    }
}

/** Reusable local fixtures for emulator screenshots. No model request or device effect is necessary. */
internal object NativeUiFixtures {
    val schema="""{"patient":{"type":"object","fields":{"name":"string","age":"number","consent":"boolean","visitTime":"number","visitDate":"string","dose":"number"}},"medications":{"type":"array","maxItems":50,"items":{"type":"object","fields":{"key":"string","token":"string","value":{"type":"object","fields":{"name":"string","dose":"string"}}}}},"routes":{"type":"array","maxItems":50,"items":{"type":"object","fields":{"name":"string","from":"string","to":"string"}}},"categories":{"type":"array","maxItems":512,"items":"string"},"distances":{"type":"array","maxItems":512,"items":"number"}}"""
    val state="""{"patient":{"name":"Ada","age":42,"consent":true,"visitTime":570,"visitDate":"2028-02-29","dose":2.5},"medications":[{"key":"m1","token":"opaque","value":{"name":"PRIVATE_MEDICATION","dose":"5 mg"}}],"routes":[{"name":"Coast","from":"Lisbon","to":"Porto"}],"categories":["Mon","Tue","Wed"],"distances":[12,18,8]}"""
    val medicalUi="""{"type":"theme","preset":"soft","children":[{"type":"section","text":"Patient history","children":[{"type":"input","text":"Name","bind":"patient.name","action":"name"},{"type":"numberInput","text":"Age","bind":"patient.age","min":0,"max":130,"action":"age"},{"type":"checkbox","text":"Consent","bind":"patient.consent","action":"consent"},{"type":"timeInput","text":"Visit time","bind":"patient.visitTime","action":"visitTime"},{"type":"dateInput","text":"Visit date","bind":"patient.visitDate","action":"visitDate"},{"type":"slider","text":"Dose","bind":"patient.dose","min":0,"max":10,"action":"dose"}]}]}"""
    val medicationUi="""{"type":"table","text":"Medication page","bind":"medications","action":"medication","columns":[{"text":"Medication","field":"value.name"},{"text":"Dose","field":"value.dose"}]}"""
    val travelUi="""{"type":"table","text":"Travel routes","bind":"routes","columns":[{"text":"Route","field":"name"},{"text":"From","field":"from"},{"text":"To","field":"to"}]}"""
    val chartUi="""{"type":"chart","text":"Distance by day","kind":"bar","categories":"categories","series":[{"text":"Distance","bind":"distances"}]}"""
}

/** Long native itinerary for deterministic UI and screenshot acceptance. No external action is claimed. */
internal object ItineraryFixture {
    val schema="""{"stops":{"type":"array","maxItems":512,"items":{"type":"object","fields":{"id":"string","name":"string","detail":"string","available":"boolean"}}}}"""
    fun rows(count: Int)=JSONArray().apply {repeat(count) {put(JSONObject().put("id","stop$it").put("name","Stop $it").put("detail","Walk by the lake for 20 minutes").put("available",true))}}
    val state=JSONObject().put("stops",rows(24)).toString()
    val ui="""{"type":"list","text":"Day plan","bind":"stops","action":"selectStop","columns":[{"text":"Stop","field":"name"},{"text":"Plan","field":"detail"}],"rowActions":[{"action":"mapStop","text":"Search maps","emphasis":"primary","enabledField":"available"},{"action":"calendarStop","text":"Open calendar form","emphasis":"secondary"}]}"""
    fun spec(title: String="Lake day")=JSONObject().put("formatVersion",2).put("title",title).put("description","A quiet walk beside the lake")
        .put("capabilities",JSONArray()).put("stateSchema",JSONObject(schema)).put("initialState",JSONObject(state)).put("ui",JSONObject(ui))
        .put("source","return {state:input.state};").toString()
}
