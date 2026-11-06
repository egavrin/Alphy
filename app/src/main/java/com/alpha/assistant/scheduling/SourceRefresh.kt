package com.alpha.assistant.scheduling

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkRequest
import com.alpha.assistant.network.NetworkResult
import com.alpha.assistant.network.livesource.LiveSourceEffect
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolNetworkGrants
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID

internal data class SourceOffer(
    val owner: String, val createdAt: Long, val revision: Int, val sourceId: String, val args: String,
) {
    val endpoint get() = JSONObject(args).getString("url")
    val selector get() = JSONObject(args).getJSONObject("extract")
    val key get() = ScheduleKey(ScheduleOwnerKind.MINI_APP, owner, "live-source")
    fun matches(tool: ToolRecord) =
        tool.saved && !tool.archived && tool.id == owner && tool.createdAt == createdAt && tool.revision == revision

    companion object {
        fun from(tool: ToolRecord, id: String, args: JSONObject): SourceOffer? {
            val extract = args.optJSONObject("extract") ?: return null
            val declaration = JSONObject().put("method", args.getString("method")).put("url", args.getString("url"))
                .put("extract", JSONObject().put("kind", extract.get("kind")).put("path", extract.getJSONArray("path")))
            return SourceOffer(tool.id, tool.createdAt, tool.revision, id, declaration.toString())
        }
    }
}

internal data class SourceStatus(val offer: SourceOffer, val sample: JSONObject?, val failed: Boolean)
internal data class SourceRefreshRun(val offer: SourceOffer, val token: String)
internal data class ScheduledTicket(val delivery: ScheduleDelivery, val slot: Int, val turn: Int, val due: Long) {
    val jobId get() = Math.addExact(Math.multiplyExact(slot, 2), turn)
}

/** The selected tool database owns the approval, refresh cache, and durable occurrence claim. */
internal class SourceRefresh(private val store: ToolStore, private val clock: () -> Long = System::currentTimeMillis) : ScheduleStorage {
    private val db get() = store.writableDatabase

    fun status(owner: String): SourceStatus? = db.rawQuery(
        "SELECT created_at,revision,source_id,args,sample,failed FROM live_sources WHERE owner=?", arrayOf(owner)
    ).use { row ->
        if (!row.moveToFirst()) return@use null
        val offer = SourceOffer(owner, row.getLong(0), row.getInt(1), row.getString(2), row.getString(3))
        if (store.tool(owner)?.let(offer::matches) != true) return@use null
        SourceStatus(offer, if (row.isNull(4)) null else JSONObject(row.getString(4)), row.getInt(5) != 0)
    }

    fun approve(offer: SourceOffer): Boolean = store.inTransaction {
        if (store.tool(offer.owner)?.let(offer::matches) != true || source(offer) == null) return@inTransaction false
        if (status(offer.owner)?.offer == offer) return@inTransaction true
        stop(store, offer.owner)
        ToolNetworkGrants.invalidateBackground(store, offer.owner)
        db.replaceOrThrow("live_sources", null, ContentValues().apply {
            put("owner", offer.owner)
            put("created_at", offer.createdAt)
            put("revision", offer.revision)
            put("source_id", offer.sourceId)
            put("args", offer.args)
            put("failed", 0)
            put("write_token", "")
        })
        true
    }

    fun activate(offer: SourceOffer): ScheduledTicket? = store.inTransaction {
        if (status(offer.owner)?.offer != offer) return@inTransaction null
        val publication = transaction { eligiblePublication(offer.key) } ?: return@inTransaction null
        val authority = ScheduleAuthority(this, clock) { _, _ -> error("Activation does not execute actions") }
        authority.activate(publication) ?: return@inTransaction null
        ticket(offer.owner)
    }

    fun ticket(owner: String): ScheduledTicket? = db.rawQuery(
        "SELECT owner,activation,slot,turn,due FROM source_schedules WHERE owner=?", arrayOf(owner)
    ).use { if (it.moveToFirst()) it.ticket() else null }

    private fun Cursor.ticket(): ScheduledTicket {
        val due = getLong(4)
        val key = ScheduleKey(ScheduleOwnerKind.MINI_APP, getString(0), "live-source")
        return ScheduledTicket(ScheduleDelivery(key, getString(1), due.toString()), getInt(2), getInt(3), due)
    }

    fun current(ticket: ScheduledTicket): Boolean = transaction {
        val active = activation(ticket.delivery.key)
        active?.id == ticket.delivery.activationId && deliveryWindow(ticket.delivery)?.contains(clock()) == true
    }

    fun advance(finished: ScheduledTicket): ScheduledTicket? = store.inTransaction {
        val owner = finished.delivery.key.ownerId
        val current = ticket(owner) ?: return@inTransaction null
        if (transaction { eligiblePublication(current.delivery.key) } == null) {
            stop(store, owner)
            return@inTransaction null
        }
        if (current != finished) return@inTransaction current
        val now = clock()
        val next = if (now < current.due) current.due
            else current.due + ((now - current.due) / INTERVAL + 1) * INTERVAL
        db.update("source_schedules", ContentValues().apply {
            put("due", next)
            put("turn", 1 - current.turn)
            if (next != current.due) put("claimed", 0)
        }, "owner=?", arrayOf(owner))
        ticket(owner)
    }

    fun cancel(owner: String): ScheduledTicket? = store.inTransaction {
        val current = ticket(owner)
        stop(store, owner)
        ToolNetworkGrants.invalidateBackground(store, owner)
        current
    }

    private fun source(offer: SourceOffer) = LiveSourceEffect.declaration(
        JSONObject(offer.args), NetworkOwner(store.artifactIdentity, offer.owner), offer.sourceId
    )

    suspend fun refresh(offer: SourceOffer, ticket: ScheduledTicket? = null): Boolean {
        val source = source(offer) ?: return false
        val owner = offer.owner
        fun approved() = status(owner)?.offer == offer && store.networkGrantEnabled(owner) &&
            (ticket == null || current(ticket))
        if (!approved()) return false
        val tool = store.tool(owner)?.takeIf(offer::matches) ?: return false
        val authority = ToolNetworkGrants.authority(store)
        if (authority.refreshAccess() == null) return false
        val grant = authority.activate(tool, this, UUID.randomUUID().toString(), background = ticket != null)
            ?: return false
        return try {
            val task = currentCoroutineContext()
            val run = begin(offer) { approved() && authority.isCurrent(grant) } ?: return false
            val request = NetworkRequest(source.request().url, followRedirects = false)
            val outcome = authority.exchange(grant, "live-source-refresh", request) {}
            task.ensureActive()
            val sample = outcome.response?.takeIf { outcome.result is NetworkResult.Delivered }
                ?.let { LiveSourceEffect.sample(source, it) }
            ArtifactCoordinator.get(store, owner).ordered {
                record(run, sample) {
                    task.ensureActive()
                    approved() && authority.isCurrent(grant)
                }
            }
        } finally {
            authority.invalidate(grant)
        }
    }

    fun begin(offer: SourceOffer, allowed: () -> Boolean): SourceRefreshRun? = store.inTransaction {
        if (status(offer.owner)?.offer != offer || !store.networkGrantEnabled(offer.owner) || !allowed())
            return@inTransaction null
        val token = UUID.randomUUID().toString()
        val updated = db.update("live_sources", ContentValues().apply { put("write_token", token) },
            "owner=?", arrayOf(offer.owner))
        if (updated == 1) SourceRefreshRun(offer, token) else null
    }

    fun record(run: SourceRefreshRun, sample: JSONObject?, allowed: () -> Boolean): Boolean = store.inTransaction {
        val offer = run.offer
        if (status(offer.owner)?.offer != offer || !store.networkGrantEnabled(offer.owner) || !allowed())
            return@inTransaction false
        db.update("live_sources", ContentValues().apply {
            put("failed", if (sample == null) 1 else 0)
            if (sample != null) put("sample", sample.toString())
            put("write_token", "")
        }, "owner=? AND write_token=?", arrayOf(offer.owner, run.token)) == 1
    }

    override fun <T> transaction(block: ScheduleStorage.Transaction.() -> T): T = store.inTransaction {
        storage.block()
    }

    private val storage = object : ScheduleStorage.Transaction {
        override fun eligiblePublication(key: ScheduleKey): SchedulePublication? {
            val offer = status(key.ownerId)?.offer?.takeIf { it.key == key } ?: return null
            if (!store.networkGrantEnabled(offer.owner)) return null
            val declaration = JSONObject().put("id", offer.sourceId).put("args", JSONObject(offer.args)).toString()
            return SchedulePublication(key, offer.createdAt.toString(), store.library.generation, offer.revision.toString(),
                ScheduleDefinition(INTERVAL.toString(), listOf(ScheduleAction("refresh", ScheduleAction.Kind.SOURCE_REFRESH, declaration))))
        }

        override fun activation(key: ScheduleKey): ScheduleActivation? {
            val current = ticket(key.ownerId) ?: return null
            val publication = eligiblePublication(key) ?: return null
            return ScheduleActivation(current.delivery.activationId, publication)
        }

        override fun saveActivation(activation: ScheduleActivation) {
            val owner = activation.publication.key.ownerId
            db.insertOrThrow("source_schedules", null, ContentValues().apply {
                put("owner", owner)
                put("activation", activation.id)
                put("due", clock() + INTERVAL)
                put("turn", 0)
                put("claimed", 0)
            })
        }

        override fun deliveryWindow(delivery: ScheduleDelivery): ScheduleDeliveryWindow? =
            ticket(delivery.key.ownerId)?.takeIf { it.delivery == delivery }?.let { ScheduleDeliveryWindow(it.due, it.due + INTERVAL) }

        override fun claim(delivery: ScheduleDelivery): Boolean = db.update("source_schedules", ContentValues().apply {
            put("claimed", 1)
        }, "owner=? AND activation=? AND due=? AND claimed=0",
            arrayOf(delivery.key.ownerId, delivery.activationId, delivery.occurrenceId)) == 1
    }

    companion object {
        const val INTERVAL = 60 * 60 * 1000L

        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS live_sources(owner TEXT PRIMARY KEY,created_at INTEGER NOT NULL,revision INTEGER NOT NULL,source_id TEXT NOT NULL,args TEXT NOT NULL,sample TEXT,failed INTEGER NOT NULL,write_token TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS source_schedules(slot INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL UNIQUE,activation TEXT NOT NULL,due INTEGER NOT NULL,turn INTEGER NOT NULL,claimed INTEGER NOT NULL)")
        }

        fun stop(store: ToolStore, owner: String) {
            store.writableDatabase.delete("source_schedules", "owner=?", arrayOf(owner))
        }
    }
}
