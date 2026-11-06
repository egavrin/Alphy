package com.alpha.assistant.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolLibraryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DesktopRef(val kind: Kind, val id: String) {
    enum class Kind { Tool, Automation, Course }

    val key: String get() = "${kind.name}:$id"
}

private class DesktopLayoutStore(context: Context, automationDatabase: String) : SQLiteOpenHelper(
    context, if (automationDatabase == "alpha-automations.db") "alpha-desktop.db"
    else "${automationDatabase.removeSuffix("-automations.db")}-desktop.db", null, 1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE desktop_items(position INTEGER PRIMARY KEY, kind TEXT NOT NULL, item_id TEXT NOT NULL, UNIQUE(kind, item_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported desktop layout version.")
    }

    fun update(change: (List<DesktopRef>) -> List<DesktopRef>): List<DesktopRef> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.query("desktop_items", arrayOf("kind", "item_id"), null, null, null, null, "position").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(DesktopRef(DesktopRef.Kind.valueOf(cursor.getString(0)), cursor.getString(1)))
                }
            }
            val changed = change(current)
            if (changed != current) {
                db.delete("desktop_items", null, null)
                changed.forEachIndexed { index, ref ->
                    db.insertOrThrow("desktop_items", null, ContentValues().apply {
                        put("position", index)
                        put("kind", ref.kind.name)
                        put("item_id", ref.id)
                    })
                }
            }
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }
}

internal class DesktopLayout(context: Context, private val tools: ToolController) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    var order by mutableStateOf<List<DesktopRef>?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    suspend fun sync(placed: List<DesktopRef>, unplaced: Set<DesktopRef>) = update { current ->
        current.filterNot { it in unplaced } + placed.filterNot { it in current }
    }

    suspend fun move(ref: DesktopRef, neighbor: DesktopRef) = update { current ->
        val from = current.indexOf(ref)
        val to = current.indexOf(neighbor)
        check(from >= 0 && to >= 0)
        current.toMutableList().apply {
            this[from] = neighbor
            this[to] = ref
        }
    }

    suspend fun setPlacement(ref: DesktopRef, placed: Boolean) = update { current ->
        if (placed) (current + ref).distinct() else current - ref
    }

    private suspend fun update(change: (List<DesktopRef>) -> List<DesktopRef>) = mutex.withLock {
        saving = true
        try {
            order = withContext(Dispatchers.IO) {
                ToolLibraryStorage.requireRelated(app,tools.automationDatabase,"automations")
                DesktopLayoutStore(app, tools.automationDatabase).use { it.update(change) }
            }
            failed = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        } finally {
            saving = false
        }
    }
}
