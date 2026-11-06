package com.alpha.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID

/** Stores memory facts in an independent, private database. */
class MemoryStore(context: Context, name: String = "alpha-memory.db") : Closeable {
    init {
        require(name.isNotBlank() && '/' !in name && name != "." && name != "..") {
            "The memory database requires a file name."
        }
    }

    private val helper = MemoryDatabase(context.applicationContext, name)
    private val lifecycleLock = Any()
    @Volatile private var closeRequested = false

    /** Reject new operations immediately. Call close to release the database. */
    fun beginClose() {
        closeRequested = true
    }

    /** Finish the current operation and close this store permanently. */
    override fun close() {
        beginClose()
        synchronized(lifecycleLock) { helper.close() }
    }

    /** The caller must verify that the user issued an explicit save command. */
    fun saveExplicitFact(text: String): MemoryFact = withOpenStore {
        require(text.isNotBlank()) { "The memory fact requires text." }
        transaction { insertFact(text, MemorySource.EXPLICIT_SAVE_COMMAND) }
    }

    /** Use one host-generated ID for the accepted offer, including every retry. */
    internal fun saveAcceptedSuggestion(acceptanceId: String, text: String): Unit = withOpenStore {
        require(acceptanceId.isNotBlank()) { "The memory suggestion requires an acceptance ID." }
        require(text.isNotBlank()) { "The memory fact requires text." }
        val fingerprint = suggestionFingerprint(acceptanceId, text)
        transaction<Unit> {
            val previous = rawQuery(
                "SELECT fingerprint FROM $RECEIPTS WHERE acceptance_id=?", arrayOf(acceptanceId),
            ).use { row -> if (row.moveToFirst()) row.getBlob(0) else null }
            if (previous != null) {
                if (!MessageDigest.isEqual(previous, fingerprint)) throw MemorySuggestionConflictException()
                return@transaction
            }
            insertFact(text, MemorySource.ACCEPTED_SUGGESTION)
            val receipt = insertOrThrow(RECEIPTS, null, ContentValues().apply {
                put("acceptance_id", acceptanceId)
                put("fingerprint", fingerprint)
            })
            check(receipt != -1L) { "The memory suggestion receipt write failed." }
        }
    }

    fun list(): List<MemoryFact> = withOpenStore { readFacts() }

    fun edit(id: String, expectedRevision: Long, text: String): MemoryFact = withOpenStore {
        require(text.isNotBlank()) { "The memory fact requires text." }
        transaction {
            val current = requireRevision(id, expectedRevision)
            val edited = current.copy(text = text, revision = Math.incrementExact(current.revision))
            val changed = update(TABLE, ContentValues().apply {
                put("text", edited.text)
                put("revision", edited.revision)
            }, "id=?", arrayOf(id))
            check(changed == 1) { "The memory edit failed." }
            verifyWrite(edited)
        }
    }

    fun delete(id: String, expectedRevision: Long): Unit = withOpenStore {
        transaction {
            requireRevision(id, expectedRevision)
            check(delete(TABLE, "id=?", arrayOf(id)) == 1) { "The memory deletion failed." }
        }
    }

    /** Request serializers must also enforce their own memory exclusions. */
    fun match(query: String, purpose: MemoryPurpose): List<MemoryFact> = withOpenStore {
        RussianMemoryMatcher.match(query, purpose) { readFacts() }
    }

    /** Select once when the request starts. Keep only the matched IDs and revisions. */
    internal fun selectForRequest(query: String, purpose: MemoryPurpose): MemoryRequestSelection = withOpenStore {
        val family = purpose.requestFamily()
        val matches = RussianMemoryMatcher.match(query, purpose) { readFacts() }
        SelectedFacts(family, matches.map { FactRevision(it.id, it.revision) })
    }

    /**
     * Validate the entire selection before serialization. An edit or delete invalidates the request.
     * Serialize synchronously in the callback. Send the request after the callback returns.
     */
    internal fun <T> withRequestFacts(
        selection: MemoryRequestSelection,
        purpose: MemoryPurpose,
        inheritedProvenance: MemoryProvenance,
        serialize: (MemoryRequestFacts) -> T,
    ): T = withOpenStore {
        val selected = selection as SelectedFacts
        require(selected.family == purpose.requestFamily()) { "The memory selection belongs to another request family." }
        if (selected.revisions.isEmpty()) {
            return@withOpenStore serialize(MemoryRequestFacts(emptyList(), inheritedProvenance))
        }

        // The transaction excludes writes from other store instances during serialization.
        transaction {
            val texts = buildList {
                for ((id, revision) in selected.revisions) {
                    val fact = read(id)
                    if (fact == null || fact.revision != revision) throw MemoryRequestInvalidatedException()
                    add(fact.text)
                }
            }
            serialize(MemoryRequestFacts(texts, MemoryProvenance.MEMORY_DERIVED))
        }
    }

    private enum class RequestFamily { CHAT, AGENT_PLAN }

    private class SelectedFacts(val family: RequestFamily, val revisions: List<FactRevision>) : MemoryRequestSelection

    private data class FactRevision(val id: String, val revision: Long)

    private fun MemoryPurpose.requestFamily(): RequestFamily = when (this) {
        MemoryPurpose.CHAT_DECISION, MemoryPurpose.CHAT_ANSWER -> RequestFamily.CHAT
        MemoryPurpose.AGENT_PLAN -> RequestFamily.AGENT_PLAN
        MemoryPurpose.MINI_APP_GENERATION, MemoryPurpose.LESSON, MemoryPurpose.AUTOMATION ->
            throw IllegalArgumentException("This request purpose cannot use memory.")
    }

    private fun MemoryDatabase.readFacts(): List<MemoryFact> = writableDatabase.rawQuery(
        "SELECT $COLUMNS FROM $TABLE ORDER BY id", null,
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) add(rows.fact())
        }
    }

    private fun SQLiteDatabase.read(id: String): MemoryFact? = rawQuery(
        "SELECT $COLUMNS FROM $TABLE WHERE id=?", arrayOf(id),
    ).use { row -> if (row.moveToFirst()) row.fact() else null }

    private fun SQLiteDatabase.requireRevision(id: String, expectedRevision: Long): MemoryFact {
        val fact = read(id) ?: throw MemoryStoreException(MemoryRejection.FACT_NOT_FOUND)
        if (fact.revision != expectedRevision) throw MemoryStoreException(MemoryRejection.STALE_REVISION)
        return fact
    }

    private fun SQLiteDatabase.insertFact(text: String, source: MemorySource): MemoryFact {
        val fact = MemoryFact(UUID.randomUUID().toString(), text, source, 1)
        insertOrThrow(TABLE, null, ContentValues().apply {
            put("id", fact.id)
            put("text", fact.text)
            put("source", fact.source.name)
            put("revision", fact.revision)
        })
        return verifyWrite(fact)
    }

    private fun suggestionFingerprint(acceptanceId: String, text: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(acceptanceId.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        // Hash exact UTF-16 code units so the receipt needs no copy of the fact text.
        for (character in text) {
            digest.update((character.code ushr 8).toByte())
            digest.update(character.code.toByte())
        }
        return digest.digest()
    }

    private fun SQLiteDatabase.verifyWrite(fact: MemoryFact): MemoryFact {
        // An SQLite write can succeed even when Android cannot read the row through a cursor.
        check(read(fact.id) == fact) { "The memory write failed verification." }
        return fact
    }

    private fun Cursor.fact() = MemoryFact(
        id = getString(0),
        text = getString(1),
        source = MemorySource.valueOf(getString(2)),
        revision = getLong(3),
    )

    private fun checkOpen() {
        check(!closeRequested) { "The memory store is closed." }
    }

    private fun <T> withOpenStore(operation: MemoryDatabase.() -> T): T {
        checkOpen()
        return synchronized(lifecycleLock) {
            checkOpen()
            helper.operation()
        }
    }

    private fun <T> MemoryDatabase.transaction(operation: SQLiteDatabase.() -> T): T {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val result = database.operation()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    private class MemoryDatabase(context: Context, name: String) : SQLiteOpenHelper(
        context,
        name,
        VERSION,
        SQLiteDatabase.OpenParams.Builder()
            .addOpenFlags(SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            // Android's default handler deletes the file on corruption.
            .setErrorHandler { }
            .build(),
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            // A second helper can finish creation before this helper acquires the transaction.
            if (db.version == VERSION) return
            db.requireSchema()
            db.execSQL(CREATE_TABLE)
            db.execSQL(CREATE_RECEIPTS)
        }

        override fun onOpen(db: SQLiteDatabase) {
            db.requireSchema(CREATE_TABLE, CREATE_RECEIPTS)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Another helper can complete this upgrade before this helper starts its transaction.
            if (db.version == VERSION) return
            if (oldVersion != 1 || newVersion != VERSION) unknownVersion(oldVersion)
            db.requireSchema(CREATE_TABLE_V1)
            db.execSQL("ALTER TABLE $TABLE RENAME TO memory_facts_v1")
            db.execSQL(CREATE_TABLE)
            db.execSQL("INSERT INTO $TABLE($COLUMNS) SELECT $COLUMNS FROM memory_facts_v1")
            db.execSQL("DROP TABLE memory_facts_v1")
            db.execSQL(CREATE_RECEIPTS)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = unknownVersion(oldVersion)

        private fun SQLiteDatabase.requireSchema(vararg definitions: String) {
            rawQuery(SCHEMA_QUERY, null).use { schema ->
                val actual = buildSet {
                    while (schema.moveToNext()) add(schema.getString(0))
                }
                check(actual == definitions.toSet()) { "Unknown memory database schema." }
            }
        }

        private fun unknownVersion(version: Int): Nothing = error("Unknown memory database version $version.")
    }

    private companion object {
        const val VERSION = 2
        const val TABLE = "memory_facts"
        const val RECEIPTS = "memory_suggestion_receipts"
        const val COLUMNS = "id, text, source, revision"
        const val SCHEMA_QUERY = "SELECT sql FROM sqlite_master WHERE substr(name, 1, 7) <> 'sqlite_'"
        const val CREATE_TABLE_V1 = """CREATE TABLE memory_facts(
            id TEXT PRIMARY KEY NOT NULL,
            text TEXT NOT NULL,
            source TEXT NOT NULL CHECK(source = 'EXPLICIT_SAVE_COMMAND'),
            revision INTEGER NOT NULL CHECK(revision >= 1)
        )"""
        const val CREATE_TABLE = """CREATE TABLE memory_facts(
            id TEXT PRIMARY KEY NOT NULL,
            text TEXT NOT NULL,
            source TEXT NOT NULL CHECK(source IN ('EXPLICIT_SAVE_COMMAND', 'ACCEPTED_SUGGESTION')),
            revision INTEGER NOT NULL CHECK(revision >= 1)
        )"""
        const val CREATE_RECEIPTS = """CREATE TABLE memory_suggestion_receipts(
            acceptance_id TEXT PRIMARY KEY NOT NULL,
            fingerprint BLOB NOT NULL CHECK(typeof(fingerprint) = 'blob' AND length(fingerprint) = 32)
        )"""
    }
}
