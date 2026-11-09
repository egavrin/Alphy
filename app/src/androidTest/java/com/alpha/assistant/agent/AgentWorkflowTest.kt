package com.alpha.assistant.agent

import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.SyntheticOwnedCalendar
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ArtifactCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val initialTime = 1893485700000L

    @Test fun oneApprovalMovesOnlyTheSelectedOccurrenceAndAddsItsAlert(): Unit = runBlocking {
        SyntheticOwnedCalendar(context).use { calendar ->
            val resolver = context.contentResolver
            val account = resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id),
                arrayOf(Calendars.OWNER_ACCOUNT), null, null, null)!!.use { assertTrue(it.moveToFirst()); it.getString(0) }
            val master = ContentUris.parseId(checkNotNull(resolver.insert(Events.CONTENT_URI, ContentValues().apply {
                put(Events.CALENDAR_ID, calendar.id)
                put(Events.ORGANIZER, account)
                put(Events.TITLE, "Alphy agent recurring visit")
                put(Events.DTSTART, initialTime)
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.DURATION, "PT30M")
                put(Events.RRULE, "FREQ=DAILY;COUNT=2")
            })))
            val occurrence = initialTime + 86_400_000
            val proposed = occurrence + 3_600_000
            withAgent { agent, _, _ ->
                val review = agent.prepare("chat", "Move the second calendar visit and add a reminder", """
                    {"steps":[{"function":"calendar.updateEvent","args":{"title":"Alphy agent recurring visit",
                    "from":$occurrence,"to":${occurrence + 3_600_000},"start":$proposed,"reminderMinutes":15}}]}
                """.trimIndent())
                assertEquals(AgentPhase.REVIEW, review.phase)
                assertEquals(initialTime, eventStarts(master).single())
                val run = checkNotNull(agent.approve(review.id, review.revision))
                assertNull(agent.approve(review.id, review.revision))
                val completed = agent.execute(run, { true }, {})
                assertEquals(completed.results.toString(), AgentPhase.COMPLETED, completed.phase)
                assertEquals(initialTime, eventStarts(master).single())
                val exception = resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.DTSTART),
                    "${Events.ORIGINAL_ID}=? AND ${Events.ORIGINAL_INSTANCE_TIME}=?", arrayOf(master.toString(), occurrence.toString()), null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(proposed, it.getLong(1)); it.getLong(0).also { _ -> assertFalse(it.moveToNext()) }
                }
                resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID}=?",
                    arrayOf(exception.toString()), null)!!.use { assertTrue(it.moveToFirst()); assertEquals(15, it.getInt(0)); assertFalse(it.moveToNext()) }
            }
        }
    }

    @Test fun invitationFromAnotherOrganizerCannotBeApproved(): Unit = runBlocking {
        SyntheticOwnedCalendar(context).use { calendar ->
            val resolver = context.contentResolver
            val event = ContentUris.parseId(checkNotNull(resolver.insert(Events.CONTENT_URI, ContentValues().apply {
                put(Events.CALENDAR_ID, calendar.id)
                put(Events.ORGANIZER, "other-organizer@example.com")
                put(Events.TITLE, "A read-only invitation")
                put(Events.DTSTART, initialTime)
                put(Events.DTEND, initialTime + 1_800_000)
                put(Events.EVENT_TIMEZONE, "UTC")
            })))
            withAgent { agent, _, _ ->
                val plan = agent.prepare("chat", "Move the calendar invitation", """
                    {"steps":[{"function":"calendar.updateEvent","args":{"title":"A read-only invitation",
                    "from":$initialTime,"to":${initialTime + 3_600_000},"start":${initialTime + 7_200_000}}}]}
                """.trimIndent())
                assertEquals(AgentPhase.UNAVAILABLE, plan.phase)
                assertTrue(plan.steps.single().options.isEmpty())
                assertNull(agent.approve(plan.id, plan.revision))
                assertEquals(listOf(initialTime), eventStarts(event))
            }
        }
    }

    @Test fun staleTargetCannotWriteMemory(): Unit = runBlocking {
        withAgent { agent, memory, _ ->
            val fact = memory.saveExplicitFact("Old preference")
            val edit = agent.prepare("chat", "Change my memory preference", """
                {"steps":[{"function":"memory.write","args":{"match":"Old preference","text":"New preference"}}]}
            """.trimIndent())
            memory.edit(fact.id, fact.revision, "Edited outside the agent")
            val run = checkNotNull(agent.approve(edit.id, edit.revision))
            assertEquals(AgentPhase.FAILED, agent.execute(run, { true }, {}).phase)
            assertEquals("Edited outside the agent", memory.list().single().text)
            assertNull(agent.approve(edit.id, edit.revision))
        }
    }

    @Test fun ambiguousTargetRequiresLocalSelectionAndForbiddenOperationsAreRejected(): Unit = runBlocking {
        withAgent { agent, memory, _ ->
            memory.saveExplicitFact("Favorite color is blue")
            memory.saveExplicitFact("Favorite color is green")
            val plan = agent.prepare("chat", "Edit my memory of my favorite color", """
                {"steps":[{"function":"memory.write","args":{"match":"Favorite color","text":"Favorite color is orange"}}]}
            """.trimIndent())
            assertEquals(AgentPhase.NEEDS_SELECTION, plan.phase)
            assertNull(agent.approve(plan.id, plan.revision))
            val selected = agent.select(plan.id, plan.revision, 0, 1)
            assertEquals(AgentPhase.REVIEW, selected.phase)
            assertEquals(2, memory.list().size)
            assertThrows(IllegalArgumentException::class.java) {
                AgentCommand.parse("""{"steps":[{"function":"sms.send","args":{}}]}""")
            }
        }
    }

    @Test fun stoppingBetweenStepsPreservesTheFirstAndSkipsTheSecond(): Unit = runBlocking {
        withAgent { agent, memory, _ ->
            val plan = twoMemoryWrites(agent)
            val run = checkNotNull(agent.approve(plan.id, plan.revision))
            val stopped = agent.execute(run, { true }, {}, onUpdate = { state ->
                if (state.results.size == 1) agent.stop(run.id)
            })
            assertEquals(AgentPhase.STOPPED, stopped.phase)
            assertEquals(listOf("First approved fact"), memory.list().map { it.text })
            assertNull(agent.approve(plan.id, plan.revision))
        }
    }

    @Test fun interruptedClaimStaysInTheJournalAndCannotReplay(): Unit = runBlocking {
        withAgent { agent, memory, _ ->
            val plan = twoMemoryWrites(agent)
            val run = checkNotNull(agent.approve(plan.id, plan.revision))
            val result = runCatching {
                agent.execute(run, { true }, {}, onUpdate = { state ->
                    if (state.claimed == 0) throw kotlinx.coroutines.CancellationException("Interrupted claim fixture")
                })
            }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(AgentPhase.INTERRUPTED, agent.latest("chat")?.phase)
            assertEquals(AgentPhase.INTERRUPTED, agent.execute(run, { true }, {}).phase)
            assertTrue(memory.list().isEmpty())
        }
    }

    @Test fun stopWhileArtifactResolutionWaitsPreventsItsWrite(): Unit = runBlocking {
        withAgent { agent, _, tools ->
            val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Counter","description":"","capabilities":[],
                "stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"text","text":"Count","bind":"count"},
                "source":"return {state:input.state};"}""")
            val item = ToolRecord("counter", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
            tools.saveTool(item, true)
            val plan = agent.prepare("chat", "Change the mini-app Counter", """
                {"steps":[{"function":"artifact.setState","args":{"app":"Counter","field":"count","value":2}}]}
            """.trimIndent())
            val run = checkNotNull(agent.approve(plan.id, plan.revision))
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lock = async {
                ArtifactCoordinator.get(tools, item.id).ordered { held.complete(Unit); release.await() }
            }
            held.await()
            val checking = CompletableDeferred<Unit>()
            val executing = async {
                var activeChecks = 0
                agent.execute(run, { if (++activeChecks == 2) checking.complete(Unit); true }, {})
            }
            checking.await()
            agent.stop(run.id)
            release.complete(Unit)
            lock.await()
            assertEquals(AgentPhase.STOPPED, executing.await().phase)
            assertEquals(item, tools.tool(item.id))
        }
    }

    @Test fun dailyCalendarReviewListsTheRepeatZoneAndLocationBeforeSaving(): Unit = runBlocking {
        SyntheticOwnedCalendar(context).use {
            withAgent { agent, _, _ ->
                val plan = agent.prepare("chat", "Create a daily calendar visit", """
                    {"steps":[{"function":"calendar.createOwned","args":{"calendar":"Alphy synthetic calendar",
                    "title":"Daily reviewed visit","start":$initialTime,"end":${initialTime + 1_800_000},
                    "timeZone":"America/New_York","reminderMinutes":15,"location":"Test clinic","repeatDaily":true}}]}
                """.trimIndent())
                val after = checkNotNull(plan.steps.single().target).after
                assertTrue(after.contains("01.01.2030 03:15 – 01.01.2030 03:45"))
                assertTrue(after.contains("America/New_York"))
                assertTrue(after.contains("Test clinic"))
                assertTrue(after.contains(context.getString(com.alpha.assistant.R.string.agent_calendar_daily)))
                val run = checkNotNull(agent.approve(plan.id, plan.revision))
                assertEquals(AgentPhase.COMPLETED, agent.execute(run, { true }, {}).phase)
                context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events.RRULE, Events.EVENT_LOCATION),
                    "${Events.TITLE}=? AND ${Events.CALENDAR_ID}=?", arrayOf("Daily reviewed visit", it.id.toString()), null)!!.use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("FREQ=DAILY", rows.getString(0))
                    assertEquals("Test clinic", rows.getString(1))
                    assertFalse(rows.moveToNext())
                }
            }
        }
    }

    private suspend fun twoMemoryWrites(agent: AgentRuntime) = agent.prepare("chat", "Save two memory facts", """
        {"steps":[{"function":"memory.write","args":{"text":"First approved fact"}},
        {"function":"memory.write","args":{"text":"Second approved fact"}}]}
    """.trimIndent())

    private fun eventStarts(id: Long): List<Long> = context.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
        arrayOf(Events.DTSTART), null, null, null)!!.use { rows -> buildList { while (rows.moveToNext()) add(rows.getLong(0)) } }

    private suspend fun withAgent(test: suspend (AgentRuntime, MemoryStore, ToolStore) -> Unit) {
        val prefix = "phone-agent-${UUID.randomUUID()}"
        try {
            MemoryStore(context, "$prefix-memory.db").use { memory ->
                ToolStore(context, "$prefix-tools.db").use { tools ->
                    AgentRuntime(context, "$prefix.db", memory, tools).use { agent -> test(agent, memory, tools) }
                }
            }
        } finally {
            listOf("$prefix.db", "$prefix-memory.db", "$prefix-tools.db").forEach(context::deleteDatabase)
        }
    }
}
