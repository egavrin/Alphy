package com.alpha.assistant.tools.three

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.alpha.assistant.tools.ToolLibrarySelection
import com.alpha.assistant.tools.ToolLibraryStorage
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class ThreeSceneAssetCleanup(private val context: Context) {
    private val directory = File(context.filesDir, "threejs-assets")
    private val usage = usages.computeIfAbsent(directory.absolutePath) { Usage() }

    fun lease(): Closeable {
        synchronized(usage) {
            usage.active++
            usage.epoch++
        }
        var released = false
        return Closeable {
            synchronized(usage) {
                if (!released) {
                    released = true
                    usage.active--
                    schedule()
                }
            }
        }
    }

    fun request() = synchronized(usage) {
        usage.pending = true
        schedule()
    }

    private fun schedule() {
        if (!usage.pending || usage.running || usage.active != 0) return
        usage.running = true
        scope.launch {
            val epoch = synchronized(usage) {
                if (usage.active != 0) {
                    usage.running = false
                    return@launch
                }
                usage.pending = false
                usage.epoch
            }
            val sweep = try {
                val files = directory.listFiles()?.filter {
                    it.isFile && (hash.matches(it.name) || partial.matches(it.name))
                }.orEmpty()
                if (files.isEmpty()) null else {
                    val selection = ToolLibraryStorage.production(context)
                    selection to unusedFiles(files, selection)
                }
            } catch (_: Exception) {
                Log.w("ThreeSceneAssets", "Asset collection deferred because stored references could not be read.")
                null
            }
            synchronized(usage) {
                // A new lease may publish a reference while the database scan is in flight.
                if (usage.epoch != epoch) usage.pending = true
                else if (sweep != null) {
                    try {
                        ToolLibraryStorage.open(context, sweep.first) { sweep.second.forEach { it.delete() } }
                    } catch (_: Exception) {
                        Log.w("ThreeSceneAssets", "Asset collection deferred because the library became unavailable.")
                    }
                }
                usage.running = false
                schedule()
            }
        }
    }

    private fun unusedFiles(files: List<File>, selection: ToolLibrarySelection): List<File> {
        val referenced = mutableSetOf<String>()
        val listed = context.databaseList().toSet()
        val databases = listed.filterNot { name ->
            sidecars.any { name.endsWith(it) && name.removeSuffix(it) in listed }
        }.toMutableSet()
        if (!selection.original) {
            databases += selection.physical
            databases += checkNotNull(selection.preserved)
        }
        for (name in databases) {
            val path = context.getDatabasePath(name).absolutePath
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ToolLibraryStorage.preserveOnCorruption).use { db ->
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { rows ->
                    buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
                }
                if (name == "alpha.db" || "conversations" in tables) collectChat(db, referenced)
                if (ToolLibraryStorage.logicalName(name) == ToolLibraryStorage.DEFAULT ||
                    tables.any { it in setOf("tools", "revisions", "drafts") }) collectTools(db, referenced)
            }
        }
        return files.filter { it.name !in referenced }
    }

    private fun collectChat(db: SQLiteDatabase, referenced: MutableSet<String>) {
        fun attachments(owner: JSONObject) {
            val items = owner.getJSONArray("attachments")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.getString("mime") == ThreeSceneAssets.ATTACHMENT_MIME) {
                    val id = JSONObject(item.getString("contextText")).getString("id")
                    check(hash.matches(id) && item.getString("uri") == "alphy-three-asset:$id")
                    referenced += id
                }
            }
        }

        db.rawQuery("SELECT body, 0 FROM conversations UNION ALL SELECT value, 1 FROM settings WHERE name='newDraft'",
            null).use { rows ->
            while (rows.moveToNext()) {
                val body = JSONObject(rows.getString(0))
                if (rows.getInt(1) == 1) attachments(body)
                else {
                    attachments(body.getJSONObject("draft"))
                    if (!body.isNull("refinementDraft")) attachments(body.getJSONObject("refinementDraft"))
                    val messages = body.getJSONArray("messages")
                    for (i in 0 until messages.length()) attachments(messages.getJSONObject(i))
                }
            }
        }
    }

    private fun collectTools(db: SQLiteDatabase, referenced: MutableSet<String>) {
        db.rawQuery("SELECT spec FROM tools UNION ALL SELECT spec FROM revisions " +
            "UNION ALL SELECT candidate FROM drafts WHERE candidate IS NOT NULL", null).use { rows ->
            while (rows.moveToNext()) {
                val spec = JSONObject(rows.getString(0))
                if (spec.has("sceneAssets")) {
                    referenced += ThreeSceneAssets.read(spec.getJSONArray("sceneAssets")).map { it.id }
                }
            }
        }
    }

    private class Usage {
        var active = 0
        var epoch = 0L
        var pending = false
        var running = false
    }

    private companion object {
        val usages = ConcurrentHashMap<String, Usage>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sidecars = listOf("-wal", "-shm", "-journal")
        val hash = Regex("[0-9a-f]{64}")
        val partial = Regex("import-[^/]+\\.part")
    }
}
