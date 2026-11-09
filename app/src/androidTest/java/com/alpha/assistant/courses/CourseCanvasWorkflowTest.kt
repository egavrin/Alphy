package com.alpha.assistant.courses

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.GraphicsContract
import com.alpha.assistant.tools.LiveInputResult
import com.alpha.assistant.tools.LiveInputSubmission
import com.alpha.assistant.tools.ToolGenerationService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CourseCanvasWorkflowTest {
    @Test fun aLiveSceneKeepsItsControlAcrossStoreReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (kind in listOf(CourseSlideKind.EXPLANATION, CourseSlideKind.EXAMPLE)) {
            val name = "course-canvas-${UUID.randomUUID()}.db"
            val tools = ToolGenerationService(provider(kind), SandboxClient(context))
            try {
                val id = CourseStore(context, name).use { acceptSetting(it, tools, kind) }
                CourseStore(context, name).use { verifySetting(it, tools, id) }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun sceneSettingsAcrossInstrumentedProcessRestart(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("canvasCoursePersistence")
        assumeTrue(phase in setOf("write", "read"))
        val name = "course-canvas-restart-${checkNotNull(args.getString("canvasCourseRunId"))}.db"
        require(name.matches(Regex("course-canvas-restart-[a-f0-9]{12}\\.db")))
        val context = instrumentation.targetContext
        val kind = CourseSlideKind.EXPLANATION
        val tools = ToolGenerationService(provider(kind), SandboxClient(context))
        try {
            CourseStore(context, name).use { store ->
                if (phase == "write") acceptSetting(store, tools, kind)
                else verifySetting(store, tools, store.courses().single().id)
            }
            println("CANVAS_COURSE_RESTART phase=$phase pid=${android.os.Process.myPid()}")
        } finally {
            if (phase == "read") context.deleteDatabase(name)
        }
    }

    private suspend fun acceptSetting(store: CourseStore, tools: ToolGenerationService, kind: CourseSlideKind): String {
        val course = store.create(CourseSetup("Drawing", "Beginner", "Explore a shape"),
            CourseOutline(listOf(CourseLessonPlan("lesson", "Shape", listOf(CourseSlidePlan("slide", kind, "Move a circle"))))))
        val controller = CourseController(store, tools)
        val generated = controller.generate(course.id, course.revision, "slide", "offline", CloudSession())
        assertEquals(null, controller.exercise(course.id, generated.revision, "slide"))
        val page = CoursePresentation(store, controller).player(course.id) as CoursePage.Slide
        val host = controller.sceneHost(page)
        try {
            host.open()
            val before = host.event(GraphicsContract.event("frame", 16, surfaceId = "fill_diagram"))
            assertEquals(1, JSONObject(before.tool.state).getInt("fill_phase"))
            val pending = host.finishLiveInput(before, "p5_speed_edit", 8.0) { true } as LiveInputSubmission.Pending
            host.liveInputFrame(10)
            val after = withTimeout(5000) { pending.receipt.await() } as LiveInputResult.Accepted
            assertEquals(8.0, x(after.snapshot.surfaces), 0.0)
            assertEquals(generated, store.course(course.id))
            assertTrue(store.answers(course.id).isEmpty())
            host.close()
            val reopened = host.open()
            assertEquals(8.0, x(reopened.surfaces), 0.0)
            assertEquals(0, JSONObject(reopened.tool.state).getInt("fill_phase"))
        } finally {
            host.close()
            host.dispose()
        }
        return course.id
    }

    private suspend fun verifySetting(store: CourseStore, tools: ToolGenerationService, id: String) {
        val controller = CourseController(store, tools)
        val page = CoursePresentation(store, controller).player(id) as CoursePage.Slide
        val host = controller.sceneHost(page)
        try {
            assertEquals(8.0, x(host.open().surfaces), 0.0)
        } finally {
            host.close()
            host.dispose()
        }
    }

    private fun x(surfaces: JSONObject) = surfaces.getJSONObject("fill_diagram").getJSONArray("commands").getJSONObject(0).getDouble("x")

    private fun provider(kind: CourseSlideKind) = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            val contentType = kind.name.lowercase(java.util.Locale.ROOT)
            val output = when (request.generationStage) {
                CloudGenerationStage.PLAN -> """
                    {"title":"Circle","description":"","language":"en",
                    "inputs":[{"id":"speed","label":"Speed","type":"number","value":2,"control":"slider","min":1,"max":10}],
                    "constants":[{"id":"body","value":"Change the position with the slider."}],"needs":[
                    {"id":"intro","type":"$contentType","label":"Circle movement","description":"Explain the movement","uses":{"body":"body"}},
                    {"id":"diagram","type":"scene","label":"Diagram","description":"Draw the moving circle","reads":["speed"]}],
                    "questions":[],"scene":{"placement":"inline","brief":"Draw one clear circle",
                    "checks":{"status":"checkable","scenario":{"slots":{"slider":{"kind":"action"},"shape":{"kind":"geometry"}},
                    "steps":[{"op":"capture","slot":"shape","as":"before"},{"op":"action","slot":"slider","value":8},
                    {"op":"assert","slot":"shape","test":"changed","capture":"before"}]}}}}
                """.trimIndent()
                CloudGenerationStage.COMPLETE -> """
                    {"holes":["diagram"],"fill":{"capabilities":[],"stateSchema":{"fill_phase":"number"},"initialState":{"fill_phase":0},
                    "surfaces":[{"id":"fill_diagram","width":64,"height":64}],
                    "ui":{"type":"column","children":[{"type":"canvas","text":"Diagram","surfaces":["fill_diagram"]}]},
                    "source":"const state=input.state, type=input.event.type; if(type==='frame') state.fill_phase++; const result={state,surfaces:{fill_diagram:{commands:[{type:'circle',x:input.ports.speed,y:20,radius:2,color:'accent'}]}}}; if(type==='open') result.frames=['fill_diagram']; return result;",
                    "behaviorBindings":{"slider":"p5_speed_edit","shape":{"surface":"fill_diagram","path":[0]}}}}
                """.trimIndent()
                else -> error("Unexpected stage ${request.generationStage}")
            }
            emit(CloudEvent.Text(output))
        }
    }
}
