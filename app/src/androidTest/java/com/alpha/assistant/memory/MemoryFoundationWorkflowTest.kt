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
                        assertClosed { store.match("Москва", MemoryPurpose.CHAT_ANSWER) }
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
        assertClosed { store.match("Москва", MemoryPurpose.MINI_APP_GENERATION) }
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
    fun concurrentAcceptanceFromSeparateStoresSavesOnlyOneFact() = withDatabase { name, _ ->
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            MemoryStore(context, name).use { first ->
                MemoryStore(context, name).use { second ->
                    assertTrue(first.list().isEmpty())
                    assertTrue(second.list().isEmpty())
                    val writes = listOf(first, second).map { store ->
                        executor.submit {
                            ready.countDown()
                            check(start.await(5, SECONDS))
                            store.saveAcceptedSuggestion("offer-concurrent", "  Образец факта.  ")
                        }
                    }
                    assertTrue(ready.await(5, SECONDS))
                    start.countDown()
                    writes.forEach { it.get(5, SECONDS) }
                    val fact = first.list().single()
                    assertEquals("  Образец факта.  ", fact.text)
                    assertEquals(MemorySource.ACCEPTED_SUGGESTION, fact.source)
                    assertEquals(1L, fact.revision)
                    assertEquals(listOf(fact), second.list())
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun replayCannotReplaceAnEditedFactOrRestoreADeletedFact() = withDatabase { name, _ ->
        val id = "offer-replay"
        val text = "Исходное предложение.\n"
        val fact = MemoryStore(context, name).use { store ->
            store.saveAcceptedSuggestion(id, text)
            store.list().single()
        }
        MemoryStore(context, name).use { store ->
            store.saveAcceptedSuggestion(id, text)
            assertEquals(listOf(fact), store.list())
            assertThrows(MemorySuggestionConflictException::class.java) { store.saveAcceptedSuggestion(id, text.trimEnd()) }
            assertEquals(listOf(fact), store.list())
            val edited = store.edit(fact.id, fact.revision, "Текст после правки.")
            assertEquals(MemorySource.ACCEPTED_SUGGESTION, edited.source)
            store.saveAcceptedSuggestion(id, text)
            assertEquals(listOf(edited), store.list())
            store.delete(edited.id, edited.revision)
        }
        MemoryStore(context, name).use { store ->
            store.saveAcceptedSuggestion(id, text)
            assertThrows(MemorySuggestionConflictException::class.java) { store.saveAcceptedSuggestion(id, "Другой текст.") }
            assertTrue(store.list().isEmpty())
        }
    }

    @Test
    fun receiptWriteFailureRollsBackTheFactAndAllowsRetryAfterReopen() = withDatabase { name, file ->
        val existing = MemoryStore(context, name).use { store ->
            val existing = store.saveExplicitFact("Существующий факт.")
            openFixture(file).use { database ->
                database.execSQL(
                    "CREATE TRIGGER reject_receipt BEFORE INSERT ON memory_suggestion_receipts " +
                        "BEGIN SELECT RAISE(ABORT, 'workflow receipt failure'); END",
                )
                try {
                    assertThrows(SQLiteException::class.java) {
                        store.saveAcceptedSuggestion("offer-failed", "Предложенный факт.")
                    }
                    assertEquals(listOf(existing), store.list())
                } finally {
                    database.execSQL("DROP TRIGGER reject_receipt")
                }
                database.execSQL(
                    "CREATE TRIGGER ignore_receipt BEFORE INSERT ON memory_suggestion_receipts " +
                        "BEGIN SELECT RAISE(IGNORE); END",
                )
                try {
                    assertThrows(IllegalStateException::class.java) {
                        store.saveAcceptedSuggestion("offer-failed", "Предложенный факт.")
                    }
                    assertEquals(listOf(existing), store.list())
                } finally {
                    database.execSQL("DROP TRIGGER ignore_receipt")
                }
            }
            existing
        }
        MemoryStore(context, name).use { store ->
            store.saveAcceptedSuggestion("offer-failed", "Предложенный факт.")
            store.saveAcceptedSuggestion("offer-failed", "Предложенный факт.")
            val facts = store.list()
            assertEquals(2, facts.size)
            assertEquals(existing, facts.single { it.source == MemorySource.EXPLICIT_SAVE_COMMAND })
            assertEquals("Предложенный факт.", facts.single { it.source == MemorySource.ACCEPTED_SUGGESTION }.text)
        }
    }

    @Test
    fun versionOneUpgradePreservesExistingIdsTextSourcesAndRevisions() = withDatabase { name, file ->
        val existing = MemoryFact("existing-id", "  Сохранённый факт.\n", MemorySource.EXPLICIT_SAVE_COMMAND, 4)
        val versionOneSchema = """CREATE TABLE memory_facts(
            id TEXT PRIMARY KEY NOT NULL,
            text TEXT NOT NULL,
            source TEXT NOT NULL CHECK(source = 'EXPLICIT_SAVE_COMMAND'),
            revision INTEGER NOT NULL CHECK(revision >= 1)
        )"""
        openFixture(file).use { database ->
            database.execSQL(versionOneSchema)
            database.execSQL(
                "INSERT INTO memory_facts VALUES (?, ?, ?, ?)",
                arrayOf<Any>(existing.id, existing.text, existing.source.name, existing.revision),
            )
            database.version = 1
        }
        MemoryStore(context, name).use { store ->
            assertEquals(listOf(existing), store.list())
            store.saveAcceptedSuggestion("offer-new", "Новый факт.")
        }
        MemoryStore(context, name).use { store ->
            assertEquals(existing, store.list().single { it.source == MemorySource.EXPLICIT_SAVE_COMMAND })
            assertEquals("Новый факт.", store.list().single { it.source == MemorySource.ACCEPTED_SUGGESTION }.text)
        }
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
    fun matchesRussianInflectionsAndWholeWordsOnly() = withDatabase { name, _ ->
        MemoryStore(context, name).use { store ->
            val tree = store.saveExplicitFact("Люблю ёлки, кошек и полёты. Гуляю с кошками.")
            val safety = store.saveExplicitFact("Важна безопасность.")
            val house = store.saveExplicitFact("Дом.")
            val short = store.saveExplicitFact("Он.")
            store.saveExplicitFact("Слон играет в домино.")
            store.saveExplicitFact("xёлки")
            store.saveExplicitFact("apple")

            for (query in listOf("Apple, Е\u0308ЛКОЙ!", "КОШКЕ", "ПОЛЕТАМИ")) {
                assertEquals(listOf(tree), store.match(query, MemoryPurpose.CHAT_DECISION))
            }
            assertEquals(listOf(safety), store.match("безопасный", MemoryPurpose.CHAT_ANSWER))
            assertEquals(listOf(house), store.match("ДОМ", MemoryPurpose.AGENT_PLAN))
            assertEquals(listOf(short), store.match("он", MemoryPurpose.CHAT_ANSWER))
            for (query in listOf("", " ...?! ", "xёлки", "apple")) {
                assertTrue(store.match(query, MemoryPurpose.CHAT_ANSWER).isEmpty())
            }
        }
    }

    @Test
    fun rankUsesDistinctTermsAndStableIdsAndReturnsAtMostFiveFacts() = withDatabase { name, _ ->
        val expected = MemoryStore(context, name).use { store ->
            val strongest = store.saveExplicitFact("Кошки и ёлки.")
            val tied = (1..7).map { store.saveExplicitFact("Кошка номер $it.") }
            store.saveExplicitFact(strongest.text)
            val firstCopy = store.list().filter { it.text == strongest.text }.minBy { it.id }
            listOf(firstCopy) + tied.sortedBy { it.id }.take(4)
        }

        MemoryStore(context, name).use { store ->
            val query = "кошками, кошкой, ёлками"
            assertEquals(expected, store.match(query, MemoryPurpose.CHAT_ANSWER))
            assertEquals(expected, store.match(query, MemoryPurpose.CHAT_DECISION))
            assertEquals(expected, store.match(query, MemoryPurpose.AGENT_PLAN))
            for (purpose in listOf(MemoryPurpose.MINI_APP_GENERATION, MemoryPurpose.LESSON, MemoryPurpose.AUTOMATION)) {
                assertTrue(store.match(query, purpose).isEmpty())
            }
        }
    }

    @Test
    fun requestStagesShareOneLocalSelectionAndSendOnlyCurrentFactText() = withDatabase { name, _ ->
        MemoryStore(context, name).use { store ->
            val matching = (1..7).map { store.saveExplicitFact("Ёлка, образец $it.") }
            store.saveExplicitFact("Сосна, образец 8.")
            val expected = matching.sortedBy { it.id }.take(5).map { it.text }

            val selection = store.selectForRequest("ёлками, берёзами", MemoryPurpose.CHAT_DECISION)
            store.withRequestFacts(selection, MemoryPurpose.CHAT_DECISION, MemoryProvenance.CLEAN) { stage ->
                assertEquals(expected, stage.texts)
                assertEquals(MemoryProvenance.MEMORY_DERIVED, stage.outputProvenance)
                assertFalse(stage.outputProvenance.isEligibleForRestrictedRequest)
            }
            store.saveExplicitFact("Ёлка и берёза, образец 9.")
            store.withRequestFacts(selection, MemoryPurpose.CHAT_ANSWER, MemoryProvenance.CLEAN) { stage ->
                assertEquals(expected, stage.texts)
            }

            val plan = store.selectForRequest("сосной", MemoryPurpose.AGENT_PLAN)
            store.withRequestFacts(plan, MemoryPurpose.AGENT_PLAN, MemoryProvenance.CLEAN) { stage ->
                assertEquals(listOf("Сосна, образец 8."), stage.texts)
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.withRequestFacts(plan, MemoryPurpose.CHAT_ANSWER, MemoryProvenance.CLEAN) {
                    error("A plan selection reached a chat serializer.")
                }
            }
        }
    }

    @Test
    fun aChangedOrDeletedSelectionStopsTheEntireStageBeforeSerialization() = withDatabase { name, _ ->
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
    }

    @Test
    fun restrictedPurposesRejectSelectionsAndInheritedProvenanceRemainsVisible() = withDatabase { name, file ->
        MemoryStore(context, name).use { store ->
            val empty = store.selectForRequest("?!", MemoryPurpose.CHAT_DECISION)
            for (purpose in listOf(MemoryPurpose.MINI_APP_GENERATION, MemoryPurpose.LESSON, MemoryPurpose.AUTOMATION)) {
                assertThrows(IllegalArgumentException::class.java) { store.selectForRequest("ёлка", purpose) }
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
    }

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
