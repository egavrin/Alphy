package com.alpha.assistant.timer

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolUiCatalog
import org.json.JSONArray
import org.json.JSONObject

internal class ForegroundTimerLedger(private val store: ToolStore, private val tool: ToolRecord, private val spec: ForegroundTimerSpec) {
    enum class Phase { IDLE, RUNNING, PAUSED, ADMITTED, CLAIMED, FINISHED, SUPPRESSED }

    data class Run(val token: String, val phase: Phase, val remainingMillis: Long, val outcome: String? = null) {
        fun encode(): String = JSONObject().put("token", token).put("phase", phase.name)
            .put("remaining", remainingMillis).put("outcome", outcome).toString()

        companion object {
            fun decode(raw: String): Run = JSONObject(raw).let {
                Run(it.getString("token"), Phase.valueOf(it.getString("phase")), it.getLong("remaining"),
                    if (it.has("outcome")) it.getString("outcome") else null)
            }
        }
    }

    data class Snapshot(val definition: String, val run: Run)

    val key = "${tool.id}:${tool.revision}:${spec.id}:!nativeTimer"
    private val definition = JSONObject().put("durationMs", spec.durationMillis)
        .put("alerts", JSONArray(spec.requests().map { it.json() })).toString()

    fun read(): Snapshot? = store.readableDatabase.rawQuery(
        "SELECT request,result FROM capability_effects WHERE effect_key=? AND phase='native_timer'", arrayOf(key),
    ).use { if (it.moveToFirst()) Snapshot(it.getString(0), Run.decode(it.getString(1))) else null }

    fun run(snapshot: Snapshot?): Run? = snapshot?.takeIf { it.definition == definition }?.run

    fun authorized(): Boolean = current()?.let(::authorized) ?: false

    private fun authorized(record: ToolRecord): Boolean {
        val definition = ToolSpec.availablePersisted(record.spec) ?: return false
        val rendered = runCatching {
            ToolUiCatalog.validateForegroundTimer(JSONObject(record.ui), spec.id, JSONObject(record.state))
        }.getOrNull()
        return rendered == spec && spec.requests().all { it.capability in definition.capabilities }
    }

    fun write(expected: Snapshot?, next: Run, requireAuthorization: Boolean = true): Snapshot? {
        var written: Snapshot? = null
        store.transaction {
            val record = current() ?: return@transaction
            if (read() != expected || (requireAuthorization && !authorized(record))) return@transaction
            val values = ContentValues().apply {
                put("effect_key", key)
                put("owner", record.id)
                put("revision", record.revision)
                put("state_version", record.stateVersion)
                put("request", definition)
                put("result", next.encode())
                put("created_at", System.currentTimeMillis())
                put("phase", "native_timer")
            }
            check(store.writableDatabase.insertWithOnConflict("capability_effects", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            written = Snapshot(definition, next)
        }
        return written
    }

    private fun current() = store.tool(tool.id)?.takeIf { it.revision == tool.revision && it.saved && !it.archived }
}
