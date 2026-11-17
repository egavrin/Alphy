package com.alpha.assistant.tools

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.alpha.assistant.tools.agent.AppScreen
import com.alpha.assistant.tools.three.ThreeSceneFailure
import com.alpha.assistant.tools.three.ThreeSceneHud
import com.alpha.assistant.tools.three.ThreeSceneProblem
import com.alpha.assistant.tools.three.ThreeSceneSnapshot
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure case: a 3D game showed its score only in native text under the viewport, so the game was not
 * self-contained and the score was far from the play. A snapshot hud draws 2D commands over the scene.
 * These checks need no qualified WebView.
 */
class ThreeSceneHudWorkflowTest {
    private val robot = ShellRobot("three-hud")
    private val spec = ToolSpec.parse("""
        {"formatVersion":3,"title":"Dice","description":"","capabilities":[],"runtime":{"kind":"threejs-worker","version":1},
        "presentation":{"mode":"immersive"},"stateSchema":{"score":"number"},"initialState":{"score":2},
        "ui":{"type":"text","text":"Score","bind":"score"},
        "source":"export async function createScene(context){return {event(){},frame(){},snapshot(){return {state:context.state};},dispose(){}};}"}
    """.trimIndent())
    private val score = """{"width":360,"height":640,"commands":[{"type":"text","x":20,"y":40,"size":28,"text":"Score 2","color":"default"}]}"""

    private fun snapshot(hud: String) =
        ThreeSceneSnapshot.validated(spec, JSONObject().put("state", JSONObject("""{"score":2}""")).put("hud", JSONObject(hud)), spec.ui.toString())

    @Test fun anInvalidHudCommandIsRejected() {
        val failure = runCatching { snapshot("""{"width":360,"height":640,"commands":[{"type":"circle","x":10}]}""") }.exceptionOrNull()
        assertEquals(ThreeSceneProblem.INVALID_HUD, (failure as? ThreeSceneFailure)?.problem)
        val unknown = runCatching { snapshot("""{"width":360,"height":640,"commands":[],"color":"red"}""") }.exceptionOrNull()
        assertEquals(ThreeSceneProblem.INVALID_HUD, (unknown as? ThreeSceneFailure)?.problem)
    }

    /** Review finding: a scene that returned hud:null before a game started stopped with an invalid hud. */
    @Test fun aNullHudIsNoLayer() {
        val checked = ThreeSceneSnapshot.validated(spec, JSONObject().put("state", JSONObject("""{"score":2}""")).put("hud", JSONObject.NULL), spec.ui.toString())
        assertEquals(null, checked.hud)
    }

    @Test fun hudTextJoinsTheAgentScreen() {
        val checked = snapshot(score)
        val screen = AppScreen.render(JSONObject(checked.ui), JSONObject(checked.state), checked.hudSurfaces("scene"), 0L, checked.hudSizes("scene")).text
        assertTrue(screen, screen.contains("Score 2"))
    }

    @Test fun hudTextJoinsTheViewportDescription() {
        val fixture = ShellFixture("three-hud")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { AlphaTheme { Box(Modifier.fillMaxSize()) { ThreeSceneHud(score, "Dice") } } }
                }
                robot.waitFor("Dice. Score 2.")
            }
        } finally { fixture.remove() }
    }
}
