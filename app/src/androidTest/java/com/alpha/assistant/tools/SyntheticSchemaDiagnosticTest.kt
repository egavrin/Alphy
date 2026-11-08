package com.alpha.assistant.tools

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in diagnosis only. Failure cases: lost retry, leaked candidate, wrong normalization,
 * persistent candidate storage, absent credential, and an unbounded request count. */
@RunWith(AndroidJUnit4::class)
class SyntheticSchemaDiagnosticTest {
    companion object {
        private const val REQUEST = "Create an interactive medication tracker app. Let me enter a medicine name, record when I take it, and review the history. Do not suggest medicines or doses."
        private const val ARKANOID_REQUEST = "Create a playable Arkanoid game with touch paddle control, moving ball, brick collisions, score, three lives, and restart. Save the high score locally."
        private const val REFINEMENT_REQUEST = "Refine this synthetic medication tracker. Improve medicine entry, recording a taken dose, and the history controls. Preserve all saved data. Do not suggest medicines or doses."
        private val uiRules=mapOf(
            "ui_object" to "object", "ui_bytes" to "bytes_at_most_32768", "node_depth_limit" to "nodes_256_depth_8",
            "component_type" to "catalog_component", "component_properties" to "catalog_properties", "component_flag" to "boolean",
            "component_text" to "string_length_1_300", "component_style" to "catalog_style", "accessible_label" to "label_present",
            "component_binding" to "declared_binding_and_state",
            "component_binding_missing_path" to "declared_path_in_schema_and_state",
            "component_binding_schema_type" to "compatible_schema_type",
            "component_binding_state_value" to "schema_valid_state_value", "component_action" to "unique_ascii_action",
            "component_contract" to "catalog_component_contract", "theme_preset" to "catalog_preset", "grid_columns" to "integer_1_4",
            "spacer_size" to "integer_0_64", "icon_name" to "catalog_icon", "button_icon" to "catalog_icon",
            "input_length" to "integer_1_2048", "text_format" to "number_or_clock_format", "selection_options" to "options_1_30",
            "selection_option" to "typed_unique_option", "selection_current_value" to "current_value_in_options",
            "record_collection" to "stable_record_collection", "row_actions" to "actions_1_3", "row_action" to "catalog_row_action",
            "table_rows" to "rows_at_most_50", "table_columns" to "columns_1_8_cells_400", "table_column" to "scalar_record_column",
            "image_handle" to "opaque_resource_handle", "numeric_bounds" to "finite_ordered_bounds_and_value",
            "time_value" to "integer_minutes_0_1439", "date_value" to "iso_date", "container_children" to "children_1_50",
            "child_object" to "object", "render_node_depth_limit" to "nodes_256_depth_8", "render_image_limit" to "images_at_most_4",
            "render_work_limit" to "render_work_at_most_2048", "render_children" to "children_1_50", "chart_kind" to "line_or_bar",
            "chart_categories" to "scalar_array_binding", "chart_series" to "series_1_2_points_256",
            "chart_series_entry" to "aligned_numeric_array", "chart_category_values" to "declared_category_values"
        )
        private val types = setOf("number", "string", "boolean", "object", "list", "array")
        private val constructors = setOf("type", "fields", "items", "maxItems", "properties", "required", "additionalProperties", "minItems", "enum", "default", "format", "nullable", "description", "title", "minimum", "maximum", "minLength", "maxLength", "\$schema", "\$ref", "definitions", "oneOf", "anyOf", "allOf")
        private val safeRules = setOf("Schema exceeds its node or depth limit.", "Invalid schema field name.", "Unknown value schema.")
    }

    @Test fun diagnosticRedactionAndNormalization() {
        val raw = """{"stateSchema":{"privateName":{"type":"list","maxItems":2,"fields":{"id":{"type":"string"},"privateField":{"type":"number"}}},"nested":{"type":"object","fields":{"privateNested":{"type":"string"}}}},"collections":{},"source":"privateSource","ui":{"text":"privateLabel"},"initialState":{"secret":"privateValue"}}"""
        val report = describe(raw)
        val text = report.toString()
        for (secret in listOf("privateName", "privateField", "privateNested", "privateSource", "privateLabel", "privateValue")) assertFalse(text.contains(secret))
        assertEquals(2, report.getInt("normalizedScalarWrappers"))
        assertEquals("rejected", report.getJSONObject("normalizedSchemaProbe").getString("result"))
        assertTrue(report.getJSONArray("nodeProbes").length() > 0)
        assertEquals("unavailable", describe("invalid secret response").getString("structure"))
        assertEquals("accepted", describe("""{"stateSchema":{"secret":{"type":"string"}},"collections":{}}""").getJSONObject("normalizedSchemaProbe").getString("result"))
        val malformed = describe("""{"stateSchema":{"bad name":{"type":"array","maxItems":0,"items":null,"unknownSecret":"privateValue"}},"collections":{}}""").toString()
        assertFalse(malformed.contains("bad name"))
        assertFalse(malformed.contains("unknownSecret"))
        assertFalse(malformed.contains("privateValue"))
        assertTrue(malformed.contains("maxItems"))
        val payloads = describe("""{"stateSchema":{"secret":{"type":"number","default":{"maxItems":12345},"unknown":{"maxItems":54321}}},"collections":{}}""").getJSONObject("stateSchema").toString()
        assertFalse(payloads.contains("12345"))
        assertFalse(payloads.contains("54321"))
        assertFalse(payloads.contains("maxItems"))
        assertTrue(payloads.contains("default"))
        val bound = describe("""{"stateSchema":{"secret":{"type":"array","maxItems":512,"items":"number"}},"collections":{}}""").toString()
        assertTrue(bound.contains("\"value\":512"))
    }

    @Test fun diagnosticStorageRemainsInMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-schema-${UUID.randomUUID()}.db"
        memoryStore(context, name).use { store ->
            store.saveDraft(ToolDraft("test", null, 0, 0, "synthetic", "synthetic source", "synthetic state", "synthetic UI", null))
            assertNotNull(store.draft("test"))
        }
        assertFalse(context.getDatabasePath(name).exists())
        memoryStore(context, name).use { store -> assertTrue(store.drafts().isEmpty()) }
    }

    // UI diagnosis must reject absent opt-in, changed prompts/storage, response overruns, and private output.
    @Test fun uiDiagnosticBoundariesAndProviderBounds(): Unit = runBlocking {
        assertTrue(runCatching {uiBoundary(false,":memory:",ARKANOID_REQUEST)}.isFailure)
        assertTrue(runCatching {uiBoundary(true,"alpha-tools.db",ARKANOID_REQUEST)}.isFailure)
        assertTrue(runCatching {uiBoundary(true,":memory:","private prompt")}.isFailure)
        val operation=uiOperations().first()
        val requests=mutableListOf<CloudRequest>()
        val secret="SECRET_UI_PAYLOAD"
        val provider=UiRecordingProvider(object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                requests.add(request); emit(CloudEvent.Text(secret))
            }
        },operation)
        val original=CloudTurn("user","Request: $ARKANOID_REQUEST")
        val session=CloudSession()
        try {
            provider.respond("offline",CloudRequest(listOf(original),false,jsonObject=true),session) {}
            provider.respond("offline",CloudRequest(listOf(original,CloudTurn("assistant",secret),CloudTurn("user","correction")),false,jsonObject=true),session) {}
            assertTrue(runCatching {provider.respond("offline",CloudRequest(listOf(original),false,jsonObject=true),session) {}}.isFailure)
            assertEquals(2,requests.size)
            assertFalse(provider.reports().toString().contains(secret))
        } finally {provider.clear()}
        assertEquals(0,provider.reports().length())
        val diagnostics=JSONArray()
        val safe=ToolUiDiagnostic(ToolUiValidationStage.DEFINITION,ToolValidationCategory.UI_STRUCTURE,"component_type","$.ui.children[0].type","catalog_component","string",null)
        diagnostics.put(uiDiagnosticReport(safe))
        assertFalse(diagnostics.toString().contains(secret))
        assertTrue(runCatching {uiDiagnosticReport(safe.copy(path="$.ui.SECRET_UI_PAYLOAD"))}.isFailure)
        assertTrue(runCatching {uiDiagnosticReport(safe.copy(rule="secret_payload"))}.isFailure)
        assertTrue(runCatching {uiDiagnosticReport(safe.copy(expected="secret_payload"))}.isFailure)
        val fixture=checkNotNull(uiOperations().last().base)
        val spec=ToolSpec.parse(fixture.spec)
        spec.validateState(fixture.state); spec.validateUi(JSONObject(fixture.ui),JSONObject(fixture.state))
        assertTrue(spec.capabilities.isEmpty()); assertTrue(spec.surfaces.isEmpty()); assertEquals(0,spec.collections.length())
        assertEquals(listOf(ARKANOID_REQUEST,ARKANOID_REQUEST,REFINEMENT_REQUEST),uiOperations().map {it.prompt})
    }

    @Test fun threeSyntheticUiOperations(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Live UI diagnostic requires explicit opt-in.",args.getString("syntheticUiDiagnostic") == "true")
        val revision=args.getString("diagnosticRevision").orEmpty()
        val digest=args.getString("diagnosticSourceSha256").orEmpty()
        require(revision.matches(Regex("[a-fA-F0-9]{7,64}"))) {"Supply diagnosticRevision as a Git revision."}
        require(digest.matches(Regex("[a-fA-F0-9]{64}"))) {"Supply diagnosticSourceSha256 for the tested source."}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val operations=uiOperations()
        check(operations.size == 3)
        val attempts=JSONArray()
        val evidence=JSONObject().put("diagnosticVersion",1).put("testedRevision",revision).put("sourceSha256",digest)
            .put("expected","Two fixed Arkanoid creations and one synthetic medication refinement; at most two responses per operation.")
            .put("attachments",0).put("deviceActions",0).put("graphicsSessions",0).put("attempts",attempts)
            .put("diagnosticScope","actualValidatorFailure records the existing validator location. No independent UI probes are performed.")
        val output=File(context.filesDir,"synthetic-ui-diagnostic.json")
        operations.forEachIndexed {index,operation ->
            val name="synthetic-ui-${UUID.randomUUID()}.db"
            memoryStore(context,name).use {store ->
                uiBoundary(true,store.writableDatabase.path,operation.prompt)
                operation.base?.let {base ->
                    val spec=ToolSpec.parsePersisted(base.spec)
                    spec.validateState(base.state); spec.validateUi(JSONObject(base.ui),JSONObject(base.state))
                    check(spec.capabilities.isEmpty() && spec.surfaces.isEmpty() && spec.collections.length() == 0)
                    store.saveTool(base,true)
                }
                val tools=store.tools(); val revisions=operation.base?.let {store.revisions(it.id)}
                // All opt-in, prompt, fixture, and storage checks precede credential access and network access.
                val key=SecureKeyStore(context).read().getOrNull()
                check(!key.isNullOrBlank()) {"The configured app credential is absent or unreadable. No request was sent."}
                val provider=UiRecordingProvider(DeepSeekProvider(),operation)
                val attempt=JSONObject().put("operation",index+1).put("kind",if(operation.base == null) "arkanoidCreation" else "syntheticMedicationRefinement")
                    .put("storage","verifiedInMemory")
                val service=ToolGenerationService(provider,SandboxClient(context),provider::diagnostic)
                val engine=ToolEngine(store,service)
                try {
                    val draft=engine.createDraft(operation.prompt,operation.base?.id)
                    engine.generate(draft.id,key,emptyList(),CloudSession())
                    attempt.put("result","acceptedPreview"); provider.finish("acceptedPreview",null)
                } catch(failure: Exception) {
                    val category=category(failure.message.orEmpty())
                    attempt.put("result","rejectedOrFailed").put("category",category ?: JSONObject.NULL).put("failureCode",failureCode(failure))
                    provider.finish("rejectedOrFailed",category)
                } finally {
                    try {
                        attempt.put("responses",provider.reports()); attempts.put(attempt)
                        output.writeText(evidence.toString(2))
                    } finally {provider.clear()}
                }
                check(tools == store.tools()) {"The diagnostic changed synthetic saved data."}
                operation.base?.let {check(revisions == store.revisions(it.id)) {"The diagnostic changed synthetic revisions."}}
            }
            assertFalse(context.getDatabasePath(name).exists())
        }
        assertEquals(3,attempts.length())
    }

    private data class UiOperation(val prompt: String,val base: ToolRecord? = null) {
        val context get() = base?.let {"\nCurrent tool: ${it.spec}\nCurrent state: ${it.state}"}.orEmpty()
    }
    private fun uiOperations(): List<UiOperation> {
        val spec=ToolSpec.parse("""{"formatVersion":3,"title":"Synthetic medication tracker","description":"Synthetic diagnostic fixture","capabilities":[],"stateSchema":{"medicine":"string","taken":"boolean","history":{"type":"list","maxItems":10,"fields":{"id":"string","at":"string"}}},"initialState":{"medicine":"Synthetic medicine","taken":false,"history":[{"id":"synthetic1","at":"2026-01-01"}]},"ui":{"type":"column","children":[{"type":"input","text":"Medicine","bind":"medicine","action":"medicine"},{"type":"checkbox","text":"Taken","bind":"taken","action":"taken"},{"type":"list","text":"History","bind":"history"}]},"source":"return {state:input.state};"}""")
        return listOf(UiOperation(ARKANOID_REQUEST),UiOperation(ARKANOID_REQUEST),UiOperation(REFINEMENT_REQUEST,
            ToolRecord("synthetic-medication",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true,createdAt=0)))
    }
    private fun uiBoundary(optIn: Boolean,databasePath: String,prompt: String) {
        check(optIn) {"The UI diagnostic requires explicit opt-in."}
        check(databasePath == ":memory:") {"The UI diagnostic requires memory storage."}
        check(prompt == ARKANOID_REQUEST || prompt == REFINEMENT_REQUEST) {"Unexpected diagnostic prompt."}
    }
    private fun uiDiagnosticReport(diagnostic: ToolUiDiagnostic): JSONObject {
        check(uiRules[diagnostic.rule] == diagnostic.expected)
        check(diagnostic.path.matches(Regex("\\$\\.ui(?:\\.(?:type|properties|visible|enabled|text|style|bind|action|preset|columns|size|name|icon|maxLength|format|options|rowActions|emphasis|enabledField|field|kind|categories|series|resourceId|fit|min|max|step|children|value)(?:\\[[0-9]{1,3}\\])?)*")))
        check(diagnostic.observed in setOf("missing","null","object","array","string","integer","number","boolean","other"))
        return JSONObject().put("origin","actualValidatorFailure").put("stage",diagnostic.stage.name.lowercase(java.util.Locale.ROOT))
            .put("category",diagnostic.category.id).put("rule",diagnostic.rule).put("path",diagnostic.path)
            .put("expected",diagnostic.expected).put("observed",diagnostic.observed).put("count",diagnostic.count ?: JSONObject.NULL)
    }
    private class DiagnosticRouting {
        private var plans=0
        private var batches=0
        private var corrected=false

        fun isRouteRequest(request: CloudRequest,prompt: String): Boolean {
            val stage=request.generationStage ?: return false
            if (stage != CloudGenerationStage.SELECTOR && request.turns.size > 1) {
                check(!corrected) {"The normal generation retry bound changed."}
                corrected=true
            }
            return when (stage) {
                CloudGenerationStage.PLAN -> {
                    check(++plans <= 2)
                    val contract = com.alpha.assistant.tools.generation.NeedsPlan.prompt
                    if (plans == 1) check(request.systemInstructions.startsWith(contract + "\n\n"))
                    else {
                        check(request.systemInstructions.startsWith(contract + "\n\n"))
                        check(request.turns.last() == CloudTurn("user","Return the corrected JSON plan."))
                    }
                    check(JSONObject(request.turns.first().text).getString("request") == prompt)
                    true
                }
                CloudGenerationStage.SELECTOR -> {
                    check(++batches <= 2 && request.turns.size == 1 && request.systemInstructions.isNotEmpty())
                    true
                }
                else -> false
            }
        }

        fun clear() { plans=0; batches=0; corrected=false }
    }

    private inner class UiRecordingProvider(private val delegate: ChatProvider,private val operation: UiOperation): ChatProvider {
        private inner class Response {
            val text=StringBuilder(); var characters=0L; var outcome="providerFailed"; var category: String?=null
            val diagnostics=JSONArray()
            var reachedOutputLimit=false
        }
        private val responses=mutableListOf<Response>()
        private val routing=DiagnosticRouting()
        private var original: CloudTurn?=null
        override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
            check(request.jsonObject && !request.search && request.selectedText.isEmpty())
            check(request.turns.all {it.images.isEmpty()})
            if (routing.isRouteRequest(request,operation.prompt)) {
                check(operation.base == null)
                delegate.respond(key,request,session,emit)
                return
            }
            check(responses.size < 2) {"The normal generation retry bound changed."}
            check(request.systemInstructions.isEmpty())
            check(request.turns.first().role == "user" && request.turns.first().text.endsWith("Request: ${operation.prompt}${operation.context}"))
            if(responses.isEmpty()) {check(request.turns.size == 1); original=request.turns.first()}
            else {
                val previous=responses.last()
                check(request.turns.first() == original && request.turns.last().role == "user")
                if (previous.reachedOutputLimit) {
                    check(request.turns.size == 2)
                    previous.outcome="outputLimitRequestedCorrection"
                } else {
                    check(request.turns.size == 3 && request.turns[1].role == "assistant" && request.turns[1].text == previous.text.toString())
                    previous.outcome="normalValidatorRequestedCorrection"
                }
                previous.category=category(request.turns.last().text)
            }
            val response=Response().also(responses::add)
            try {
                delegate.respond(key,request,session) {event ->
                    if(event is CloudEvent.Text) {
                        response.characters+=event.value.length
                        val remaining=65536-response.text.length
                        if(remaining > 0) response.text.append(event.value.take(remaining))
                    }
                    emit(event)
                }
                response.outcome="providerCompleted"
            } catch (failure: CloudFailure) {
                response.reachedOutputLimit=failure.code == "response_length"
                throw failure
            }
        }
        fun diagnostic(value: ToolUiDiagnostic) {responses.lastOrNull()?.diagnostics?.put(uiDiagnosticReport(value))}
        fun finish(outcome: String,category: String?) {responses.lastOrNull()?.let {it.outcome=outcome; it.category=category}}
        fun reports()=JSONArray().apply {responses.forEachIndexed {index,response ->
            put(JSONObject().put("response",index+1).put("outcome",response.outcome).put("category",response.category ?: JSONObject.NULL)
                .put("characters",response.characters).put("captureTruncated",response.characters > response.text.length)
                .put("validatorFailures",response.diagnostics))
        }}
        fun clear() {responses.forEach {it.text.clear()}; responses.clear(); routing.clear(); original=null}
    }

    private fun memoryStore(context: android.content.Context, databaseName: String): ToolStore {
        val memoryContext = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String?): File {
                check(name == databaseName) { "Unexpected diagnostic database." }
                return File(":memory:")
            }
            override fun openOrCreateDatabase(name: String?, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
                check(name == databaseName) { "Unexpected diagnostic database." }
                return SQLiteDatabase.create(factory)
            }
        }
        val store = ToolStore(memoryContext, databaseName)
        try {
            val database = store.writableDatabase
            check(database.path == ":memory:") { "Diagnostic storage is not in memory. No request was sent." }
            database.rawQuery("PRAGMA database_list", null).use { cursor ->
                check(cursor.count == 1) { "Unexpected attached diagnostic database. No request was sent." }
                check(cursor.moveToFirst() && cursor.getString(1) == "main" && cursor.getString(2).isNullOrEmpty()) {
                    "Diagnostic storage has a file path. No request was sent."
                }
            }
            check(!context.getDatabasePath(databaseName).exists()) { "A diagnostic database file exists. No request was sent." }
            return store
        } catch (failure: Exception) {
            store.close()
            throw failure
        }
    }

    @Test fun threeIndependentSyntheticGenerations() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live diagnostic requires explicit opt-in.", args.getString("syntheticSchemaDiagnostic") == "true")
        val revision = args.getString("diagnosticRevision").orEmpty()
        val sourceDigest = args.getString("diagnosticSourceSha256").orEmpty()
        require(revision.matches(Regex("[a-fA-F0-9]{7,64}"))) { "Supply diagnosticRevision as a Git revision." }
        require(sourceDigest.matches(Regex("[a-fA-F0-9]{64}"))) { "Supply diagnosticSourceSha256 for the tested source." }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val attempts = JSONArray()
        val evidence = JSONObject().put("diagnosticVersion", 1).put("testedRevision", revision)
            .put("sourceSha256", sourceDigest).put("request", REQUEST).put("expected", "Three independent normal generations, each with at most one normal correction.")
            .put("existingArtifact", false).put("attachments", 0).put("deviceActions", 0)
            .put("attempts", attempts).put("ruleLimit", "The original validator erases rule details. Schema probes are separate diagnostics, not the original failure location.")
        val output = File(context.filesDir, "synthetic-schema-diagnostic.json")
        repeat(3) { index ->
            val name = "synthetic-schema-${UUID.randomUUID()}.db"
            val provider = RecordingProvider(DeepSeekProvider())
            val attempt = JSONObject().put("attempt", index + 1)
            memoryStore(context, name).use { store ->
                attempt.put("storage", "verifiedInMemory")
                val key = SecureKeyStore(context).read().getOrNull()
                check(!key.isNullOrBlank()) { "The configured app credential is absent or unreadable. No request was sent." }
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                try {
                    val draft = engine.createDraft(REQUEST, null)
                    engine.generate(draft.id, key, emptyList(), CloudSession())
                    attempt.put("result", "acceptedPreview")
                    provider.finish("acceptedPreview", null)
                } catch (failure: Exception) {
                    val category = category(failure.message.orEmpty())
                    attempt.put("result", "rejectedOrFailed").put("category", category ?: JSONObject.NULL)
                        .put("failureCode", failureCode(failure))
                    provider.finish("rejectedOrFailed", category)
                } finally {
                    attempt.put("responses", provider.reports())
                    provider.clear()
                    attempts.put(attempt)
                    output.writeText(evidence.toString(2))
                }
                check(store.tools().isEmpty()) { "The diagnostic must not accept an artifact." }
            }
            assertFalse("The diagnostic must not create a database file.", context.getDatabasePath(name).exists())
        }
        assertEquals(3, attempts.length())
    }

    private fun category(text: String): String? = ToolValidationCategory.entries.firstOrNull { text.contains("Validation category: ${it.id}.") }?.id

    private fun failureCode(failure: Exception): String {
        val code = (failure as? CloudFailure)?.code ?: return "localFailure"
        val allowed = setOf("auth", "cancelled", "empty_response", "incomplete", "provider", "quota", "rate_limit", "response_length", "response_size", "stream_invalid", "unavailable", "tool_invalid", "tool_limit", "widget_generation", "widget_response_size", "widget_plan", "widget_selector", "widget_questions", "widget_incomplete", "widget_spec", "widget_state", "widget_preview", "widget_preview_state", "widget_preview_effects")
        return code.takeIf { it in allowed || it.matches(Regex("http_[0-9]{3}")) } ?: "providerFailure"
    }

    private inner class RecordingProvider(private val delegate: ChatProvider) : ChatProvider {
        private inner class Response {
            val text = StringBuilder()
            var characters = 0L
            var outcome = "providerFailed"
            var category: String? = null
            var code: String? = null
        }
        private val responses = mutableListOf<Response>()
        private val routing=DiagnosticRouting()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            check(request.jsonObject && !request.search && request.turns.all { it.images.isEmpty() })
            if (routing.isRouteRequest(request,REQUEST)) {
                delegate.respond(key,request,session,emit)
                return
            }
            check(responses.size < 2) { "The normal generation retry bound changed." }
            check(request.turns.first().text.endsWith("Request: $REQUEST"))
            if (responses.isNotEmpty()) {
                val previous=responses.last()
                if (previous.code == "response_length") {
                    check(request.turns.size == 2)
                    previous.outcome = "outputLimitRequestedCorrection"
                } else {
                    check(request.turns.size == 3 && request.turns[1].text == previous.text.toString())
                    previous.outcome = "normalValidatorRequestedCorrection"
                }
                previous.category = category(request.turns.last().text)
            } else check(request.turns.size == 1)
            val response = Response().also(responses::add)
            try {
                delegate.respond(key, request, session) { event ->
                    if (event is CloudEvent.Text) {
                        response.characters += event.value.length
                        val remaining = 65536 - response.text.length
                        if (remaining > 0) response.text.append(event.value.take(remaining))
                    }
                    emit(event)
                }
                response.outcome = "providerCompleted"
            } catch (failure: Exception) {
                response.code = failureCode(failure)
                throw failure
            }
        }
        fun finish(outcome: String, category: String?) {
            responses.lastOrNull()?.let { it.outcome = outcome; it.category = category }
        }
        fun reports() = JSONArray().apply {
            responses.forEachIndexed { index, response ->
                put(JSONObject().put("response", index + 1).put("outcome", response.outcome)
                    .put("category", response.category ?: JSONObject.NULL).put("failureCode", response.code ?: JSONObject.NULL)
                    .put("characters", response.characters).put("captureTruncated", response.characters > response.text.length)
                    .put("schema", describe(response.text.toString())))
            }
        }
        fun clear() { responses.forEach { it.text.clear() }; responses.clear(); routing.clear() }
    }

    private fun probe(schema: JSONObject, collections: JSONObject): JSONObject = try {
        ToolValueSchema.validateSchemas(schema, collections)
        JSONObject().put("result", "accepted")
    } catch (failure: Exception) {
        JSONObject().put("result", "rejected").put("rule", failure.message?.takeIf { it in safeRules } ?: "Constructor, scalar, name, field-count, or integer bound requirement failed.")
    }

    private fun describe(raw: String): JSONObject {
        val parsed = try { SandboxContract.objectFrom(raw); Json.parseToJsonElement(raw) as JsonObject }
            catch (_: Exception) { return JSONObject().put("structure", "unavailable") }
        var wrappers = 0
        fun normalize(fields: JsonObject) = JsonObject(fields.mapValues { (_, value) ->
            if (value is JsonObject && value.keys == setOf("type") && value["type"] in listOf("number", "string", "boolean").map(::JsonPrimitive)) {
                wrappers++; value.getValue("type")
            } else value
        })
        // Mirror only the existing generatedSpec scalar normalization for diagnostic probes.
        val state = parsed["stateSchema"] as? JsonObject
        val normalized = state?.let { JsonObject(normalize(it).mapValues { (_, value) ->
            if (value is JsonObject && value["type"] == JsonPrimitive("list") && value["fields"] is JsonObject)
                JsonObject(value + ("fields" to normalize(value.getValue("fields") as JsonObject))) else value
        }) }
        val json = JSONObject(parsed.toString())
        val normalizedState = normalized?.let { JSONObject(it.toString()) }
        val collections = if (json.has("collections")) json.optJSONObject("collections") else JSONObject()
        val probes = JSONArray()
        val names = mutableMapOf<String, String>()
        var nodes = 0
        var depth = 0
        fun redacted(value: Any?) = JSONObject().put("redacted", true).put("kind", when (value) {
            is JSONObject -> "object"
            is JSONArray -> "array"
            is String -> "string"
            is Number -> if (value is Int) "integer" else "number"
            is Boolean -> "boolean"
            else -> "null"
        })
        fun shape(value: Any?, path: String, level: Int, fieldMap: Boolean = false, schemaNode: Boolean = false, bound: Boolean = false): Any {
            nodes++; depth = maxOf(depth, level)
            return when (value) {
                is JSONObject -> {
                    if (!fieldMap && !schemaNode) return redacted(value)
                    val entries = JSONArray()
                    value.keys().forEach { key ->
                        val safeKey = if (fieldMap) names.getOrPut(key) { "field${names.size + 1}" }
                            else key.takeIf { it in constructors } ?: "unknownKey${entries.length() + 1}"
                        val entry = JSONObject().put("key", safeKey)
                        if (fieldMap) entry.put("validName", ToolValueSchema.name(key)).put("nameLength", key.length).put("stableIdField", key == "id")
                        val item = value.get(key)
                        val childPath = "$path.$safeKey"
                        val type = value.optString("type")
                        val child = when {
                            fieldMap -> shape(item, childPath, level + 1, schemaNode = true)
                            key == "fields" && type in setOf("object", "list") -> shape(item, childPath, level + 1, fieldMap = true)
                            key == "items" && type == "array" -> shape(item, childPath, level + 1, schemaNode = true)
                            key == "type" -> shape(item, childPath, level + 1)
                            key == "maxItems" && type in setOf("array", "list") -> shape(item, childPath, level + 1, bound = true)
                            else -> redacted(item)
                        }
                        entry.put("value", child)
                        entries.put(entry)
                        if (fieldMap) probes.put(JSONObject().put("path", "$path.$safeKey")
                            .put("standaloneProbe", probe(JSONObject().put("field", value.get(key)), JSONObject())))
                    }
                    JSONObject().put("kind", if (fieldMap) "fieldMap" else "object").put("count", value.length()).put("entries", entries)
                }
                is JSONArray -> redacted(value)
                is String -> JSONObject().put("kind", "string").put("length", value.length)
                    .put("schemaType", value.takeIf { it in types } ?: "redacted")
                is Number -> JSONObject().put("kind", if (value is Int) "integer" else "number")
                    .apply { if (bound) put("value", value) }
                is Boolean -> JSONObject().put("kind", "boolean")
                else -> JSONObject().put("kind", "null")
            }
        }
        val report = JSONObject().put("structure", "available").put("normalizedScalarWrappers", wrappers)
            .put("stateSchema", shape(json.opt("stateSchema"), "$.stateSchema", 0, true))
            .put("collections", shape(json.opt("collections") ?: JSONObject(), "$.collections", 0, true))
            .put("shapeNodes", nodes).put("shapeDepth", depth).put("nodeProbes", probes)
            .put("probeScope", "Standalone probes use raw nodes before scalar normalization and reset depth and node budgets. Paths use deterministic generic names. stableIdField identifies the required id name. Only array/list maxItems at schema positions retains numeric values. Extra constructor payloads are redacted without traversal.")
        report.put("normalizedSchemaProbe", if (normalizedState != null && collections != null) probe(normalizedState, collections)
            else JSONObject().put("result", "rejected").put("rule", "stateSchema and collections must be objects."))
        return report
    }
}
