package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.tools.agent.AppAgentGuide
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Failure case: the app agent spent its reasoning on doubts about rules (timers without a surface, row template payloads,
 * hidden nodes), compared layouts, and planned every detail before the first write. 40 raw samples on 2026-11-17.
 */
@RunWith(AndroidJUnit4::class)
class AgentGuideRuntimeFactsTest {
    private val guide = AppAgentGuide.system("", "")

    @Test fun theGuideAnswersTheRuntimeDoubts() {
        for (part in listOf(
            "Runtime facts. These answers are exact.",
            "need a declared drawing surface. The host rejects an app without a surface that returns timers.",
            "Each wait starts the timer intervals again",
            "this write is the final app after one screenshot review",
            "A tick only wakes the source. Do not count ticks.",
            "at most $MAX_PROBE_EVENTS events in one wait",
            "Each control in a rowTemplate sends event.value={key,value}.",
            "sends the value to the source at once",
        )) assertTrue(part, guide.contains(part))
        // Review finding: ToolGenerationSmoke rejects source timers in an app without a surface (widget_timer_host).
        assertFalse("the guide says that timers run without a drawing surface", guide.contains("also without a drawing surface"))
    }

    @Test fun theGuideGivesLayoutDefaults() {
        assertTrue(guide.contains("Layout defaults. Use them without comparing other layouts"))
        // Review finding: switch, edit icon, and delete icon are not catalog names, and games keep ui for settings only.
        for (part in listOf("checkbox or toggle", "Trash icon", "Pencil icon", "Outside games, put settings")) assertTrue(part, guide.contains(part))
        assertFalse(guide.contains("or switch if it has one"))
    }

    @Test fun theGuideAsksForAQuickFirstVersion() {
        assertTrue(guide.contains("Write a working first version quickly with write_app"))
        assertFalse(guide.contains("Make it correct and finished in its first version"))
    }
}
