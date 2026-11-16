package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CanvasGenerationWorkflowTest {
    @Test fun inlineCanvasesValidateDeclaredSurfaceOwnership() {
        val raw = definition()
        assertEquals(4, ToolSpec.parse(raw.toString()).surfaces.size)
        for (invalid in listOf(
            definition().apply { getJSONArray("surfaces").remove(3) },
            definition().apply { getJSONObject("ui").getJSONArray("children").getJSONObject(1).put("surfaces", JSONArray(listOf("a", "b"))) },
            definition().apply { getJSONArray("surfaces").put(JSONObject("""{"id":"extra","width":64,"height":64}""")) },
        )) {
            assertTrue(runCatching { ToolSpec.parse(invalid.toString()) }.exceptionOrNull() is ToolValidationFailure)
        }
        val immersive = definition().put("presentation", JSONObject().put("mode", "immersive"))
        val placementFailure = runCatching { ToolSpec.parse(immersive.toString()) }.exceptionOrNull() as ToolValidationFailure
        assertEquals("canvas_placement", placementFailure.details?.rule)
        val legacy = ToolSpec.parse(definition().put("ui", JSONObject("""{"type":"text","text":"Legacy stage"}""")).toString())
        assertTrue(runCatching { legacy.validateUi(raw.getJSONObject("ui")) }.exceptionOrNull() is ToolValidationFailure)
    }

    @Test fun invisibleTransformDoesNotExposeItsCanvasToTheSceneCheck() {
        val ui = JSONObject("""{"type":"transform","pose":[0,0,0,0,0,0,1,0],"children":[
            {"type":"canvas","text":"Hidden drawing","surfaces":["a"]}]}""")
        assertEquals(listOf("a"), canvasSurfaceIds(ui))
        assertTrue(canvasSurfaceIds(ui, JSONObject()).isEmpty())
    }

    @Test fun separateCanvasGroupsShareOneRetainedSession(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "canvas-groups-${UUID.randomUUID()}.db"
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) = error("Offline")
        }
        val spec = ToolSpec.parse(definition().apply {
            put("source", """
                globalThis.phase ??= 0;
                const ids = ['a', 'b', 'c', 'd'], e = input.event;
                const result = {state: input.state, surfaces: {}};
                const output = e.type === 'frame' ? [e.surfaceId] : ids;
                for (const id of output) result.surfaces[id] = {commands:[{type:'circle',x:16 + globalThis.phase,y:32,radius:5,color:'accent'}]};
                if (e.type === 'frame') globalThis.phase++;
                if (e.type === 'open') result.frames = ids;
                return result;
            """.trimIndent())
        }.toString())
        try {
            ToolStore(context, name).use { store ->
                val tool = ToolRecord("groups", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
                store.saveTool(tool, true)
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                try {
                    val opened = engine.openArtifact(tool)
                    assertEquals(spec.surfaces.keys, opened.frames)
                    val left = engine.artifactEvent(tool.id, 1, GraphicsContract.event("frame", 16, surfaceId = "a"), opened.sessionGeneration)
                    val right = engine.artifactEvent(tool.id, 1, GraphicsContract.event("frame", 16, surfaceId = "c"), opened.sessionGeneration)
                    fun x(frame: ArtifactSnapshot, id: String) = frame.surfaces.getJSONObject(id).getJSONArray("commands").getJSONObject(0).getInt("x")
                    assertEquals(16, x(left, "a"))
                    assertEquals(17, x(right, "c"))
                    assertEquals(16, x(right, "b"))
                    assertEquals(16, x(right, "d"))
                } finally {
                    engine.closeArtifacts()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun definition() = JSONObject("""
        {"formatVersion":3,"title":"Shapes","description":"","capabilities":[],"stateSchema":{"ready":"boolean"},"initialState":{"ready":true},
        "surfaces":[{"id":"a","width":64,"height":64},{"id":"b","width":64,"height":64},
        {"id":"c","width":64,"height":64},{"id":"d","width":64,"height":64}],
        "ui":{"type":"column","children":[{"type":"canvas","text":"First pair","surfaces":["a","b"]},
        {"type":"canvas","text":"Second pair","surfaces":["c","d"]}]}}
    """.trimIndent()).put("source", """
        const frame={commands:[{type:'circle',x:32,y:32,radius:10,color:'accent'}]};
        return {state:input.state,surfaces:{a:frame,b:frame,c:frame,d:frame}};
    """.trimIndent())
}
