package com.alpha.assistant.tools

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Failure case: list rows showed "Model: Trek Domane" and "Rank: Rank 1", because a bound text needed a label
 * of 1 to 300 characters and the renderer joined it with the value.
 */
class BoundValueTextWorkflowTest {
    private val robot = ShellRobot("bound-value-text")

    @Test fun aBoundRowTextWithAnEmptyLabelShowsOnlyItsValue() {
        val spec = ToolSpec.parse(JSONObject("""{"formatVersion":3,"title":"Bikes","description":"Used bikes","capabilities":[],
            "stateSchema":{"bikes":{"type":"list","maxItems":5,"fields":{"id":"string","name":"string","price":"string"}}},
            "initialState":{"bikes":[{"id":"a","name":"Trek Domane","price":"1,450"}]},
            "ui":{"type":"column","children":[{"type":"list","text":"Bikes","bind":"bikes","rowTemplate":{"type":"row","wrap":false,"children":[
                {"type":"text","text":"","bind":"name","layout":{"weight":1}},{"type":"badge","text":"","bind":"price"}]}}]},
            "source":"return {state: input.state};"}""").toString())
        val fixture = ShellFixture("bound-value-text")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                ToolContent(spec.ui.toString(), spec.initialState, enabled = true, appLanguage = "en") { _, _ -> }
                            }
                        }
                    }
                }
                robot.waitFor("Trek Domane")
                robot.waitFor("1,450")
                assertNull(robot.texts().toString(), robot.texts().firstOrNull { it.startsWith(":") })
            }
        } finally { fixture.remove() }
    }
}
