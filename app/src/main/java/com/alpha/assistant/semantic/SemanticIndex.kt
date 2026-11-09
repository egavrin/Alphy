package com.alpha.assistant.semantic

import android.app.appsearch.AppSearchBatchResult
import android.app.appsearch.AppSearchManager
import android.app.appsearch.AppSearchResult
import android.app.appsearch.AppSearchSchema
import android.app.appsearch.AppSearchSession
import android.app.appsearch.BatchResultCallback
import android.app.appsearch.EmbeddingVector
import android.app.appsearch.GenericDocument
import android.app.appsearch.PutDocumentsRequest
import android.app.appsearch.RemoveByDocumentIdRequest
import android.app.appsearch.SearchSpec
import android.app.appsearch.SetSchemaRequest
import android.app.appsearch.SetSchemaResponse
import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SemanticRecord(val id: String, val revision: String, val text: String)
internal data class SemanticHit(val id: String, val revision: String, val offset: Int, val end: Int, val score: Double)

private const val SCHEMA = "SemanticChunk"
private const val NAMESPACE = "chunks"
private const val BATCH = 48
private val direct = Executor { it.run() }
private val metadata = listOf("source", "revision", "start", "end", "signature")
private val schema = AppSearchSchema.Builder(SCHEMA)
    .addProperty(AppSearchSchema.StringPropertyConfig.Builder("source").build())
    .addProperty(AppSearchSchema.StringPropertyConfig.Builder("revision").build())
    .addProperty(AppSearchSchema.StringPropertyConfig.Builder("signature").build())
    .addProperty(AppSearchSchema.LongPropertyConfig.Builder("start").build())
    .addProperty(AppSearchSchema.LongPropertyConfig.Builder("end").build())
    .addProperty(AppSearchSchema.EmbeddingPropertyConfig.Builder("vector")
        .setIndexingType(AppSearchSchema.EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY).build())
    .build()

internal class SemanticIndex(private val context: Context, name: String) {
    private val database = "semantic-" + semanticRevision(name)
    private val lock = Mutex()
    private var session: AppSearchSession? = null
    private var indexed: MutableMap<String, String>? = null
    private var reconciled: List<SemanticRecord>? = null
    private val runtime = SemanticRuntime.get(context)

    suspend fun update(records: () -> List<SemanticRecord>, isCurrent: (String, String) -> Boolean) {
        access { db -> reconcile(db, records(), isCurrent) }
    }

    suspend fun search(
        query: String,
        cutoff: Float,
        limit: Int,
        records: () -> List<SemanticRecord>,
        isCurrent: (String, String) -> Boolean,
        accept: (String) -> Boolean = { true },
    ): List<SemanticHit> {
        if (query.isBlank()) return emptyList()
        val ticket = runtime.ticket()
        return access { db ->
            if (reconcile(db, records(), isCurrent) == 0) return@access emptyList()
            val matches = linkedMapOf<String, SemanticHit>()
            for (vector in runtime.queries(query)) {
                val spec = projected()
                    .setListFilterQueryLanguageEnabled(true)
                    .setDefaultEmbeddingSearchMetricType(SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE)
                    .addEmbeddingParameters(EmbeddingVector(vector, VECTOR_SIGNATURE))
                    .setRankingStrategy("max(this.matchedSemanticScores(getEmbeddingParameter(0)))")
                    .build()
                db.search("semanticSearch(getEmbeddingParameter(0), $cutoff, 1, \"COSINE\")", spec).use { results ->
                    val seen = hashSetOf<String>()
                    while (seen.size < limit) {
                        val page = result { results.getNextPage(direct, it) }
                        if (page.isEmpty()) break
                        for (entry in page) {
                            val doc = entry.genericDocument
                            val id = checkNotNull(doc.getPropertyString("source"))
                            val revision = checkNotNull(doc.getPropertyString("revision"))
                            if (id in seen || !accept(id) || !isCurrent(id, revision)) continue
                            seen.add(id)
                            val hit = SemanticHit(id, revision, doc.getPropertyLong("start").toInt(),
                                doc.getPropertyLong("end").toInt(), entry.rankingSignal)
                            if (hit.score > (matches[id]?.score ?: Double.NEGATIVE_INFINITY)) matches[id] = hit
                            if (seen.size == limit) break
                        }
                    }
                }
            }
            runtime.checkActive(ticket)
            matches.values.sortedByDescending { it.score }.filter { accept(it.id) && isCurrent(it.id, it.revision) }.take(limit)
        }
    }

    private suspend fun reconcile(db: AppSearchSession, records: List<SemanticRecord>, current: (String, String) -> Boolean): Int {
        val ticket = runtime.ticket()
        val known = indexed ?: loadMetadata(db).also { indexed = it }
        if (reconciled == records) return known.size
        val wanted = records.associateBy { it.id }
        val remove = known.keys.filter { id ->
            val source = id.substringBeforeLast('#')
            wanted[source]?.revision != known[id]
        }
        for (batch in remove.chunked(BATCH)) {
            currentCoroutineContext().ensureActive()
            write { db.remove(RemoveByDocumentIdRequest.Builder(NAMESPACE).addIds(batch).build(), direct, it) }
            batch.forEach { known.remove(it) }
        }
        val pending = records.asSequence().flatMap { record ->
            textChunks(record.text).asSequence().map { chunk -> Pending(record, chunk) }
        }.filter { it.id !in known }
        val recent = object : LinkedHashMap<String, FloatArray>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 128
        }
        var complete = true
        for (batch in pending.chunked(BATCH)) {
            currentCoroutineContext().ensureActive()
            val inputs = batch.map { it.record.text.substring(it.chunk.start, it.chunk.end) }
            val missing = inputs.distinct().filterNot(recent::containsKey)
            val cached = inputs.mapNotNull { input -> recent[input]?.let { input to it } }.toMap()
            val fresh = missing.zip(runtime.documents(missing)).toMap()
            recent.putAll(fresh)
            val vectors = cached + fresh
            val documents = batch.zip(inputs).mapNotNull { (entry, input) ->
                if (current(entry.record.id, entry.record.revision)) entry.document(vectors.getValue(input))
                else {
                    complete = false
                    null
                }
            }
            if (documents.isEmpty()) continue
            runtime.checkActive(ticket)
            write { db.put(PutDocumentsRequest.Builder().addGenericDocuments(documents).build(), direct, it) }
            documents.forEach { known[it.id] = checkNotNull(it.getPropertyString("revision")) }
        }
        reconciled = records.takeIf { complete }
        return known.size
    }

    private data class Pending(val record: SemanticRecord, val chunk: TextChunk) {
        val id get() = "${record.id}#${chunk.start}"

        fun document(vector: FloatArray) = GenericDocument.Builder<GenericDocument.Builder<*>>(NAMESPACE, id, SCHEMA)
            .setPropertyString("source", record.id)
            .setPropertyString("revision", record.revision)
            .setPropertyString("signature", VECTOR_SIGNATURE)
            .setPropertyLong("start", chunk.start.toLong())
            .setPropertyLong("end", chunk.end.toLong())
            .setPropertyEmbedding("vector", EmbeddingVector(vector, VECTOR_SIGNATURE))
            .build()
    }

    private suspend fun loadMetadata(db: AppSearchSession): MutableMap<String, String> = buildMap {
        db.search("", projected().build()).use { results ->
            while (true) {
                val page = result { results.getNextPage(direct, it) }
                if (page.isEmpty()) break
                for (entry in page) {
                    val doc = entry.genericDocument
                    put(doc.id, if (doc.getPropertyString("signature") == VECTOR_SIGNATURE)
                        checkNotNull(doc.getPropertyString("revision")) else "")
                }
            }
        }
    }.toMutableMap()

    private suspend fun <T> access(block: suspend (AppSearchSession) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val db = session ?: open().also { session = it }
                block(db)
            } catch (cancelled: CancellationException) {
                indexed = null
                reconciled = null
                throw cancelled
            } catch (failure: Exception) {
                indexed = null
                reconciled = null
                if (failure is SemanticUnavailable) throw failure
                throw SemanticUnavailable(failure)
            }
        }
    }

    private suspend fun open(): AppSearchSession {
        val manager = checkNotNull(context.getSystemService(AppSearchManager::class.java))
        val session = result<AppSearchSession>(AppSearchSession::close) {
            manager.createSearchSession(AppSearchManager.SearchContext.Builder(database).build(), direct, it)
        }
        try {
            val request = SetSchemaRequest.Builder().addSchemas(schema)
                .setSchemaTypeDisplayedBySystem(SCHEMA, false).build()
            result<SetSchemaResponse> { session.setSchema(request, direct, direct, it) }
        } catch (failure: Exception) {
            session.close()
            throw failure
        }
        return session
    }

    private fun projected() = SearchSpec.Builder().addFilterNamespaces(NAMESPACE)
        .addProjection(SCHEMA, metadata).setResultCountPerPage(64)

    private suspend fun <T> result(
        closeIfCancelled: (T) -> Unit = {},
        call: (Consumer<AppSearchResult<T>>) -> Unit,
    ): T =
        suspendCancellableCoroutine { continuation ->
            call(Consumer { response ->
                if (response.isSuccess) continuation.resume(checkNotNull(response.resultValue)) { _, value, _ ->
                    closeIfCancelled(value)
                }
                else continuation.resumeWithException(IllegalStateException(response.errorMessage))
            })
        }

    private suspend fun write(call: (BatchResultCallback<String, Void>) -> Unit): Unit = withContext(NonCancellable) {
        suspendCancellableCoroutine { continuation ->
            call(object : BatchResultCallback<String, Void> {
                override fun onResult(result: AppSearchBatchResult<String, Void>) {
                    if (result.isSuccess) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException(result.failures.values.first().errorMessage))
                }
                override fun onSystemError(throwable: Throwable?) {
                    continuation.resumeWithException(throwable ?: IllegalStateException("AppSearch failed"))
                }
            })
        }
    }

    companion object {
        private val corpora = mutableMapOf<String, SemanticIndex>()

        fun get(context: Context, name: String): SemanticIndex = synchronized(corpora) {
            corpora.getOrPut(name) { SemanticIndex(context.applicationContext, name) }
        }
    }
}

internal fun semanticRevision(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
