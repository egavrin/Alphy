package com.alpha.assistant.scheduling

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecurringSearchWorkflowTest {
    @Test fun reservesBeforeProviderWorkAndKeepsQuotaAcrossReactivation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recurring-search-${UUID.randomUUID()}.db"
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Sources","description":"","capabilities":[],"stateSchema":{"n":"number"},"initialState":{"n":0},"ui":{"type":"text","text":"Sources"},"source":"return {state:input.state};"}""")
        val tools = (1..5).map { ToolRecord("search-$it", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true) }
        var now = 100_000L
        var authorization = "provider-approval"
        try {
            ToolStore(context, name).use { store ->
                tools.forEach { store.saveTool(it, true) }
                val search = RecurringSearch(store, { authorization }) { now }
                val tickets = tools.take(4).map { checkNotNull(search.activate(it, "Fixed query")) }
                assertNull(search.activate(tools.last(), "Fixed query"))
                assertEquals(tickets[0], search.activate(tools[0], "Fixed query"))
                assertEquals(RecurringAdmission.Skip, search.reserve(tickets[0]))
                now = tickets[0].due + 1
                val first = (search.reserve(tickets[0]) as RecurringAdmission.Reserved).run
                assertEquals(RecurringAdmission.Skip, search.reserve(tickets[0]))
                assertEquals(RecurringAdmission.Deferred, search.reserve(tickets[1]))
                search.finish(first, null)
                val second = (search.reserve(tickets[1]) as RecurringAdmission.Reserved).run
                search.finish(second, null)
                assertEquals("Fixed query", first.query)
                val tooSoon = checkNotNull(search.advance(tickets[0]))
                now = tooSoon.due
                assertEquals(RecurringAdmission.Deferred, search.reserve(tooSoon))
                repeat(3) { index ->
                    val previous = checkNotNull(search.ticket(tools[0].id))
                    assertNull(search.cancel(tools[0].id, "stale-activation"))
                    assertEquals(previous, search.cancel(tools[0].id, previous.delivery.activationId))
                    now += 1
                    val ticket = checkNotNull(search.activate(tools[0], "Fixed query $index"))
                    // Simulate close windows with approved 6-hour schedules by rolling the wall clock forward.
                    now = ticket.due
                    search.finish((search.reserve(ticket) as RecurringAdmission.Reserved).run, null)
                    assertEquals(RecurringAdmission.Skip, search.reserve(ticket))
                }
                val active = checkNotNull(search.status(tools[0].id))
                authorization = "replacement-provider-approval"
                assertNull(search.status(tools[0].id))
                val newTicket = checkNotNull(search.activate(tools[0], "Fixed query"))
                assertNotEquals(active.ticket.delivery.activationId, newTicket.delivery.activationId)
                assertEquals(RecurringAdmission.Skip, search.reserve(active.ticket))
                assertTrue(newTicket.due >= now + RecurringSearch.INTERVAL)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
