package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolDraftPreview
import com.alpha.assistant.tools.ToolDraftPreviewIdentity
import com.alpha.assistant.tools.ToolDraftPreviewObserver
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.writerFixturePatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StaticDraftPreviewWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scene = """{"id":"scene","type":"scene","label":"Dot","description":"Draw and move a dot"}"""
    private val counter = """{"id":"count","type":"counter","label":"Count","description":"Increase and reset"}"""

    private fun plan(mixed: Boolean) = """
        {"title":"Static fixture","description":"","language":"en","inputs":[],"constants":[],
         "needs":[${if (mixed) "$counter,$scene" else scene}],"questions":[]}
    """.trimIndent()

    private inner class Provider(
        private val replies: List<String>,
        private val mixed: Boolean = false,
        private val order: MutableList<String> = mutableListOf(),
    ) : ChatProvider {
        private var codeCalls = 0

        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            val response = when (request.generationStage) {
                CloudGenerationStage.PLAN -> plan(mixed)
                CloudGenerationStage.SELECTOR -> {
                    val questions = JSONObject(request.turns.single().text).getJSONArray("questions")
                    val answers = JSONArray()
                    repeat(questions.length()) { index ->
                        val question = questions.getJSONObject(index)
                        val probabilities = JSONObject()
                        question.getJSONObject("choices").keys().forEach { probabilities.put(it, if (it == "A") 1 else 0) }
                        answers.put(JSONObject().put("id", question.getString("id")).put("choice", "A").put("confidence", 1)
                            .put("probabilities", probabilities))
                    }
                    JSONObject().put("answers", answers).toString()
                }
                CloudGenerationStage.COMPLETE -> {
                    order += "request ${codeCalls + 1}"
                    val output = replies.getOrNull(codeCalls++) ?: throw CloudFailure("auth", "The fixture has no further candidate.")
                    if (!request.turns.last().text.contains("Return one JSON patch object only")) output else {
                        val previous = JSONObject(request.turns[1].text)
                        val next = JSONObject(output)
                        val before = if (mixed) previous.getJSONObject("fill") else previous
                        val after = if (mixed) next.getJSONObject("fill") else next
                        if (before.toString() == after.toString()) after.put("description", "Retry $codeCalls")
                        writerFixturePatch(before.toString(), after.toString())
                    }
                }
                else -> error("Unexpected generation stage.")
            }
            emit(CloudEvent.Text(response))
        }
    }

    private fun definition(title: String = "Drawing", extra: String = "", mixed: Boolean = false): String {
        val field = if (mixed) "fill_value" else "value"
        val surface = if (mixed) "fill_board" else "board"
        val action = if (mixed) "fill_move" else "move"
        val control = JSONObject().put("type", "button").put("text", "Move").put("action", action)
        val source = """
            const event = input.event, state = input.state;
            if (event.type === "frame" || event.type === "action") state.$field++;
            const x = event.type === "preview" ? 8 : event.type === "render" ? 12 : 20 + state.$field;
            const result = {state, frames:["$surface"], surfaces:{"$surface":{commands:[
              {type:"circle",x,y:16,radius:2,color:"accent"}
            ]}}};
            $extra
            return result;
        """.trimIndent()
        val spec = JSONObject().put("title", title).put("description", "").put("source", source)
            .put("capabilities", JSONArray().put("vibration.play"))
            .put("collections", JSONObject("""{"saved":{"type":"object","fields":{"name":"string"}}}"""))
            .put("stateSchema", JSONObject().put(field, "number")).put("initialState", JSONObject().put(field, 0))
            .put("surfaces", JSONArray("""[{"id":"$surface","width":64,"height":64}]"""))
        return if (mixed) {
            spec.put("ui", JSONObject().put("type", "column").put("children", JSONArray().put(control)))
            JSONObject().put("holes", JSONArray().put("scene")).put("fill", spec).toString()
        } else {
            spec.put("formatVersion", 3).put("ui", control).toString()
        }
    }

    private suspend fun withEngine(provider: Provider, block: suspend (ToolStore, ToolEngine) -> Unit) {
        val name = "static-preview-${UUID.randomUUID()}.db"
        val store = ToolStore(context, name)
        val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
        try {
            block(store, engine)
        } finally {
            engine.closeArtifacts()
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun previewX(snapshot: ToolDraftPreview, surface: String = "board"): Int =
        JSONObject(snapshot.surfacesJson).getJSONObject(surface).getJSONArray("commands").getJSONObject(0).getInt("x")

    @Test fun suspendedCallbackPrecedesDraftPublicationAndCarriesOnlyTheOriginalDrawing(): Unit = runBlocking {
        withEngine(Provider(listOf(definition()))) { store, engine ->
            val draft = engine.createDraft("Draw a dot.", null)
            val entered = CompletableDeferred<ToolDraftPreview>()
            val resume = CompletableDeferred<Unit>()
            val observer = ToolDraftPreviewObserver("request", onPreview = { snapshot ->
                entered.complete(snapshot)
                resume.await()
            }, onClear = { error("A successful request cleared its candidate.") })
            val running = async { engine.generate(draft.id, "fixture", emptyList(), CloudSession(), previewObserver = observer) }
            val snapshot = withTimeout(15000) { entered.await() }
            assertEquals(ToolDraftPreviewIdentity(draft.id, "request", 1), snapshot.identity)
            assertEquals(8, previewX(snapshot))
            assertEquals(0, JSONObject(snapshot.stateJson).getInt("value"))
            assertEquals(draft, store.draft(draft.id))
            assertNull(store.tool(draft.id))
            resume.complete(Unit)
            val complete = running.await()
            assertEquals(snapshot.candidateJson, complete.candidate)
            assertEquals(snapshot.stateJson, complete.previewState)
            assertEquals(snapshot.uiJson, complete.previewUi)
            assertEquals(complete, store.draft(draft.id))
        }
    }

    @Test fun correctionClearsTheFirstCandidateBeforeRequestingTheNextOne(): Unit = runBlocking {
        val order = mutableListOf<String>()
        val first = definition("First", """if (event.type === "action") throw Error("Fixture action failure");""")
        val second = definition("Second")
        withEngine(Provider(listOf(first, second), order = order)) { store, engine ->
            val draft = engine.createDraft("Draw a dot.", null)
            val observed = mutableListOf<ToolDraftPreview>()
            val result = engine.generate(draft.id, "fixture", emptyList(), CloudSession(),
                previewObserver = ToolDraftPreviewObserver("request", onPreview = {
                    order += "show ${it.identity.attempt}"
                    observed += it
                    assertEquals(8, previewX(it))
                    assertEquals(draft, store.draft(draft.id))
                }, onClear = { order += "clear ${it.attempt}" }))
            assertEquals(listOf("request 1", "show 1", "clear 1", "request 2", "show 2"), order)
            assertEquals(listOf("First", "Second"), observed.map { JSONObject(it.candidateJson).getString("title") })
            assertEquals(observed.last().candidateJson, result.candidate)
        }
    }

    @Test fun invalidInitialPairNeverShowsAndLateFailureClearsEveryProvisionalDrawing(): Unit = runBlocking {
        val forbidden = listOf(
            """if (event.type === "render") result.effects = [{id:"buzz",capability:"vibration.play",args:{milliseconds:1}}];""",
            """if (event.type === "preview") result.records = [{id:"read",collection:"saved",op:"get",key:"one"}];""",
            """if (event.type === "render") state.value++;""",
            """if (event.type === "render") result.surfaces.board.commands[0].x = 90000;""",
            """if (event.type === "preview") result.surfaces.unknown = result.surfaces.board;"""
        )
        for (script in forbidden) {
            val bad = definition(extra = script)
            withEngine(Provider(listOf(bad, bad))) { store, engine ->
                val draft = engine.createDraft("Draw a dot.", null)
                val events = mutableListOf<String>()
                val observer = ToolDraftPreviewObserver("request", { events += "show" }, { events += "clear" })
                assertTrue(runCatching {
                    engine.generate(draft.id, "fixture", emptyList(), CloudSession(), previewObserver = observer)
                }.isFailure)
                assertTrue(events.isEmpty())
                assertEquals(draft, store.draft(draft.id))
            }
        }

        val late = definition(extra = """if (event.type === "frame") throw Error("Fixture frame failure");""")
        withEngine(Provider(listOf(late, late))) { store, engine ->
            val draft = engine.createDraft("Draw a dot.", null)
            val events = mutableListOf<String>()
            val observer = ToolDraftPreviewObserver("request", onPreview = {
                events += "show ${it.identity.attempt}"
                assertEquals(8, previewX(it))
                assertEquals(0, JSONObject(it.stateJson).getInt("value"))
            }, onClear = { events += "clear ${it.attempt}" })
            assertTrue(runCatching {
                engine.generate(draft.id, "fixture", emptyList(), CloudSession(), previewObserver = observer)
            }.isFailure)
            assertEquals(listOf("show 1", "clear 1", "show 2", "clear 2"), events)
            assertEquals(draft, store.draft(draft.id))
        }
    }

    @Test fun cancellationAndStaleDraftsClearOnlyTheMatchingRequest(): Unit = runBlocking {
        withEngine(Provider(listOf(definition(), definition()))) { store, engine ->
            val draft = engine.createDraft("Draw a dot.", null)
            val session = CloudSession()
            val entered = CompletableDeferred<ToolDraftPreviewIdentity>()
            val resume = CompletableDeferred<Unit>()
            val clears = mutableListOf<ToolDraftPreviewIdentity>()
            var current = "old"
            var displayed: ToolDraftPreview? = null
            val observer = ToolDraftPreviewObserver("old", onPreview = {
                entered.complete(it.identity)
                resume.await()
                if (current == it.identity.requestId) displayed = it
            }, onClear = { clears += it })
            val running = async { runCatching { engine.generate(draft.id, "fixture", emptyList(), session, previewObserver = observer) } }
            val old = withTimeout(15000) { entered.await() }
            current = "new"
            session.cancel()
            resume.complete(Unit)
            assertEquals("cancelled", (running.await().exceptionOrNull() as CloudFailure).code)
            assertEquals(listOf(old), clears)
            assertNull(displayed)
            assertEquals(draft, store.draft(draft.id))

            val edited = draft.copy(prompt = "Draw two dots.")
            val newObserver = ToolDraftPreviewObserver("new", onPreview = { store.saveDraft(edited) }, onClear = { clears += it })
            assertTrue(runCatching {
                engine.generate(draft.id, "fixture", emptyList(), CloudSession(), previewObserver = newObserver)
            }.isFailure)
            assertEquals(listOf(old, ToolDraftPreviewIdentity(draft.id, "new", 1)), clears)
            assertEquals(edited, store.draft(draft.id))
        }
    }

    @Test fun mixedSnapshotUsesTheSealedCandidateAndRequiresVisibleSceneOutput(): Unit = runBlocking {
        val hidden = definition(extra = """if (event.type === "preview") result.surfaces.fill_board.commands = [];""", mixed = true)
        val drawn = definition(mixed = true)
        withEngine(Provider(listOf(hidden, drawn), mixed = true)) { store, engine ->
            val draft = engine.createDraft("Draw a dot and add an independent counter.", null)
            val snapshots = mutableListOf<ToolDraftPreview>()
            val observer = ToolDraftPreviewObserver("mixed", { snapshots += it }, { error("Hidden drawing was published.") })
            val result = engine.generate(draft.id, "fixture", emptyList(), CloudSession(), previewObserver = observer)
            val snapshot = snapshots.single()
            assertEquals(2, snapshot.identity.attempt)
            assertEquals(8, previewX(snapshot, "fill_board"))
            assertEquals(0, JSONObject(snapshot.stateJson).getInt("p5_count_value"))
            assertEquals(0, JSONObject(snapshot.stateJson).getInt("fill_value"))
            assertNotNull(ToolSpec.parsePersisted(snapshot.candidateJson).composition)
            assertEquals(result.candidate, snapshot.candidateJson)
            assertEquals(result, store.draft(draft.id))
        }
    }
}
