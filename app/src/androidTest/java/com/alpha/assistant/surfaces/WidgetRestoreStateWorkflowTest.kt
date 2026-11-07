package com.alpha.assistant.surfaces

import android.content.Context
import android.content.ContextWrapper
import android.appwidget.AppWidgetManager
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WidgetRestoreStateWorkflowTest {
    @Test fun restoreWithUnchangedIdPreservesBinding() = withFixture { context ->
        restore(context, intArrayOf(101), intArrayOf(101))
        assertEquals("Unchanged ID must keep its original binding", first, binding(context, 101))
        assertEquals(second, binding(context, 102))
    }

    @Test fun overlappingRestorePreservesOriginalOwners() = withFixture { context ->
        restore(context, intArrayOf(101, 102), intArrayOf(102, 103))
        assertEquals(
            "Destinations must receive the original owners and preferences",
            listOf(first, second),
            listOf(binding(context, 102), binding(context, 103)),
        )
        assertNull(ToolWidgets.toolId(context, 101))
    }

    @Test fun disjointRestorePreservesBindings() = withFixture { context ->
        restore(context, intArrayOf(101, 102), intArrayOf(201, 202))
        assertEquals(first, binding(context, 201))
        assertEquals(second, binding(context, 202))
        assertNull(ToolWidgets.toolId(context, 101))
        assertNull(ToolWidgets.toolId(context, 102))
    }

    @Test fun restoreCyclePreservesBothOriginalBindings() = withFixture { context ->
        restore(context, intArrayOf(101, 102), intArrayOf(102, 101))
        assertEquals(second, binding(context, 101))
        assertEquals(first, binding(context, 102))
    }

    @Test fun invalidMappingLeavesItsSourceAndAllowsOtherMoves() = withFixture { context ->
        restore(context, intArrayOf(101, 102), intArrayOf(AppWidgetManager.INVALID_APPWIDGET_ID, 202))
        assertEquals(first, binding(context, 101))
        assertEquals(second, binding(context, 202))
        assertNull(ToolWidgets.toolId(context, 102))
        assertNull(ToolWidgets.toolId(context, AppWidgetManager.INVALID_APPWIDGET_ID))
    }

    private val first = "first" to WidgetPreferences("compact", true)
    private val second = "second" to WidgetPreferences("wide", false)

    private fun binding(context: Context, id: Int) =
        ToolWidgets.toolId(context, id) to ToolWidgets.preferences(context, id)

    private fun restore(context: Context, oldIds: IntArray, newIds: IntArray) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ToolWidgetProvider().onRestored(context, oldIds, newIds)
        }
    }

    private fun withFixture(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "widget-restore-${UUID.randomUUID()}"
        val database = "$prefix.db"
        val context = object : ContextWrapper(base) {
            private fun mapped(name: String) = if (name == "alpha-tools.db") database else name
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$prefix-$name", mode)
            override fun getNoBackupFilesDir() = base.noBackupFilesDir.resolve(prefix)
            override fun getDatabasePath(name: String) = base.getDatabasePath(mapped(name))
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                base.openOrCreateDatabase(mapped(name), mode, factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?,
            ) = base.openOrCreateDatabase(mapped(name), mode, factory, handler)
        }
        val spec = ToolSpec.parse("""
            {"formatVersion":3,"title":"Widget restore fixture","description":"Test state","capabilities":[],
             "stateSchema":{"count":"number"},"initialState":{"count":0},
             "ui":{"type":"column","children":[{"type":"text","text":"Count","bind":"count"}]},
             "source":"return {state:input.state};"}
        """.trimIndent())
        try {
            ToolStore(context).use { store ->
                listOf(first, second).forEachIndexed { index, (owner, preferences) ->
                    val record = ToolRecord(owner, 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
                    store.saveTool(record, true)
                    ToolWidgets.bind(context, 101 + index, owner, preferences, store)
                }
            }
            block(context)
        } finally {
            base.deleteDatabase(database)
            base.deleteSharedPreferences("$prefix-alpha-widgets")
        }
    }
}
