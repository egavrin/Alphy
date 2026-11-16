package com.alpha.assistant.tools

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolStorageRecoveryWorkflowTest {
    private val robot = ShellRobot("tool-storage")

    @Test fun olderLibraryIsRefusedAndKept() {
        val fixture = ShellFixture("published-library")
        val name = "${fixture.name}.db-tools.db"
        val spec = ToolSpec.parse("""
            {"formatVersion":3,"title":"Saved counter","description":"A local counter","capabilities":[],
            "stateSchema":{"count":"number"},"initialState":{"count":0},
            "ui":{"type":"column","children":[{"type":"metric","text":"Count","bind":"count"},
            {"type":"button","text":"Increase","action":"increment"}]},
            "source":"if (input.event.actionId === 'increment') input.state.count++; return {state:input.state};"}
        """.trimIndent())
        val state = """{"count":5}"""
        fixture.install(saveKey = false)
        try {
            fixture.context.openOrCreateDatabase(name, 0, null).use { seedPublishedLibrary(it, spec, state) }
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.mini_apps))
                robot.waitFor(robot.text(R.string.tool_library_unavailable))
            }
            fixture.context.openOrCreateDatabase(name, 0, null).use { db ->
                assertEquals(7, db.version)
                db.rawQuery("SELECT state FROM tools WHERE id='saved'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(state, it.getString(0))
                }
                db.rawQuery("SELECT prompt FROM drafts WHERE id='pending'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Saved draft", it.getString(0))
                }
            }
        } finally { fixture.remove() }
    }

    @Test fun unreadableDatabaseIsPreserved() {
        val fixture = ShellFixture("unreadable-library")
        val name = "${fixture.name}.db-tools.db"
        val path = fixture.context.getDatabasePath(name)
        val bytes = "unreadable user data".toByteArray()
        fixture.install(saveKey = false)
        try {
            path.parentFile?.mkdirs()
            path.writeBytes(bytes)
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.mini_apps))
                robot.waitFor(robot.text(R.string.tool_library_unavailable))
                assertArrayEquals(bytes, path.readBytes())
            }
        } finally { fixture.remove() }
    }

    private fun seedPublishedLibrary(db: SQLiteDatabase, spec: ToolSpec, state: String) {
        // These definitions and the file metadata reproduce the published alpha.2 library.
        db.execSQL("CREATE TABLE tools(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, conversation_id TEXT, saved INTEGER NOT NULL, archived INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE revisions(tool_id TEXT NOT NULL, revision INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, PRIMARY KEY(tool_id,revision))")
        db.execSQL("CREATE TABLE drafts(id TEXT PRIMARY KEY, tool_id TEXT, base_revision INTEGER NOT NULL, base_state_version INTEGER NOT NULL, prompt TEXT NOT NULL, candidate TEXT, preview_state TEXT, preview_ui TEXT, conversation_id TEXT)")
        val values = arrayOf(spec.json, state, spec.ui.toString())
        db.execSQL("INSERT INTO tools VALUES('saved',1,5,?,?,?,'stored-chat',1,0,100)", values)
        db.execSQL("INSERT INTO revisions VALUES('saved',1,?,?,?)", values)
        db.execSQL("INSERT INTO drafts VALUES('pending','saved',1,5,'Saved draft',NULL,NULL,NULL,'stored-chat')")
        db.version = 7
    }
}
