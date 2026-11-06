package com.alpha.assistant.surfaces.widgetauthoring

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.surfaces.WidgetProjection
import com.alpha.assistant.surfaces.projectWidget
import com.alpha.assistant.surfaces.widgetActionId
import com.alpha.assistant.tools.ArtifactCoordinator
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolUiCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class WidgetLayoutStore(private val store: ToolStore) : WidgetDraftHost<WidgetProjection> {
    data class Layout(val revision: Int, val version: Long, val presentation: WidgetPresentation)

    fun read(owner: String): Layout? {
        val db = store.readableDatabase
        if (!exists(db)) return null
        return db.rawQuery("SELECT revision,version,definition FROM widget_layouts WHERE owner=?", arrayOf(owner)).use {
            if (it.moveToFirst()) Layout(it.getInt(0), it.getLong(1), parseWidgetPresentation(it.getString(2))) else null
        }
    }

    fun presentation(tool: ToolRecord): WidgetPresentation? = read(tool.id)?.takeIf { it.revision == tool.revision }?.presentation

    fun snapshot(tool: ToolRecord): WidgetAppSnapshot<WidgetProjection>? {
        val current = current(WidgetAppId(store.library.generation, tool.id)) ?: return null
        return current.takeIf { it.base.revision == tool.revision && it.base.stateVersion == tool.stateVersion }
    }

    override suspend fun latest(app: WidgetAppId) = withContext(Dispatchers.IO) { current(app) }

    private fun current(app: WidgetAppId): WidgetAppSnapshot<WidgetProjection>? {
        if (app.libraryGeneration != store.library.generation) return null
        val tool = store.tool(app.id)?.takeIf { it.saved && !it.archived } ?: return null
        val projection = projectWidget(tool)
        val base = WidgetDraftBase(app, tool.revision, tool.stateVersion, read(tool.id)?.version ?: 0L)
        return WidgetAppSnapshot(base, projection.references(), projection)
    }

    override fun validate(snapshot: WidgetAppSnapshot<WidgetProjection>, presentation: WidgetPresentation): Boolean =
        presentation.usesOnly(snapshot.references) && projectRows(snapshot.state, presentation).size == presentation.rows.size

    override fun render(snapshot: WidgetAppSnapshot<WidgetProjection>, presentation: WidgetPresentation): WidgetPreviewContent =
        WidgetPreviewContent(snapshot.state.title, projectRows(snapshot.state, presentation).map { node ->
            when (node.getString("type")) {
                "button" -> WidgetPreviewRow.Button(node.getString("text"))
                "toggle" -> WidgetPreviewRow.Toggle(node.getString("text"), ToolUiCatalog.value(snapshot.state.state, node.getString("bind")) == true)
                else -> WidgetPreviewRow.Text(ToolUiCatalog.display(node, snapshot.state.state))
            }
        })

    suspend fun save(proposal: WidgetSaveProposal): Boolean = ArtifactCoordinator.get(store, proposal.base.app.id).ordered {
        var saved = false
        store.transaction {
            val snapshot = current(proposal.base.app) ?: return@transaction
            if (snapshot.base != proposal.base || !validate(snapshot, proposal.presentation)) return@transaction
            createTable(store.writableDatabase)
            val values = ContentValues().apply {
                put("owner", proposal.base.app.id)
                put("revision", proposal.base.revision)
                put("version", proposal.base.presentationVersion + 1L)
                put("definition", encode(proposal.presentation))
            }
            check(store.writableDatabase.replace("widget_layouts", null, values) != -1L)
            saved = true
        }
        saved
    }

    companion object {
        fun exists(db: SQLiteDatabase): Boolean = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='widget_layouts'", null,
        ).use { it.moveToFirst() }

        fun createTable(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS widget_layouts(" +
            "owner TEXT PRIMARY KEY, revision INTEGER NOT NULL, version INTEGER NOT NULL, definition TEXT NOT NULL)")
    }
}

internal fun WidgetProjection.references(): WidgetReferences {
    val bindings = nodes.mapNotNull { node ->
        node.optString("bind").takeIf { it.isNotEmpty() && ToolUiCatalog.value(state, it).let { value ->
            value is String || value is Number || value is Boolean
        } }
    }.toSet()
    val actions = nodes.mapNotNull { node -> node.widgetActionId()?.let { action ->
        action to if (node.getString("type") == "toggle") WidgetAction.Toggle(node.getString("bind")) else WidgetAction.Button
    } }.toMap()
    return WidgetReferences(bindings, actions)
}

internal fun projectRows(projection: WidgetProjection, presentation: WidgetPresentation): List<JSONObject> {
    val references = projection.references()
    return presentation.rows.mapNotNull { row ->
        val node = JSONObject().put("text", row.label)
        when (row) {
            is WidgetPresentationRow.Text -> if (row.binding == null || row.binding in references.bindings)
                node.put("type", "text").put("bind", row.binding) else null
            is WidgetPresentationRow.Button -> if (references.actions[row.action] == WidgetAction.Button)
                node.put("type", "button").put("action", row.action) else null
            is WidgetPresentationRow.Toggle -> if (references.actions[row.action] == WidgetAction.Toggle(row.binding))
                node.put("type", "toggle").put("action", row.action).put("bind", row.binding) else null
        }
    }
}

internal fun parseWidgetPresentation(raw: String): WidgetPresentation {
    require(raw.toByteArray().size <= 8_192)
    val document = SandboxContract.objectFrom(raw)
    require(document.keys().asSequence().toSet() == setOf("rows"))
    val rows = document.getJSONArray("rows")
    require(rows.length() in 1..6)
    return WidgetPresentation(List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val label = requireNotNull(row.get("label") as? String)
        require(label.isNotBlank() && label.length <= 300)
        fun fields(vararg names: String) { require(row.keys().asSequence().toSet() == setOf("type", "label", *names)) }
        fun text(name: String) = requireNotNull(row.get(name) as? String)
        when (row.getString("type")) {
            "text" -> if (row.has("binding")) {
                fields("binding"); WidgetPresentationRow.Text(label, text("binding"))
            } else { fields(); WidgetPresentationRow.Text(label) }
            "button" -> { fields("action"); WidgetPresentationRow.Button(label, text("action")) }
            "toggle" -> { fields("binding", "action"); WidgetPresentationRow.Toggle(label, text("binding"), text("action")) }
            else -> throw IllegalArgumentException("Unknown widget row.")
        }
    })
}

private fun encode(presentation: WidgetPresentation): String = JSONObject().put("rows", JSONArray(presentation.rows.map { row ->
    JSONObject().put("label", row.label).apply {
        when (row) {
            is WidgetPresentationRow.Text -> put("type", "text").put("binding", row.binding)
            is WidgetPresentationRow.Button -> put("type", "button").put("action", row.action)
            is WidgetPresentationRow.Toggle -> put("type", "toggle").put("binding", row.binding).put("action", row.action)
        }
    }
})).toString()
