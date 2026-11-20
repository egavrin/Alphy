package com.alpha.assistant.tools.appfile

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.DialogueSplit
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.dialogueSplit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan D, Da2: the parts of a kit app by their role on the dialogue stage, from the compile in the sandbox. */
class DialogueSplitTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compiler = AppFileCompiler(context, SandboxClient(context))

    @Test fun lessonPlanPutsTheOrbitAndItsControlsLeft() {
        val (column, split) = split(plan("lesson"))
        assertEquals(listOf("text Alphy Learn"), parts(column, split.leading))
        assertEquals(listOf("card", "numberInput Положение на орбите", "segmented Вращение Луны", "button Один круг"), parts(column, split.main))
        assertEquals(listOf("card", "card"), parts(column, split.side))
        assertEquals("the lesson card", "d.lesson_title_text", column.child(split.side[0]).getJSONArray("children").getJSONObject(1).getString("bind"))
        assertEquals("the question card", "Если Луна не вращается вокруг оси, мы видим только одну её сторону?",
            column.child(split.side[1]).getJSONArray("children").getJSONObject(0).getString("text"))
    }

    @Test fun modelPlanPutsTheModelAndItsRowsLeft() {
        val (column, split) = split(plan("model3d"))
        assertEquals(listOf("text Мастерская"), parts(column, split.leading))
        assertEquals(listOf("card", "flowRow", "flowRow"), parts(column, split.main))
        assertEquals(listOf("rover_model"), column.child(split.main[0]).getJSONArray("children").getJSONObject(0).getJSONArray("surfaces").let { listOf(it.getString(0)) })
        assertEquals(listOf("card"), parts(column, split.side))
        assertEquals("В фокусе", column.child(split.side[0]).getJSONArray("children").getJSONObject(0).getString("text"))
    }

    @Test fun gamePlanKeepsTheStatusTextsLeft() {
        val (column, split) = split(GAME)
        assertEquals(listOf("text Пробуем вместе"), parts(column, split.leading))
        assertEquals(listOf("card", "text", "button 2 клетки", "button 3 клетки", "button 4 клетки", "button Проверить мост",
            "button Убрать последнюю деталь", "text"), parts(column, split.main))
        assertEquals("the status text", "d.status", column.child(split.main.last()).getString("bind"))
        assertEquals(listOf("card"), parts(column, split.side))
        assertEquals("Маленькая миссия", column.child(split.side[0]).getJSONArray("children").getJSONObject(0).getString("text"))
    }

    @Test fun aPlanWithNoMainVisualStaysOneColumn() {
        val (column, split) = split(plan("calc"))
        assertEquals(emptyList<Int>(), split.leading)
        assertEquals(emptyList<Int>(), split.main)
        assertEquals((0 until column.getJSONArray("children").length()).toList(), split.side)
    }

    @Test fun aDeckIsAMainVisual() {
        val (column, split) = split(plan("look-deck-es"))
        assertEquals(listOf("section Repaso de hoy"), parts(column, split.main))
        val (cards, withDeck) = split(plan("cards"))
        assertEquals("the deck section of cards.plan", "Words", cards.child(withDeck.main.first()).getString("text"))
    }

    /** A timer bar and a ring are canvases of a card, and a list is no visual, so each other saved plan and look fixture stays one column. */
    @Test fun eachOtherPlanHasNoMainVisual() {
        val others = SAVED_PLANS.keys - setOf("lesson", "model3d") + listOf("water-ko", "units-pl", "shopping", "habits", "price-filter",
            "look-water-ko", "look-shopping-it", "mock-water-ko", "mock-units-pl", "mock-shopping-it", "mock-habits-uk", "mock-price-filter", "look-guide-es")
        assertEquals(others.associateWith { emptyList<Int>() }, others.associateWith { name -> splitOrNull(plan(name))?.second?.main.orEmpty() })
    }

    /** A root that is not a theme over one column, or a column, has no split. */
    @Test fun anotherRootHasNoSplit() {
        assertEquals(null, dialogueSplit(JSONObject("""{"type":"text","text":"Count","bind":"count"}""")))
        assertEquals(null, dialogueSplit(JSONObject("""{"type":"theme","preset":"clean","children":[{"type":"column","children":[]},{"type":"text","text":"x"}]}""")))
        assertEquals(DialogueSplit(emptyList(), emptyList(), listOf(0)), dialogueSplit(JSONObject("""{"type":"column","children":[{"type":"text","text":"x"}]}""")))
    }

    /** A column with a layout, an align or arrangement style, or a part with a layout places its parts itself, so it has no split. */
    @Test fun aColumnThatPlacesItsPartsHasNoSplit() {
        val canvas = """{"type":"card","children":[{"type":"canvas","id":"c"}]},{"type":"card","children":[]}"""
        for (column in listOf("""{"type":"column","layout":"x","children":[$canvas]}""", """{"type":"column","style":{"align":"center"},"children":[$canvas]}""",
            """{"type":"column","style":{"arrangement":"end"},"children":[$canvas]}""",
            """{"type":"column","children":[$canvas,{"type":"text","text":"x","layout":"x"}]}""")) {
            assertEquals(column, null, dialogueSplit(JSONObject(column)))
        }
        assertEquals(DialogueSplit(emptyList(), listOf(0), listOf(1)), dialogueSplit(JSONObject("""{"type":"column","children":[$canvas]}""")))
    }

    private fun splitOrNull(text: String): Pair<JSONObject, DialogueSplit>? {
        val ui = runBlocking { compiler.compile(text) }.let { result ->
            assertEquals(result.faults.toString(), "ok", result.status)
            ToolSpec.parse(checkNotNull(result.definition)).ui
        }
        val column = if (ui.getString("type") == "theme") ui.getJSONArray("children").getJSONObject(0) else ui
        return dialogueSplit(ui)?.let { column to it }
    }
    private fun split(text: String) = checkNotNull(splitOrNull(text)) { "no split" }
    private fun JSONObject.child(index: Int): JSONObject = getJSONArray("children").getJSONObject(index)
    /** The type of each child of [column] at [indices], with its text when it has one. */
    private fun parts(column: JSONObject, indices: List<Int>) = indices.map { column.child(it).let { node ->
        listOf(node.getString("type"), node.optString("text")).filter(String::isNotEmpty).joinToString(" ") } }

    private companion object {
        /**
         * kit/test/fixtures/dialogue-game.plan, which the kit build does not copy to the test assets. Its line `$/pieces = []` faults in the
         * compile of the app ("starts as an empty list"), so here the list starts with one piece. The parts and their order are the same.
         */
        val GAME = """
            app("Мост для маленького ровера", lang="ru", kind="Пробуем вместе", preset="playful", palette="green")
            ${'$'}/target = 5
            ${'$'}/pieces = [2]
            ${'$'}/checked = false
            ${'$'}/sum := sum(${'$'}/pieces, ${'$'}it)
            ${'$'}/won := ${'$'}/checked && ${'$'}/sum == ${'$'}/target && len(${'$'}/pieces) == 2
            ${'$'}/status := ${'$'}/won ? "Мост подошёл. Ровер едет!" : !${'$'}/checked ? "Нажми на деталь, чтобы поставить её." : len(${'$'}/pieces) < 2 ? "Нужны две разные детали." : ${'$'}/sum < ${'$'}/target ? "Не хватает " + text(${'$'}/target - ${'$'}/sum) + " клеток. Попробуй другую деталь." : "Мост длиннее на " + text(${'$'}/sum - ${'$'}/target) + " клетки. Убери последнюю деталь."
            need scene = drawing("Река, мост и ровер", 320, 170, [rect(0, 0, 320, 170, "#dff3ff"), circle(282, 28, 14, "sun"), rect(0, 96, 320, 74, "water"), rect(0, 80, 40, 90, "land"), rect(280, 80, 40, 90, "land"), each(${'$'}/target, rect(42 + ${'$'}index * 240 / ${'$'}/target, 84, 240 / ${'$'}/target - 4, 14, "ink", radius=3, stroke=true)), each(${'$'}/target, rect(42 + ${'$'}index * 240 / ${'$'}/target, 84, 240 / ${'$'}/target - 4, 14, ${'$'}index < at(${'$'}/pieces, 0) ? "wood" : "sand", radius=3, visible=${'$'}index < ${'$'}/sum)), each(${'$'}/target, text(40 + (${'$'}index + 0.5) * 240 / ${'$'}/target, 91, text(${'$'}index + 1), "muted", size=9, visible=${'$'}index >= ${'$'}/sum)), rect(${'$'}/won ? 286 : 6, 62, 28, 14, "accent", radius=4), circle((${'$'}/won ? 286 : 6) + 6, 78, 4, "ink"), circle((${'$'}/won ? 286 : 6) + 22, 78, 4, "ink")])
            need count = content("Мост: " + text(${'$'}/sum) + " / " + text(${'$'}/target) + " клеток")
            need two = button("2 клетки", [append(${'$'}/pieces, 2), set(${'$'}/checked, false)], enabled=!${'$'}/won && !has(${'$'}/pieces, 2) && len(${'$'}/pieces) < 2)
            need three = button("3 клетки", [append(${'$'}/pieces, 3), set(${'$'}/checked, false)], enabled=!${'$'}/won && !has(${'$'}/pieces, 3) && len(${'$'}/pieces) < 2)
            need four = button("4 клетки", [append(${'$'}/pieces, 4), set(${'$'}/checked, false)], enabled=!${'$'}/won && !has(${'$'}/pieces, 4) && len(${'$'}/pieces) < 2)
            need check = button("Проверить мост", set(${'$'}/checked, true), enabled=!${'$'}/won && len(${'$'}/pieces) > 0)
            need undo = button("Убрать последнюю деталь", [remove(${'$'}/pieces, ${'$'}it == at(${'$'}/pieces, len(${'$'}/pieces) - 1)), set(${'$'}/checked, false)], enabled=len(${'$'}/pieces) > 0)
            need note = content(${'$'}/status)
            need mission = card("Помоги роверу перебраться", "Мост должен быть длиной ровно " + text(${'$'}/target) + " клеток. Используй две разные детали.", eyebrow="Маленькая миссия")
        """.trimIndent() + "\n"
    }
}
