package com.alpha.assistant.scheduling

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.alpha.assistant.cloud.requireWebSearchQuery
import com.alpha.assistant.data.Source
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class RecurringSearchStatus(
    val ticket: ScheduledTicket, val query: String, val credential: String, val revision: Int,
    val createdAt: Long, val result: String?, val observedAt: Long, val failed: Boolean,
)
internal data class RecurringSearchRun(val ticket: ScheduledTicket, val query: String, val credential: String)
internal sealed interface RecurringAdmission {
    data class Reserved(val run: RecurringSearchRun) : RecurringAdmission
    data object Deferred : RecurringAdmission
    data object Skip : RecurringAdmission
}

internal class RecurringSearch(
    private val store: ToolStore, private val credential: () -> String?, private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = store.writableDatabase

    fun status(owner: String): RecurringSearchStatus? {
        val scheduled = read(owner) ?: return null
        val tool = store.tool(owner) ?: return null
        return scheduled.takeIf {
            tool.saved && !tool.archived && it.revision == tool.revision && it.createdAt == tool.createdAt &&
                credential() == it.credential
        }
    }

    private fun read(owner: String): RecurringSearchStatus? = db.rawQuery(
        "SELECT slot,activation,turn,due,query,credential,revision,created_at,result,observed_at,failed FROM recurring_searches WHERE owner=?",
        arrayOf(owner)
    ).use {
        if (!it.moveToFirst()) return@use null
        val due = it.getLong(3)
        val key = ScheduleKey(ScheduleOwnerKind.MINI_APP, owner, ID)
        val ticket = ScheduledTicket(ScheduleDelivery(key, it.getString(1), due.toString()), it.getInt(0), it.getInt(2), due)
        RecurringSearchStatus(ticket, it.getString(4), it.getString(5), it.getInt(6), it.getLong(7),
            it.getString(8), it.getLong(9), it.getInt(10) != 0)
    }

    fun activate(tool: ToolRecord, query: String): ScheduledTicket? = store.inTransaction {
        requireWebSearchQuery(query)
        val grant = credential() ?: return@inTransaction null
        val current = store.tool(tool.id) ?: return@inTransaction null
        if (!current.saved || current.archived || current.revision != tool.revision || current.createdAt != tool.createdAt)
            return@inTransaction null
        val existing = status(tool.id)
        if (existing?.query == query) return@inTransaction existing.ticket
        db.delete("recurring_searches", "credential<>?", arrayOf(grant))
        if (existing == null && count("SELECT COUNT(*) FROM recurring_searches", emptyArray()) >= 4)
            return@inTransaction null
        db.replaceOrThrow("recurring_searches", null, ContentValues().apply {
            put("owner", tool.id)
            put("activation", UUID.randomUUID().toString())
            put("query", query)
            put("credential", grant)
            put("revision", current.revision)
            put("created_at", current.createdAt)
            put("due", clock() + INTERVAL)
            put("turn", 0)
            put("observed_at", 0)
            put("failed", 0)
        })
        status(tool.id)?.ticket
    }

    fun cancel(owner: String, activation: String): ScheduledTicket? = store.inTransaction {
        val previous = read(owner)?.ticket?.takeIf { it.delivery.activationId == activation } ?: return@inTransaction null
        stop(store, owner)
        previous
    }

    fun ticket(owner: String) = read(owner)?.ticket

    /** The occurrence claim, quota debit, and process-local in-flight slot share one durable commit. */
    fun reserve(ticket: ScheduledTicket): RecurringAdmission = store.inTransaction {
        val owner = ticket.delivery.key.ownerId
        val current = status(owner)?.takeIf { it.ticket == ticket } ?: return@inTransaction RecurringAdmission.Skip
        val now = clock()
        if (now !in ScheduleDeliveryWindow(ticket.due, ticket.due + INTERVAL)) return@inTransaction RecurringAdmission.Skip
        if (count("SELECT COUNT(*) FROM recurring_search_runs WHERE activation=? AND due=?",
                arrayOf(ticket.delivery.activationId, ticket.delivery.occurrenceId)) != 0) return@inTransaction RecurringAdmission.Skip
        val since = (now - DAY).toString()
        val session = ToolStore.processSession
        db.delete("recurring_search_runs", "started<=? AND (session<>? OR running=0)", arrayOf(since, session))
        if (count("SELECT COUNT(*) FROM recurring_search_runs WHERE session=? AND running=1", arrayOf(session)) != 0 ||
            count("SELECT COUNT(*) FROM recurring_search_runs WHERE owner=? AND started>?", arrayOf(owner, (now - INTERVAL).toString())) != 0 ||
            count("SELECT COUNT(*) FROM recurring_search_runs WHERE owner=? AND started>?", arrayOf(owner, since)) >= 4 ||
            count("SELECT COUNT(*) FROM recurring_search_runs WHERE started>?", arrayOf(since)) >= 16)
            return@inTransaction RecurringAdmission.Deferred
        val inserted = db.insertWithOnConflict("recurring_search_runs", null, ContentValues().apply {
            put("owner", owner)
            put("activation", ticket.delivery.activationId)
            put("due", ticket.due)
            put("started", now)
            put("session", session)
            put("running", 1)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted < 0) RecurringAdmission.Skip
        else RecurringAdmission.Reserved(RecurringSearchRun(ticket, current.query, current.credential))
    }

    fun authorized(run: RecurringSearchRun): Boolean =
        status(run.ticket.delivery.key.ownerId)?.let { it.ticket == run.ticket && it.credential == run.credential } == true &&
            clock() in ScheduleDeliveryWindow(run.ticket.due, run.ticket.due + INTERVAL)

    fun finish(run: RecurringSearchRun, results: List<Source>?, publish: Boolean = true) = store.transaction {
        val ticket = run.ticket
        val owner = ticket.delivery.key.ownerId
        val affected = db.update("recurring_search_runs", ContentValues().apply { put("running", 0) },
            "activation=? AND due=? AND running=1 AND session=?",
            arrayOf(ticket.delivery.activationId, ticket.delivery.occurrenceId, ToolStore.processSession))
        if (affected == 1 && publish && authorized(run)) {
            val data = results?.take(5)?.let { hits ->
                JSONArray(hits.map { JSONObject().put("title", it.title).put("url", it.url) }).toString()
            }
            db.update("recurring_searches", ContentValues().apply {
                put("failed", if (data == null) 1 else 0)
                if (data != null) {
                    put("result", data)
                    put("observed_at", clock())
                }
            }, "owner=?", arrayOf(owner))
        }
    }

    fun advance(finished: ScheduledTicket): ScheduledTicket? = store.inTransaction {
        val owner = finished.delivery.key.ownerId
        val current = status(owner)?.ticket ?: return@inTransaction null
        if (current != finished) return@inTransaction current
        val now = clock()
        val next = if (now < current.due) current.due
            else current.due + ((now - current.due) / INTERVAL + 1) * INTERVAL
        db.update("recurring_searches", ContentValues().apply {
            put("due", next)
            put("turn", 1 - current.turn)
        }, "owner=?", arrayOf(owner))
        ticket(owner)
    }

    private fun count(query: String, args: Array<String>): Int = db.rawQuery(query, args).use {
        check(it.moveToFirst())
        it.getInt(0)
    }

    companion object {
        const val ID = "recurring-ai-search"
        const val INTERVAL = 6 * SourceRefresh.INTERVAL
        private const val DAY = 24 * SourceRefresh.INTERVAL

        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_searches(slot INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL UNIQUE,activation TEXT NOT NULL,turn INTEGER NOT NULL,due INTEGER NOT NULL,query TEXT NOT NULL,credential TEXT NOT NULL,revision INTEGER NOT NULL,created_at INTEGER NOT NULL,result TEXT,observed_at INTEGER NOT NULL,failed INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_search_runs(owner TEXT NOT NULL,activation TEXT NOT NULL,due INTEGER NOT NULL,started INTEGER NOT NULL,session TEXT NOT NULL,running INTEGER NOT NULL,PRIMARY KEY(activation,due))")
        }

        fun stop(store: ToolStore, owner: String) {
            store.writableDatabase.delete("recurring_searches", "owner=?", arrayOf(owner))
        }
    }
}
