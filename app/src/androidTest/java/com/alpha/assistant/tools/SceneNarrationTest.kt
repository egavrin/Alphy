package com.alpha.assistant.tools

import org.junit.Assert.*
import org.junit.Test

/** Plan "one scene", Task 27: the host reads the narration lines of a stored template file as the kit keeps them (decision 2). */
class SceneNarrationTest {
    private val head = "scene(\"Дыхание\", lang=\"ru\")\n"
    private val card = "card = card(\"Дыхание\", \"Вдох и выдох.\")\n"

    @Test fun sceneNarrationReadsEachKind() {
        val steps = SceneNarration.of(head + "control = steps(\"Фаза\", [\"Вдох\", \"Выдох\", \"Пауза\"], \"Выдох\")\n" + card +
            "say1 = \"Сначала вдох.\"\nsay2 = \"Потом выдох.\"\nsay3 = \"В паузе всё замирает.\"\n")
        assertEquals(SceneNarration(true, mapOf(1 to "Сначала вдох.", 2 to "Потом выдох.", 3 to "В паузе всё замирает."), null), steps)
        assertEquals("Потом выдох.", steps.sentence(2))
        assertNull(steps.sentence(4))
        val slider = SceneNarration.of(head + "control = slider(\"Сила\", 1, 5, 1, 3)\n" + card + "say = \"Двигай силу и смотри на пружину.\"\n")
        assertEquals(SceneNarration(false, emptyMap(), "Двигай силу и смотри на пружину."), slider)
        assertEquals(SceneNarration(false, emptyMap(), null), SceneNarration.of(head + "control = choice(\"Фаза\", [\"Вдох\", \"Выдох\"])\n" + card))
        assertEquals(SceneNarration(false, emptyMap(), null), SceneNarration.of(null))
        // The kit writes a quote mark inside a sentence as \", and the host reads the sentence back unchanged.
        val quoted = SceneNarration.of(head + "control = steps(\"Фаза\", [\"Вдох\", \"Выдох\"])\n" + card +
            "say1 = \"Скажи \\\"вдох\\\" и «выдох».\"\n")
        assertEquals(mapOf(1 to "Скажи \"вдох\" и «выдох»."), quoted.sentences)
        assertTrue(quoted.stepped)
    }
}
