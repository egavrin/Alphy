package com.alpha.assistant.ui

import com.alpha.assistant.R
import com.alpha.assistant.data.Draft
import com.alpha.assistant.surfaces.AssistContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewedContextLearningWorkflowTest {
    @Test fun attachmentSheetKeepsCreationInTheComposer() {
        val fixture = ShellFixture("context-learning")
        val robot = ShellRobot("context-learning")
        val original = Draft("Spanish conversation", listOf(AssistContext.textAttachment("Private notes")))
        fixture.store.saveNewDraft(original)
        fixture.install()
        try {
            fixture.launch().use {
                robot.click(robot.text(R.string.composer_add_context))
                robot.waitFor(robot.text(R.string.context_title))
                robot.waitFor(robot.text(R.string.context_file))
                assertNull(robot.find(robot.text(R.string.course_learning)))
                assertNull(robot.find(robot.text(R.string.context_mini_app)))
                assertNull(robot.find(robot.text(R.string.context_automation)))
                robot.click(robot.text(R.string.cancel))
                robot.waitGone(robot.text(R.string.context_title))
                assertEquals(original, fixture.store.load().draft)
                assertEquals(0, fixture.provider.calls)
            }
        } finally {
            fixture.remove()
        }
    }
}
