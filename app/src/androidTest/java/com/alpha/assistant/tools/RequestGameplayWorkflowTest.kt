package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/** Frozen development fixtures. Each workflow starts from the declared state and uses the production result contract. */
@RunWith(AndroidJUnit4::class)
class RequestGameplayWorkflowTest {
    private val sandbox get() = SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext)
    private val offline = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            error("A frozen gameplay fixture must not call a provider.")
        }
    }

    private inner class Workflow(
        val spec: ToolSpec,
        private val service: ToolGenerationService,
        private val execute: suspend (String, String) -> SandboxResult
    ) {
        private val surfaceId = spec.surfaces.keys.single()
        private var saved = spec.initialState
        private var ui = spec.ui.toString()
        lateinit var result: ToolPreview
            private set

        suspend fun send(event: JSONObject) {
            when (event.getString("type")) {
                "preview", "open", "render" -> Unit
                "action" -> ToolUiCatalog.validateAction(JSONObject(ui), event.getString("actionId"), event.opt("value"), JSONObject(saved))
                else -> GraphicsContract.validateSurfaceEvent(event, spec.surfaces)
            }
            val input = JSONObject().put("artifactId", "frozen_fixture").put("revision", 1)
                .put("state", JSONObject(saved)).put("event", event)
            val raw = execute(spec.source, input.toString())
            assertEquals("sandbox result for ${event.getString("type")}: ${raw.message}", "OK", raw.status)
            result = service.validateResult(spec, saved, event, ui, checkNotNull(raw.json))
            assertTrue(result.effects.isEmpty())
            assertEquals(0, result.records.length())
            saved = result.state
            ui = result.ui
        }

        suspend fun lifecycle(type: String) = send(
            if (type in setOf("preview", "open", "render")) JSONObject().put("type", type)
            else GraphicsContract.event(type, surfaceId = surfaceId)
        )
        suspend fun frame(dt: Int) = send(GraphicsContract.event("frame", dt, surfaceId = surfaceId))
        suspend fun advance(ms: Int) { repeat(ms / 50) { frame(50) } }
        suspend fun pointer(x: Double, y: Double, phase: String = "down") =
            send(GraphicsContract.event("pointer", x = x, y = y, phase = phase, surfaceId = surfaceId))
        suspend fun move(direction: String) =
            send(GraphicsContract.event("control", direction = direction, surfaceId = surfaceId))
        suspend fun speed(value: Double) = send(JSONObject().put("type", "action").put("actionId", "speed").put("value", value))

        fun boundValue(node: JSONObject) = ToolUiCatalog.value(JSONObject(result.state), node.getString("bind"))
        fun commands(type: String): List<JSONObject> {
            val commands = checkNotNull(result.surfaces).getJSONObject(surfaceId).getJSONArray("commands")
            return (0 until commands.length()).map(commands::getJSONObject).filter { it.getString("type") == type }
        }
        fun labels() = commands("text").map { it.getString("text") }
        fun drawing() = checkNotNull(result.surfaces).toString()
    }

    private suspend fun runFixture(raw: String, play: suspend Workflow.() -> Unit) {
        val spec = ToolSpec.parse(raw)
        assertTrue(spec.capabilities.isEmpty())
        val sandbox = sandbox
        val service = ToolGenerationService(offline, sandbox)
        sandbox.disposable { execute -> Workflow(spec, service, execute).play() }
    }

    @Test fun starCatcherShowsMovementMissesAndWinThenRestoresReadyBoard(): Unit = runBlocking {
        runFixture(RequestGameplayExamples.starCatchSpec()) { catchStars() }
    }

    @Test fun orbitSliderAndPointerMoveTheMoonAndRestartRestoresScene(): Unit = runBlocking {
        runFixture(RequestGameplayExamples.orbitSpec()) { exploreOrbit() }
    }

    @Test fun runnableGameWithBrokenScoringFailsTheSameWorkflow(): Unit = runBlocking {
        val broken = variant(RequestGameplayExamples.starCatchSpec(), "s.score += 1", "s.score += 0")
        rejects("Catch awards exactly one point") { runFixture(broken) { catchStars() } }
    }

    @Test fun runnableSceneWithFrozenDrawingFailsTheSameWorkflow(): Unit = runBlocking {
        val broken = variant(RequestGameplayExamples.orbitSpec(), "const angle = s.angle;", "const angle = 0;")
        rejects("Frame advances the visible moon") { runFixture(broken) { exploreOrbit() } }
    }

    private suspend fun Workflow.catchStars() {
        assertEquals("immersive", spec.presentation)
        assertEquals("Собери звёзды", spec.title)
        lifecycle("preview")
        val ready = drawing()
        assertTrue("Ready instructions appear in Russian", "Нажмите «Начать»" in labels())
        assertEquals(emptySet<String>(), result.frames)
        lifecycle("open")
        lifecycle("start")
        assertEquals("playing", result.playback)
        assertEquals(setOf("field"), result.frames)
        val initialY = starY()
        frame(0)
        assertEquals(initialY, starY(), 0.0)
        advance(650)
        assertTrue("A frame moves the star toward the catcher", starY() > initialY)
        assertTrue("No result before contact", "Очки: 0 · Промахи: 0" in labels())
        frame(50)
        assertTrue("A star in another lane counts as one miss", "Очки: 0 · Промахи: 1" in labels())

        val before = drawing()
        pointer(52.0, 200.0, phase = "cancel")
        assertEquals("Canceled input leaves the catcher in place", before, drawing())
        pointer(228.0, 200.0)
        assertEquals(228.0, commands("roundRect").single().getDouble("x") + 22.0, 0.0)
        advance(700)
        assertTrue("Catch awards exactly one point", "Очки: 1 · Промахи: 1" in labels())
        move("left")
        assertEquals(140.0, commands("roundRect").single().getDouble("x") + 22.0, 0.0)
        advance(700)
        assertTrue("Second catch increases the score", "Очки: 2 · Промахи: 1" in labels())
        move("left")
        advance(700)
        assertTrue("Third catch wins", "Вы победили!" in labels())
        assertTrue("Final score remains visible", "Очки: 3 · Промахи: 1" in labels())
        assertEquals("ended", result.playback)
        assertEquals(emptySet<String>(), result.frames)
        val won = drawing()
        move("right")
        assertEquals("Movement does not change a finished board", won, drawing())

        lifecycle("restart")
        assertEquals("ready", result.playback)
        assertEquals(spec.initialState, result.state)
        assertEquals(ready, drawing())
        lifecycle("start")
        advance(1400)
        assertTrue("Two misses end the game", "Игра окончена" in labels())
        assertTrue("A miss never awards points", "Очки: 0 · Промахи: 2" in labels())
        assertEquals("ended", result.playback)
        assertEquals(emptySet<String>(), result.frames)
    }

    private suspend fun Workflow.exploreOrbit() {
        assertEquals("app", spec.presentation)
        assertEquals("Орбита Луны", spec.title)
        val slider = checkNotNull(spec.action(spec.ui, "speed"))
        assertEquals("slider", slider.getString("type"))
        assertEquals("Скорость", slider.getString("text"))
        lifecycle("preview")
        val ready = drawing()
        assertTrue("The scene label is Russian", "Земля и Луна" in labels())
        assertEquals(210.0, moon().getDouble("x"), 0.0)
        assertEquals(emptySet<String>(), result.frames)
        lifecycle("open")
        lifecycle("start")
        assertEquals(setOf("orbit"), result.frames)
        frame(50)
        assertMoon("Frame advances the visible moon", 0.1)
        speed(3.0)
        assertEquals("The slider keeps the selected speed", 3.0, (boundValue(slider) as Number).toDouble(), 0.0)
        frame(50)
        assertMoon("Higher speed advances the moon farther", 0.4)
        pointer(140.0, 190.0)
        assertMoon("Pointer selects the lower point on the orbit", Math.PI / 2)
        move("right")
        assertMoon("Native movement also positions the moon", 2 * Math.PI / 3)
        lifecycle("pause")
        assertEquals("paused", result.playback)
        assertEquals(emptySet<String>(), result.frames)
        val paused = drawing()
        lifecycle("render")
        assertEquals(paused, drawing())
        lifecycle("restart")
        assertEquals("ready", result.playback)
        assertEquals(spec.initialState, result.state)
        assertEquals(ready, drawing())
    }

    private fun Workflow.starY() = commands("polygon").single().getJSONArray("points").getJSONArray(0).getDouble(1)
    private fun Workflow.moon() = commands("circle").single { it.getString("color") == "warning" }
    private fun Workflow.assertMoon(message: String, angle: Double) {
        assertEquals(message, 140 + 70 * cos(angle), moon().getDouble("x"), 0.000001)
        assertEquals(message, 100 + 70 * sin(angle), moon().getDouble("y"), 0.000001)
    }

    private fun variant(raw: String, original: String, replacement: String) = JSONObject(raw).apply {
        val source = getString("source")
        check(original in source)
        put("source", source.replace(original, replacement))
    }.toString()

    private suspend fun rejects(message: String, play: suspend () -> Unit) {
        val failure = try {
            play()
            null
        } catch (failure: AssertionError) {
            failure
        }
        assertTrue("Expected workflow assertion '$message', got $failure", failure?.message?.contains(message) == true)
    }
}
