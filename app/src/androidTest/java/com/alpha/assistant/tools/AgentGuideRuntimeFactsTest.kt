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
            "run in every app, also without a drawing surface",
            "A tick only wakes the source. Do not count ticks.",
            "at most $MAX_PROBE_EVENTS events in one wait",
            "Each control in a rowTemplate sends event.value={key,value}.",
            "sends the value to the source at once",
        )) assertTrue(part, guide.contains(part))
        assertFalse("the guide still says that timers need a drawing surface",
            guide.contains("use a drawing surface with frame or timer subscriptions"))
    }

    @Test fun theGuideGivesLayoutDefaults() {
        assertTrue(guide.contains("Layout defaults. Use them without comparing other layouts"))
    }

    @Test fun theGuideAsksForAQuickFirstVersion() {
        assertTrue(guide.contains("Write a working first version quickly with write_app"))
        assertFalse(guide.contains("Make it correct and finished in its first version"))
    }
}
