package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkflowReliabilityReplayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun recorded(name: String): JSONObject =
        instrumentation.context.assets.open("generation-reliability/$name-recorded.json")
            .bufferedReader().use { JSONObject(it.readText()) }

    private fun firstReply(case: JSONObject, stage: CloudGenerationStage): String {
        val replies = case.getJSONArray("replies")
        return (0 until replies.length()).map(replies::getJSONObject)
            .first { it.getString("stage") == stage.name }.getString("content")
    }

    private data class Replay(
        val result: Result<ToolPreview>,
        val requests: List<CloudRequest>,
        val events: List<ToolGenerationTraceEvent>,
    ) {
        val repairs get() = events.filterIsInstance<ToolGenerationTraceEvent.Repair>()
        val writerRequests get() = requests.filter { it.generationStage == CloudGenerationStage.COMPLETE }
        val behaviorFailures get() = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
            .mapNotNull { it.behaviorBinding }
        val smokeFailures get() = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>().mapNotNull { it.smoke }
    }

    private suspend fun replay(case: JSONObject, plans: List<String>, writers: List<String>): Replay {
        val requests = mutableListOf<CloudRequest>()
        val events = mutableListOf<ToolGenerationTraceEvent>()
        val replies = mapOf(
            CloudGenerationStage.PLAN to plans.iterator(),
            CloudGenerationStage.COMPLETE to writers.iterator(),
        )
        val provider = object : ChatProvider {
            override suspend fun respond(
                key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
            ) {
                requests += request
                val tape = replies[request.generationStage] ?: error("Unexpected stage: ${request.generationStage}")
                if (!tape.hasNext()) throw CloudFailure("offline_fixture_end",
                    "No further offline replies. ${request.turns.last().text.take(600)}")
                emit(CloudEvent.Text(request.withFixtureCapabilityPlan(tape.next())))
            }
        }
        val result = runCatching {
            ToolGenerationService(provider, SandboxClient(instrumentation.targetContext)).generate(
                "offline", case.getString("context"), null, emptyList(), CloudSession(), "workflow-replay",
                privateState = false, draftPreview = null, originalRequest = case.getString("request"),
                trace = ToolGenerationTrace(UUID.randomUUID(), { events += it }),
            )
        }
        return Replay(result, requests, events).also {
            Log.i("WorkflowReliability", "${case.getString("case")} stages=${requests.map { it.generationStage }} " +
                "repairs=${it.repairs.map { it.providerStage to it.repairNumber }} " +
                "behavior=${it.behaviorFailures.map { diagnostic -> diagnostic.rule }} smoke=${it.smokeFailures} accepted=${result.isSuccess}")
        }
    }

    private fun gamePlan(case: JSONObject, observeAvailability: Boolean): String {
        val plan = JSONObject(firstReply(case, CloudGenerationStage.PLAN))
        val steps = plan.getJSONObject("behavior").getJSONObject("scenario").getJSONArray("steps")
        steps.put(10, JSONObject("""{"op":"assert","slot":"status","test":"equals","value":"Победа! Все три звезды найдены."}"""))
        if (observeAvailability) {
            steps.put(12, JSONObject("""{"op":"availability","slot":"c3","value":false}"""))
            steps.put(JSONObject("""{"op":"availability","slot":"c3","value":true}"""))
            steps.put(JSONObject("""{"op":"assert","slot":"status","test":"equals","value":"Звезда! Клетка 2. Очков: 1"}"""))
        }
        return plan.toString()
    }

    @Test fun recordedGridLocksAndResetsWithoutWriterRepair() = runBlocking {
        val case = recorded("grid-game")
        val writer = firstReply(case, CloudGenerationStage.COMPLETE)
        val original = replay(case, listOf(gamePlan(case, observeAvailability = false)), listOf(writer))
        assertEquals(BehaviorFailureRule.ACTION_UNAVAILABLE, original.behaviorFailures.single().rule)
        assertEquals(13, original.behaviorFailures.single().step)
        assertEquals(1, original.repairs.count { it.providerStage == CloudGenerationStage.COMPLETE })

        val corrected = replay(case, listOf(gamePlan(case, observeAvailability = true)), listOf(writer))
        val preview = corrected.result.getOrThrow()
        val grid = JSONObject(preview.ui).getJSONArray("children").getJSONObject(2)
        val cells = grid.getJSONArray("children")
        assertEquals(3, grid.getInt("columns"))
        assertEquals((1..9).map(Int::toString), (0 until cells.length()).map {
            cells.getJSONObject(it).getJSONArray("children").getJSONObject(0).getString("text")
        })
        assertEquals(1, corrected.writerRequests.size)
        assertTrue(corrected.repairs.isEmpty())
    }

    @Test fun availabilityRejectsMissingButtonAndAnUnlockedBoard() = runBlocking {
        val case = recorded("grid-game")
        val plan = gamePlan(case, observeAvailability = true)
        val writer = JSONObject(firstReply(case, CloudGenerationStage.COMPLETE))
        for (path in listOf(JSONArray().put(2).put(50).put(0), JSONArray().put(2).put(2).put(1))) {
            val missing = JSONObject(writer.toString()).apply {
                getJSONObject("behaviorBindings").put("c3", path)
            }
            val absent = replay(case, listOf(plan), listOf(missing.toString()))
            assertEquals(BehaviorFailureRule.TARGET_MISSING, absent.behaviorFailures.single().rule)
            assertEquals(13, absent.behaviorFailures.single().step)
        }

        val unlocked = JSONObject(writer.toString()).apply {
            val grid = getJSONObject("ui").getJSONArray("children").getJSONObject(2)
            grid.getJSONArray("children").getJSONObject(2).getJSONArray("children").getJSONObject(0)
                .remove("enabledBind")
        }
        val wrong = replay(case, listOf(plan), listOf(unlocked.toString()))
        assertEquals(BehaviorFailureRule.ASSERTION_FAILED, wrong.behaviorFailures.single().rule)
        assertEquals(13, wrong.behaviorFailures.single().step)
    }

    @Test fun recordedCanvasReportsTheRejectedDrawingField() = runBlocking {
        val case = recorded("inline-canvas")
        val plan = firstReply(case, CloudGenerationStage.PLAN)
        val writer = firstReply(case, CloudGenerationStage.COMPLETE)
        val captured = replay(case, listOf(plan), listOf(writer))
        val failure = captured.smokeFailures.single()
        assertEquals("preview", failure.getString("event"))
        val withoutStroke = JSONObject(writer).apply {
            val source = getString("source")
            check(source.contains(", stroke: 2,"))
            put("source", source.replace(", stroke: 2,", ","))
        }
        val comparison = replay(case, listOf(plan), listOf(withoutStroke.toString()))
        assertTrue("Removing only the unsupported field must pass preview", comparison.smokeFailures.isEmpty())
        assertTrue(comparison.events.filterIsInstance<ToolGenerationTraceEvent.Stage>()
            .any { it.stage == ToolGenerationStage.BEHAVIOR && it.started })
        assertEquals("\$.surfaces[0].commands[1].stroke", failure.getString("path"))
        assertEquals("drawing.command_fields", failure.getString("failureCode"))
        assertTrue(captured.writerRequests[1].turns.last().text.contains("\$.surfaces[0].commands[1].stroke"))
    }

    @Test fun correctedInlineCanvasAcceptsTwoTouchesAndClearsTheMarker() {
        val case = recorded("inline-canvas")
        val plan = JSONObject(firstReply(case, CloudGenerationStage.PLAN))
        val scene = plan.getJSONObject("scene").put("placement", "inline")
        scene.remove("visualReview")
        val steps = scene.getJSONObject("checks").getJSONObject("scenario").getJSONArray("steps")
        steps.remove(7)
        steps.put(9, JSONObject("""{"op":"assert","slot":"marker","test":"empty"}"""))
        val evidence = scene.getJSONArray("checkEvidence")
        evidence.getJSONObject(4).put("step", 9)
        evidence.getJSONObject(5).put("step", 10)
        for (index in listOf(1, 3, 5)) evidence.getJSONObject(index).put("scope", "selected")
        val writer = JSONObject("""{"formatVersion":3,"title":"Метка на холсте","description":"Отдельный холст","capabilities":[],
            "stateSchema":{"count":"number","x":"number","y":"number"},
            "initialState":{"count":0,"x":0,"y":0},
            "surfaces":[{"id":"board","width":360,"height":250}],
            "ui":{"type":"column","children":[{"type":"canvas","text":"Метка на холсте","surfaces":["board"]},
                {"type":"metric","text":"Касаний","bind":"count"},{"type":"button","text":"Сбросить","action":"reset"}]},
            "behaviorBindings":{"surface":"board","marker":{"surface":"board","path":[1]},"count":[1],"reset":"reset"}}""")
            .put("source", """
                const e = input.event, s = input.state;
                if (e.type === 'pointer' && e.phase === 'down' && e.pointer === 0) {
                    s.count++; s.x = e.x; s.y = e.y;
                }
                if (e.type === 'action' && e.actionId === 'reset') s.count = 0;
                const marker = s.count > 0 ? [{type:'circle',x:s.x,y:s.y,radius:12,color:'#FF7A00'}] : [];
                return {state:s,surfaces:{board:{commands:[
                    {type:'rect',x:0,y:0,width:360,height:250,color:'#F1F3F4'},
                    {type:'group',children:marker}
                ]}}};
            """.trimIndent())
        val run = runBlocking { replay(case, listOf(plan.toString()), listOf(writer.toString())) }
        val preview = run.result.getOrThrow()
        assertTrue(run.repairs.isEmpty())
        assertEquals(listOf("board"), canvasSurfaceIds(JSONObject(preview.ui)))
        val missingMarker = JSONObject(writer.toString()).apply {
            put("source", getString("source").replace("{type:'group',children:marker}",
                "...(s.count > 0 ? [{type:'group',children:marker}] : [])"))
        }
        val invalid = runBlocking { replay(case, listOf(plan.toString()), listOf(missingMarker.toString())) }
        assertEquals(BehaviorFailureRule.DRAWING_UNAVAILABLE, invalid.behaviorFailures.single().rule)
        assertEquals(10, invalid.behaviorFailures.single().step)

        val fixture = ShellFixture("workflow-reliability-inline")
        val robot = ShellRobot(fixture.name)
        val database = "${fixture.name}.db-tools.db"
        val output = File(fixture.context.cacheDir, "workflow-reliability-inline").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val proof = JSONObject()
        val id = "verified_inline"
        try {
            ToolStore(fixture.context, database).use { store ->
                store.saveTool(ToolRecord(id, 1, 1, preview.spec.json, preview.state, preview.ui, null, true), revision = true)
                fixture.install()
                fixture.launch().use {
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil { !model.loading && !model.tools.busy }
                    robot.instrumentation.runOnMainSync { model.openSurface(id) }
                    robot.waitFor(robot.text(R.string.tool_refine))
                    fun screenshot(name: String): Boolean {
                        val bitmap = robot.automation.takeScreenshot()
                        return try {
                            bitmap != null && File(output, "$name.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally {
                            bitmap?.recycle()
                        }
                    }
                    val checks = Plan5WorkflowChecks(robot, proof, { checkNotNull(store.tool(id)) }, ::screenshot, database)
                    checks.run(case.getString("case"), { error("Unexpected reopen") }) { error("Unexpected refinement") }
                    robot.waitUntil {
                        val saved = JSONObject(checkNotNull(store.tool(id)).state)
                        saved.getInt("count") == 0 && saved.getDouble("x") > 0 && saved.getDouble("y") > 0
                    }
                    assertEquals(0, fixture.provider.calls)
                    File(output, "workflow.json").writeText(proof.toString(2))
                }
            }
        } finally {
            fixture.model?.let { model ->
                runBlocking { model.tools.closeAndJoin() }
                model.courseStore.close()
                model.memoryStore.close()
            }
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.name}.db-courses.db")
            fixture.context.deleteDatabase("${fixture.name}.db-memory.db")
        }
    }
}
