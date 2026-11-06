package com.alpha.assistant.scheduling

import android.app.job.JobScheduler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolNetworkGrants
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.surfaces.ToolGlanceWidget
import com.alpha.assistant.surfaces.WidgetPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceRefreshWorkflowTest {
    @Test fun androidJobKeepsLastValueAndArmsTheNextOccurrence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tool = readingTool()
        val offer = readingOffer(tool, "https://127.0.0.1:1/closed")
        val scheduler = "cmd jobscheduler"
        val namespace = "-n alphy-source-refresh ${context.packageName}"
        ToolStore(context).use { store ->
            val refresh = SourceRefresh(store) { System.currentTimeMillis() - SourceRefresh.INTERVAL - 5_000 }
            val network = ToolNetworkGrants.authority(store)
            try {
                store.saveTool(tool, true)
                runBlocking {
                    checkNotNull(network.refreshAccess())
                    network.setGranted(tool.id, true).getOrThrow()
                }
                assertTrue(refresh.approve(offer))
                val sample = JSONObject().put("value", 42).put("source", offer.endpoint).put("dataTimeMs", 1_000L)
                val older = checkNotNull(refresh.begin(offer) { true })
                val current = checkNotNull(refresh.begin(offer) { true })
                assertFalse(refresh.record(older, null) { true })
                assertTrue(refresh.record(current, sample) { true })
                val ticket = checkNotNull(refresh.activate(offer))
                assertTrue(SourceRefreshJobService.enqueue(store, ticket))
                val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                    .executeShellCommand("$scheduler run -f $namespace ${ticket.jobId}")).bufferedReader().use { it.readText() }
                assertTrue(output, "Running job" in output || "not found" in output.lowercase() || output.isBlank())
                val until = SystemClock.elapsedRealtime() + 10_000
                while (refresh.ticket(tool.id) == ticket && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
                val next = checkNotNull(refresh.ticket(tool.id))
                assertNotEquals(ticket.jobId, next.jobId)
                assertEquals(ticket.due + SourceRefresh.INTERVAL, next.due)
                val status = checkNotNull(refresh.status(tool.id))
                assertTrue(status.failed)
                assertEquals(42, status.sample?.getInt("value"))
                val widget = runBlocking { ToolGlanceWidget.preview(context, tool, WidgetPreferences()) }
                val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(1_000L))
                instrumentation.runOnMainSync {
                    val labels = labels(widget.apply(context, null)).toList()
                    assertTrue(labels.toString(), labels.contains("42"))
                    val provenance = context.getString(com.alpha.assistant.R.string.live_source_widget_stale,
                        "127.0.0.1:1", timestamp)
                    assertTrue(labels.toString(), labels.contains(provenance))
                }
                val jobs = context.getSystemService(JobScheduler::class.java).forNamespace("alphy-source-refresh")
                while (jobs.getPendingJob(next.jobId) == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
                assertTrue(checkNotNull(jobs.getPendingJob(next.jobId)).isPersisted)
            } finally {
                refresh.cancel(tool.id)?.let { SourceRefreshJobService.cancel(store, it) }
                runBlocking { network.setGranted(tool.id, false).getOrThrow() }
                store.transaction {
                    store.writableDatabase.delete("live_sources", "owner=?", arrayOf(tool.id))
                    store.writableDatabase.delete("revisions", "tool_id=?", arrayOf(tool.id))
                    store.writableDatabase.delete("tools", "id=?", arrayOf(tool.id))
                }
            }
        }
    }

    @Test fun scheduledSourceClaimsSurviveReopenAndRevocationDoesNotReviveThem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "source-refresh-${UUID.randomUUID()}.db"
        val tool = readingTool()
        val offer = readingOffer(tool, "https://example.com/current")
        var now = 1_000L
        var calls = 0
        val executor = ScheduleExecutor { _, _ -> calls++; ScheduleActionResult.COMPLETED }
        try {
            val ticket = ToolStore(context, name).use { store ->
                store.saveTool(tool, true)
                store.setNetworkGrant(tool.id, true)
                val refresh = SourceRefresh(store) { now }
                assertNull(refresh.activate(offer))
                assertTrue(refresh.approve(offer))
                checkNotNull(refresh.activate(offer))
            }
            ToolStore(context, name).use { store ->
                val refresh = SourceRefresh(store) { now }
                val authority = ScheduleAuthority(refresh, { now }, executor)
                assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(ticket.delivery))
                now = ticket.due
                assertEquals(ScheduleDeliveryResult.COMPLETED, authority.deliver(ticket.delivery))
                assertEquals(1, calls)
            }
            ToolStore(context, name).use { store ->
                val refresh = SourceRefresh(store) { now }
                val authority = ScheduleAuthority(refresh, { now }, executor)
                assertEquals(ScheduleDeliveryResult.ALREADY_CLAIMED, authority.deliver(ticket.delivery))
                val next = checkNotNull(refresh.advance(ticket))
                assertNotEquals(ticket.jobId, next.jobId)
                now = next.due + SourceRefresh.INTERVAL
                assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(next.delivery))
                store.setNetworkGrant(tool.id, false)
                store.setNetworkGrant(tool.id, true)
                assertNull(refresh.ticket(tool.id))
                assertFalse(refresh.current(next))
                val replacement = checkNotNull(refresh.activate(offer))
                assertNotEquals(ticket.delivery.activationId, replacement.delivery.activationId)
                assertEquals(ScheduleDeliveryResult.REJECTED, authority.deliver(ticket.delivery))
                assertEquals(1, calls)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun readingTool(): ToolRecord {
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Reading","description":"","capabilities":[],"stateSchema":{"value":"number"},"initialState":{"value":0},"ui":{"type":"text","text":"Reading"},"source":"return {state:input.state};"}""")
        return ToolRecord(UUID.randomUUID().toString(), 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
    }

    private fun readingOffer(tool: ToolRecord, url: String) = checkNotNull(SourceOffer.from(tool, "reading",
        JSONObject("""{"method":"GET","url":"$url","extract":{"kind":"number","path":["value"]}}""")))

    private fun labels(view: View): Sequence<String> = sequence {
        if (view is TextView) yield(view.text.toString())
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(labels(view.getChildAt(index)))
        }
    }
}
