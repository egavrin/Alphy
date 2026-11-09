package com.alpha.assistant.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentChatWorkflowTest {
    @Test fun timedReminderRemainsAnAutomationDraft() {
        val fixture = ShellFixture("agent-timed-reminder")
        val robot = ShellRobot("agent-timed-reminder")
        try {
            fixture.provider.automationDraft = """
                {"name":"Call","trigger":{"type":"schedule","repeat":"once","date":"2030-01-02","time":"08:00","zone":"device","exact":true},
                "conditions":{},"actions":[{"id":"task","capability":"agent.run","args":{"instruction":"Remind me to call the clinic."}}]}
            """.trimIndent()
            fixture.install()
            fixture.launch().use {
                robot.edit(robot.text(R.string.composer_label), "Remind me to call the clinic on January 2, 2030 at 08:00.")
                robot.click(robot.text(R.string.composer_send))
                robot.waitFor(robot.text(R.string.automation_agent_proposal))
                assertEquals(1, fixture.provider.calls)
                assertFalse(checkNotNull(fixture.provider.lastRequest).agentPlan)
            }
        } finally { fixture.remove() }
    }

    @Test fun currentPhoneRequestOpensReviewAndOnlyApprovalChangesLocalMemory() {
        val fixture = ShellFixture("agent-chat")
        val robot = ShellRobot("agent-chat")
        val memoryName = "${fixture.store.databaseName}-memory.db"
        try {
            MemoryStore(fixture.context, memoryName).use { memory ->
                memory.saveExplicitFact("Favorite color is blue")
                fixture.provider.devicePlanNeeds = listOf("Change my memory of my favorite color to green")
                fixture.provider.agentProposal = """
                    {"steps":[{"function":"memory.write","args":{"match":"Favorite color","text":"Favorite color is green"}}]}
                """.trimIndent()
                fixture.install()
                fixture.launch().use {
                    robot.edit(robot.text(R.string.composer_label), "Change my memory of my favorite color to green")
                    robot.click(robot.text(R.string.composer_send))
                    robot.waitFor(robot.text(R.string.agent_plan_title))
                    robot.reveal(robot.text(R.string.agent_execute))
                    assertEquals("Favorite color is blue", memory.list().single().text)
                    assertEquals(2, fixture.provider.calls)
                    val outbound = checkNotNull(fixture.provider.lastRequest).turns.single().text
                    assertFalse(outbound.contains(memory.list().single().id))
                    robot.click(robot.text(R.string.agent_execute))
                    robot.reveal(robot.text(R.string.agent_completed))
                    assertEquals("Favorite color is green", memory.list().single().text)
                    assertEquals(2, fixture.provider.calls)
                }
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase(memoryName)
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
    }

    @Test fun aModelCannotAddAnUnrequestedClockActionToAMemoryPlan() {
        val fixture = ShellFixture("agent-unrequested-action")
        val robot = ShellRobot("agent-unrequested-action")
        try {
            fixture.provider.devicePlanNeeds = listOf("Change my memory of my favorite color to green")
            fixture.provider.agentProposal = """
                {"steps":[{"function":"memory.write","args":{"text":"Unrequested fact"}},
                {"function":"clock.setAlarm","args":{"hour":7,"minute":0}}]}
            """.trimIndent()
            fixture.install()
            fixture.launch().use {
                robot.edit(robot.text(R.string.composer_label), "Change my memory of my favorite color to green")
                robot.click(robot.text(R.string.composer_send))
                robot.waitFor(robot.text(R.string.agent_plan_error))
                assertEquals(3, fixture.provider.calls)
                assertNull(robot.find(robot.text(R.string.agent_execute)))
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
    }

    @Test fun unrequestedDailyRecurrenceCannotEnterReview() {
        val fixture = ShellFixture("agent-unrequested-repeat")
        val robot = ShellRobot("agent-unrequested-repeat")
        try {
            fixture.provider.devicePlanNeeds = listOf("Create a calendar event for a single visit")
            fixture.provider.agentProposal = """
                {"steps":[{"function":"calendar.createOwned","args":{"calendar":"Work","title":"Visit",
                "start":1893485700000,"end":1893487500000,"timeZone":"UTC","reminderMinutes":15,"repeatDaily":true}}]}
            """.trimIndent()
            fixture.install()
            fixture.launch().use {
                robot.edit(robot.text(R.string.composer_label), "Create a calendar event for a single visit")
                robot.click(robot.text(R.string.composer_send))
                robot.waitFor(robot.text(R.string.agent_plan_error))
                assertEquals(3, fixture.provider.calls)
                assertNull(robot.find(robot.text(R.string.agent_execute)))
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
    }
}
