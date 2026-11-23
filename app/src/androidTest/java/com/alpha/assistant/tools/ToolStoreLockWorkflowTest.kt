package com.alpha.assistant.tools

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.network.NetworkOwner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The lock rule of the app store (stage 7b, R1): no thread waits for the database while it holds the library gate. The three guards of the
 * library selection stay: no commit goes to a library file that is no longer current, no open creates a file for a replaced selection, and a
 * revoked network grant shows at the next read.
 */
@RunWith(AndroidJUnit4::class)
class ToolStoreLockWorkflowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Thread 1 holds a transaction for 300 ms, revokes a grant, and reads through the getter inside it. A plain reader of the same store and
     * a second store that reads the grants start during the hold. In Alpha 23 the second store held the gate while it waited for the
     * database, and the other two threads waited for the gate until the busy wait of the platform (about 2.5 s) ended it.
     */
    @Test fun aTransactionAReaderAndASecondStoreEndWithin500MsAfterTheHold() = withStore { store, _ ->
        val tool = save(store)
        store.setNetworkGrant(tool.id, true)
        val inside = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val ends = ConcurrentHashMap<String, Long>()
        var holdEnd = 0L
        var grants: Set<NetworkOwner>? = null
        fun run(name: String, work: () -> Unit) = thread(name = name) {
            try { work() } catch (failure: Throwable) { failures.add(failure) }
            ends[name] = SystemClock.uptimeMillis()
        }
        val holder = run("transaction") {
            store.inTransaction {
                holdEnd = SystemClock.uptimeMillis() + 300
                store.setNetworkGrant(tool.id, false)
                inside.countDown()
                SystemClock.sleep(100)
                store.networkGrantEnabled(tool.id)
                SystemClock.sleep((holdEnd - SystemClock.uptimeMillis()).coerceAtLeast(0))
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val second = run("second store") { grants = ToolNetworkGrantPersistence(app, store.library).loadEnabled().getOrThrow() }
        SystemClock.sleep(40)
        val reader = run("reader") { store.tools() }
        listOf(holder, second, reader).forEach { it.join(10_000) }
        assertTrue("no thread fails: $failures", failures.isEmpty())
        val late = ends.mapValues { it.value - holdEnd }
        assertEquals(setOf("transaction", "second store", "reader"), late.keys)
        assertTrue("each thread ends within 500 ms after the hold: $late", late.values.all { it <= 500 })
        assertEquals("the revoked grant shows at the next read", emptySet<NetworkOwner>(), grants)
    }

    @Test fun aStoreOfAReplacedSelectionCommitsNothing() = withStore { store, name ->
        save(store)
        selectOtherLibraryForTest(app, store.library)
        assertTrue(runCatching { save(store) }.exceptionOrNull() is ToolLibraryUnavailable)
        assertEquals(1, toolCount(name))
    }

    /** The open of an existing file waits for the database outside the gate. A selection that changes during that open is found before use. */
    @Test fun aSelectionThatChangesDuringTheOpenIsFoundBeforeACommit() = withStore { store, name ->
        save(store)
        store.close()
        val switching = object : ContextWrapper(app) {
            override fun openOrCreateDatabase(file: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
                super.openOrCreateDatabase(file, mode, factory, handler).also { if (file == name) selectOtherLibraryForTest(app, store.library) }
        }
        ToolStore(switching, name).use { reopened ->
            assertTrue(runCatching { save(reopened) }.exceptionOrNull() is ToolLibraryUnavailable)
        }
        assertEquals(1, toolCount(name))
    }

    @Test fun anUnopenedStoreOfAReplacedSelectionCreatesNoFile() = withStore { stale, name ->
        assertFalse(app.getDatabasePath(name).exists())
        selectOtherLibraryForTest(app, stale.library)
        assertTrue(runCatching { stale.writableDatabase }.exceptionOrNull() is ToolLibraryUnavailable)
        assertFalse(app.getDatabasePath(name).exists())
    }

    private fun toolCount(name: String) = SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT count(*) FROM tools", null).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun withStore(work: (ToolStore, String) -> Unit) {
        val name = "tool-store-lock-${UUID.randomUUID()}.db"
        try {
            ToolStore(app, name).use { store ->
                try { work(store, name) } finally { ToolNetworkGrants.retireLibrary(app, store.library) }
            }
        } finally {
            app.databaseList().filter { it.startsWith(name) }.forEach(app::deleteDatabase)
            ToolLibraryStorage.selectionFile(app, name).delete()
        }
    }

    private fun save(store: ToolStore): ToolRecord {
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Замок","description":"","capabilities":[],"stateSchema":{"value":"number"},"initialState":{"value":0},"ui":{"type":"text","text":"Замок"},"source":"return {state:input.state};"}""")
        return ToolRecord(UUID.randomUUID().toString(), 1, 1, spec.json, spec.initialState, JSONObject(spec.ui.toString()).toString(), null, true)
            .also { store.commitTool(it, null, true) }
    }
}
