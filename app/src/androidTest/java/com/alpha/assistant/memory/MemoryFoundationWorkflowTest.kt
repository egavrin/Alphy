package com.alpha.assistant.memory

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class MemoryFoundationWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun explicitSaveSurvivesReopenAndRevisionChecksProtectEditsAndDeletes() = withDatabase { name, _ ->
        val original = "  Я люблю ёлки.\nОсобенно зимой.  "
        val (first, second) = MemoryStore(context, name).use { store ->
            assertTrue(store.list().isEmpty())
            assertThrows(IllegalArgumentException::class.java) { store.saveExplicitFact(" \n ") }
            val first = store.saveExplicitFact(original)
            val second = store.saveExplicitFact("Мой город — Казань.")
            assertEquals(original, first.text)
            assertEquals(MemorySource.EXPLICIT_SAVE_COMMAND, first.source)
            assertEquals(1L, first.revision)
            assertNotEquals(first.id, second.id)
            first to second
        }

        MemoryStore(context, name).use { writer ->
            MemoryStore(context, name).use { other ->
                assertEquals(setOf(first, second), other.list().toSet())
                val edited = writer.edit(first.id, first.revision, "Я люблю хвойные деревья.")
                assertEquals(first.id, edited.id)
                assertEquals(first.source, edited.source)
                assertEquals(2L, edited.revision)
                assertEquals("Я люблю хвойные деревья.", edited.text)

                assertRejected(MemoryRejection.STALE_REVISION) {
                    other.edit(first.id, first.revision, "Устаревшая правка.")
                }
                assertRejected(MemoryRejection.STALE_REVISION) {
                    other.delete(first.id, first.revision)
                }
                assertEquals(setOf(edited, second), writer.list().toSet())

                other.delete(edited.id, edited.revision)
                assertRejected(MemoryRejection.FACT_NOT_FOUND) {
                    writer.edit(edited.id, edited.revision, "Удалённый факт.")
                }
                assertRejected(MemoryRejection.FACT_NOT_FOUND) {
                    writer.delete(edited.id, edited.revision)
                }
            }
        }

        MemoryStore(context, name).use { assertEquals(listOf(second), it.list()) }
    }

    @Test
    fun closeRejectsNewOperationsWhileAnAdmittedWriteFinishes() = withDatabase { name, file ->
        val executor = Executors.newFixedThreadPool(2)
        val store = MemoryStore(context, name)
        try {
            val original = store.saveExplicitFact("Мой город — Казань.")
            val (write, close) = openFixture(file).use { database ->
                database.beginTransaction()
                try {
                    val writing = CountDownLatch(1)
                    val write = executor.submit<MemoryFact> {
                        writing.countDown()
                        store.edit(original.id, original.revision, "Мой город — Москва.")
                    }
                    assertTrue(writing.await(5, SECONDS))
                    assertThrows(TimeoutException::class.java) { write.get(150, MILLISECONDS) }

                    executor.submit {
                        store.beginClose()
                        assertClosed { store.list() }
                        assertClosed { store.saveExplicitFact("Новый факт.") }
                        assertClosed { store.list() }
                    }.get(1, SECONDS)

                    val closing = CountDownLatch(1)
                    val close = executor.submit {
                        closing.countDown()
                        store.close()
                    }
                    assertTrue(closing.await(5, SECONDS))
                    assertThrows(TimeoutException::class.java) { close.get(150, MILLISECONDS) }
                    write to close
                } finally {
                    database.endTransaction()
                }
            }
            val edited = write.get(5, SECONDS)
            close.get(5, SECONDS)
            assertEquals(original.copy(text = "Мой город — Москва.", revision = 2), edited)
            assertClosed { store.delete(edited.id, edited.revision) }
            MemoryStore(context, name).use { assertEquals(listOf(edited), it.list()) }
        } finally {
            store.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closingAnUnusedStoreKeepsItsDatabaseUnopened() = withDatabase { name, file ->
        val store = MemoryStore(context, name)
        store.close()
        assertClosed { store.list() }
        assertClosed { store.list() }
        assertFalse(file.exists())
    }

    @Test
    fun failedSqlWritesLeaveExistingFactsIntact() = withDatabase { name, file ->
        val before = MemoryStore(context, name).use { store ->
            val first = store.saveExplicitFact("Первый факт.")
            val second = store.saveExplicitFact("Второй факт.")
            val operations = mapOf<String, () -> Unit>(
                "INSERT" to { store.saveExplicitFact("Новая запись.") },
                "UPDATE" to { store.edit(first.id, first.revision, "Изменённая запись.") },
                "DELETE" to { store.delete(first.id, first.revision) },
            )
            openFixture(file).use { database ->
                for ((operation, write) in operations) {
                    database.execSQL(
                        "CREATE TRIGGER reject_write BEFORE $operation ON memory_facts " +
                            "BEGIN SELECT RAISE(ABORT, 'workflow write failure'); END",
                    )
                    assertThrows(SQLiteException::class.java) { write() }
                    database.execSQL("DROP TRIGGER reject_write")
                }
            }
            setOf(first, second)
        }
        MemoryStore(context, name).use { assertEquals(before, it.list().toSet()) }
    }

    @Test
    fun unknownVersionAndUnknownSchemaPreserveTheDatabase() {
        for (version in listOf(0, 1, 2, 42)) {
            withDatabase { name, file ->
                openFixture(file).use { database ->
                    database.execSQL("CREATE TABLE memory_facts(id TEXT, text TEXT, source TEXT, revision INTEGER)")
                    database.execSQL("INSERT INTO memory_facts VALUES ('old-id', 'Keep this fact.', 'fixture', 3)")
                    database.version = version
                }
                MemoryStore(context, name).use { store ->
                    assertThrows(IllegalStateException::class.java) { store.list() }
                    assertThrows(IllegalStateException::class.java) { store.saveExplicitFact("New fact.") }
                }
                openFixture(file).use { database ->
                    assertEquals(version, database.version)
                    database.rawQuery("SELECT * FROM memory_facts", null).use { rows ->
                        assertEquals(1, rows.count)
                        assertTrue(rows.moveToFirst())
                        assertEquals("old-id", rows.getString(0))
                        assertEquals("Keep this fact.", rows.getString(1))
                        assertEquals("fixture", rows.getString(2))
                        assertEquals(3L, rows.getLong(3))
                    }
                }
            }
        }
    }

    @Test
    fun corruptionIsReportedAndTheFileRemainsAvailable() = withDatabase { name, file ->
        val original = ByteArray(4096) { (it + 1).toByte() }
        file.writeBytes(original)

        MemoryStore(context, name).use { store ->
            assertThrows(SQLiteException::class.java) { store.list() }
            assertThrows(SQLiteException::class.java) { store.saveExplicitFact("Новый факт.") }
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun savedFactsUseSemanticRetrievalAfterReopen() = withDatabase { name, _ ->
        val expected = MemoryStore(context, name).use { store ->
            val wanted = store.saveExplicitFact("My favorite color is blue")
            store.saveExplicitFact("My passport number is 40482819")
            wanted
        }
        kotlinx.coroutines.runBlocking {
            MemoryStore(context, name).use { store ->
                assertEquals(listOf(expected), store.search("What is my favorite color?", 0.82f, 5))
                store.delete(expected.id, expected.revision)
                assertTrue(store.search("What is my favorite color?", 0.82f, 5).isEmpty())
            }
        }
    }

    @Test
    fun requestStagesShareOneLocalSelectionAndSendOnlyCurrentFactText() = withDatabase { name, _ -> kotlinx.coroutines.runBlocking {
        MemoryStore(context, name).use { store ->
            val wanted = store.saveExplicitFact("My favorite color is blue.")
            store.saveExplicitFact(wanted.text)
            store.saveExplicitFact("My passport number is 40482819.")
            val expected = listOf(wanted.text)

            val selection = store.selectForRequest("What is my favorite color?", MemoryPurpose.CHAT_DECISION)
            store.withRequestFacts(selection, MemoryPurpose.CHAT_DECISION, MemoryProvenance.CLEAN) { stage ->
                assertEquals(expected, stage.texts)
                assertEquals(MemoryProvenance.MEMORY_DERIVED, stage.outputProvenance)
                assertFalse(stage.outputProvenance.isEligibleForRestrictedRequest)
            }
            store.saveExplicitFact("My favorite color is orange.")
            store.withRequestFacts(selection, MemoryPurpose.CHAT_ANSWER, MemoryProvenance.CLEAN) { stage ->
                assertEquals(expected, stage.texts)
            }

            val plan = store.selectForRequest("What is my favorite color?", MemoryPurpose.AGENT_PLAN)
            store.withRequestFacts(plan, MemoryPurpose.AGENT_PLAN, MemoryProvenance.CLEAN) { stage ->
                assertEquals(setOf("My favorite color is blue.", "My favorite color is orange."), stage.texts.toSet())
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.withRequestFacts(plan, MemoryPurpose.CHAT_ANSWER, MemoryProvenance.CLEAN) {
                    error("A plan selection reached a chat serializer.")
                }
            }
        }
    } }

    @Test
    fun aChangedOrDeletedSelectionStopsTheEntireStageBeforeSerialization() = withDatabase { name, _ -> kotlinx.coroutines.runBlocking {
        MemoryStore(context, name).use { store ->
            val first = store.saveExplicitFact("Ёлка, образец 1.")
            val second = store.saveExplicitFact("Ёлка, образец 2.")
            val initial = store.selectForRequest("ёлки", MemoryPurpose.CHAT_DECISION)

            MemoryStore(context, name).use { writer ->
                writer.edit(first.id, first.revision, "Ёлка, обновлённый образец.")
                assertThrows(MemoryRequestInvalidatedException::class.java) {
                    store.withRequestFacts(initial, MemoryPurpose.CHAT_ANSWER, MemoryProvenance.MEMORY_DERIVED) {
                        error("An edited selection reached the serializer.")
                    }
                }

                val retry = store.selectForRequest("ёлки", MemoryPurpose.CHAT_DECISION)
                store.withRequestFacts(retry, MemoryPurpose.CHAT_DECISION, MemoryProvenance.CLEAN) { stage ->
                    assertEquals(setOf("Ёлка, обновлённый образец.", second.text), stage.texts.toSet())
                }
                writer.delete(second.id, second.revision)
                assertThrows(MemoryRequestInvalidatedException::class.java) {
                    store.withRequestFacts(retry, MemoryPurpose.CHAT_ANSWER, MemoryProvenance.MEMORY_DERIVED) {
                        error("A deleted selection reached the serializer.")
                    }
                }
            }
        }
    } }

    @Test
    fun restrictedPurposesRejectSelectionsAndInheritedProvenanceRemainsVisible() = withDatabase { name, file -> kotlinx.coroutines.runBlocking {
        MemoryStore(context, name).use { store ->
            val empty = store.selectForRequest("?!", MemoryPurpose.CHAT_DECISION)
            for (purpose in listOf(MemoryPurpose.MINI_APP_GENERATION, MemoryPurpose.LESSON, MemoryPurpose.AUTOMATION)) {
                assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.selectForRequest("ёлка", purpose) } }
                assertThrows(IllegalArgumentException::class.java) {
                    store.withRequestFacts(empty, purpose, MemoryProvenance.CLEAN) {
                        error("A restricted purpose reached the memory serializer.")
                    }
                }
            }
            for (origin in MemoryProvenance.entries) {
                store.withRequestFacts(empty, MemoryPurpose.CHAT_ANSWER, origin) { stage ->
                    assertTrue(stage.texts.isEmpty())
                    assertEquals(origin, stage.outputProvenance)
                    assertEquals(origin == MemoryProvenance.CLEAN, stage.outputProvenance.isEligibleForRestrictedRequest)
                }
            }
            assertEquals(MemoryProvenance.MEMORY_DERIVED, MemoryProvenance.UNKNOWN.merge(MemoryProvenance.MEMORY_DERIVED))
            assertEquals(MemoryProvenance.UNKNOWN, MemoryProvenance.UNKNOWN.merge(MemoryProvenance.CLEAN))
        }
        assertFalse(file.exists())
    } }

    private fun assertRejected(expected: MemoryRejection, operation: () -> Unit) {
        val error = assertThrows(MemoryStoreException::class.java) { operation() }
        assertEquals(expected, error.reason)
    }

    private fun assertClosed(operation: () -> Unit) {
        val error = assertThrows(IllegalStateException::class.java) { operation() }
        assertEquals("The memory store is closed.", error.message)
    }

    private fun openFixture(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file,
        SQLiteDatabase.OpenParams.Builder()
            .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .build(),
    )

    private fun withDatabase(test: (String, File) -> Unit) {
        val name = "memory-workflow-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        try {
            test(name, file)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
