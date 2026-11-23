package com.alpha.assistant.semantic

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The candidates that the template prompt gets for one request (stage 6, S1 and S3). */
internal const val LIBRARY_CANDIDATES = 24
/** The longest wait of a template call for its candidates (S4): a later search gives the call the elements only. */
internal const val LIBRARY_WAIT_MS = 150L
private const val CATALOG_ASSET = "library/catalog.json"

/** One ready object of the catalog asset that kit/build.js writes: its names and other words in each language, its one line, and its parts. */
internal data class LibraryEntry(val id: String, val name: String, val names: List<String>, val words: List<String>, val line: String, val parts: List<String>) {
    /** The text that the embedding model reads for this entry: its names, its line, and its other words. */
    val document: String get() = (names + line + words).joinToString(". ")

    companion object {
        /** The entries of a catalog JSON text {objects: [...]}, as the asset and the test fixtures hold them. */
        fun parse(text: String): List<LibraryEntry> {
            val list = JSONObject(text).getJSONArray(if (JSONObject(text).has("objects")) "objects" else "entries")
            fun texts(value: Any?): List<String> = when (value) {
                is JSONArray -> List(value.length()) { value.get(it) }.flatMap(::texts)
                is JSONObject -> value.keys().asSequence().toList().sorted().flatMap { texts(value.get(it)) }
                is String -> listOf(value)
                else -> emptyList()
            }
            return List(list.length()) { index ->
                val one = list.getJSONObject(index)
                val parts = one.optJSONArray("parts")?.let { array -> List(array.length()) { array.getJSONObject(it).getString("id") } }.orEmpty()
                LibraryEntry(one.getString("id"), one.optString("name", one.getString("id").substringAfter('.')), texts(one.opt("names")),
                    texts(one.opt("words")), one.optString("line"), parts)
            }
        }
    }
}

/**
 * The candidates of one search and their source (fix list 4, K4): [VECTORS] for the ranked list of the embedding model, [WORDS] for the word
 * match alone, and [NONE] for no candidate. [mark] is the detail of the trace mark library_candidates, with no name and no request text.
 */
internal data class LibraryFound(val entries: List<LibraryEntry>, val source: String) {
    val mark: String get() = "${entries.size} ${if (entries.isEmpty()) NONE else source}"

    companion object {
        const val VECTORS = "vectors"
        const val WORDS = "words"
        const val NONE = "none"
        val EMPTY = LibraryFound(emptyList(), NONE)
    }
}

/** A started search (fix list 4, K1): the ranked list of the vectors, and the word match, which needs no model and is ready at once. */
internal class LibraryQuery(val search: Deferred<LibraryFound>, val words: Deferred<LibraryFound>)

/** The vectors of the search: the embedding model of the app (SemanticRuntime), or a fake of a test. A failure is SemanticUnavailable. */
internal interface LibraryVectors {
    /** The vectors of each piece of a request. */
    suspend fun query(text: String): List<FloatArray>
    suspend fun documents(texts: List<String>): List<FloatArray>
}

/**
 * The search of the ready objects for a request (stage 6, S1 to S5): the cosine of EmbeddingGemma vectors and a word match of the names and other
 * words, with no model call. The vectors of the entries are kept in [store] with the vector [signature] of the model, so a new model makes new
 * vectors (S2). With no model, the search is the word match only.
 */
internal class LibrarySearch(
    private val entries: List<LibraryEntry>,
    private val vectors: LibraryVectors?,
    private val store: File?,
    private val signature: String = VECTOR_SIGNATURE,
) {
    private val lock = Mutex()
    private var known: Map<String, FloatArray>? = null
    /** The number of entry vectors that the model made in this process: a test reads it to see that old vectors were not used. */
    @Volatile internal var made = 0

    /** The [count] best entries for [request], best first: all scored entries with the model, the matched entries with no model. */
    suspend fun candidates(request: String, count: Int = LIBRARY_CANDIDATES): List<LibraryEntry> = found(request, count).entries

    /** The word match alone, with no model: the entries whose names or other words are in [request], best first (K1). */
    fun words(request: String, count: Int = LIBRARY_CANDIDATES): List<LibraryEntry> {
        val stems = stems(request)
        val match = entries.associateWith { entry -> matchScore(stems, entry) }
        return entries.filter { match.getValue(it) > 0f }.sortedByDescending { match.getValue(it) }.take(count)
    }

    /** Starts the search of [request] in [scope] and its word match beside it: the caller waits for them with [within]. */
    fun query(request: String, scope: CoroutineScope): LibraryQuery =
        LibraryQuery(scope.async { found(request) }, scope.async { LibraryFound(words(request), LibraryFound.WORDS) })

    /** [candidates] with their source: the vectors, or the word match when the model is unavailable. */
    suspend fun found(request: String, count: Int = LIBRARY_CANDIDATES): LibraryFound {
        val stems = stems(request)
        val match = entries.associateWith { entry -> matchScore(stems, entry) }
        val cosine = try {
            vectors?.let { model ->
                val query = model.query(request)
                val document = documentVectors(model)
                entries.associateWith { entry -> document[entry.document]?.let { vector -> query.maxOfOrNull { cosine(it, vector) } } ?: 0f }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SemanticUnavailable) {
            null
        }
        return if (cosine == null) LibraryFound(words(request, count), LibraryFound.WORDS)
            else LibraryFound(entries.sortedByDescending { cosine.getValue(it) + MATCH_WEIGHT * match.getValue(it) }.take(count), LibraryFound.VECTORS)
    }

    /** Makes the vectors of the entries now, so the first request does not wait for them (S5: the voice mode starts it). */
    suspend fun warm() {
        val model = vectors ?: return
        try { documentVectors(model) } catch (error: CancellationException) { throw error } catch (_: SemanticUnavailable) {}
    }

    private suspend fun documentVectors(model: LibraryVectors): Map<String, FloatArray> = lock.withLock {
        val texts = entries.map { it.document }.distinct()
        val have = (known ?: withContext(Dispatchers.IO) { read() }).filterKeys { it in texts }
        val missing = texts.filter { it !in have }
        if (missing.isEmpty()) return@withLock have.also { known = it }
        val fresh = missing.zip(model.documents(missing)).toMap()
        made += fresh.size
        (have + fresh).also { all ->
            known = all
            withContext(Dispatchers.IO) { write(all) }
        }
    }

    /** The stored vectors, or none for a file of another signature, another dimension, or no file (S2). */
    private fun read(): Map<String, FloatArray> = try {
        store?.takeIf { it.isFile }?.let { file ->
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != signature) return emptyMap()
                val count = input.readInt()
                buildMap {
                    repeat(count) {
                        val text = input.readUTF()
                        put(text, FloatArray(input.readInt()) { input.readFloat() })
                    }
                }
            }
        }.orEmpty()
    } catch (_: IOException) {
        emptyMap()
    }

    private fun write(all: Map<String, FloatArray>) {
        val file = store ?: return
        try {
            val partial = File(file.parentFile, file.name + ".partial")
            DataOutputStream(partial.outputStream().buffered()).use { output ->
                output.writeUTF(signature)
                output.writeInt(all.size)
                for ((text, vector) in all) {
                    output.writeUTF(text)
                    output.writeInt(vector.size)
                    vector.forEach(output::writeFloat)
                }
            }
            if (!partial.renameTo(file)) partial.delete()
        } catch (_: IOException) {
            Log.w("AlphyLibrary", "vectors not saved")
        }
    }

    companion object {
        /** The weight of a word match against the cosine: a name or a word of the request lifts its entry above the entries that only resemble it. */
        private const val MATCH_WEIGHT = 0.15f
        @Volatile private var instance: LibrarySearch? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The search of the catalog asset with the embedding model of the app, made once. */
        fun get(context: Context): LibrarySearch = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val runtime = SemanticRuntime.get(app)
                val model = object : LibraryVectors {
                    override suspend fun query(text: String) = runtime.queries(text)
                    override suspend fun documents(texts: List<String>) = runtime.documents(texts)
                }
                LibrarySearch(LibraryEntry.parse(app.assets.open(CATALOG_ASSET).use { it.readBytes().decodeToString() }), model,
                    File(app.noBackupFilesDir, "library-vectors.bin"))
            }.also { instance = it }
        }

        /** Starts the load of the model and the vectors of the catalog (S5). */
        fun warm(context: Context) { scope.launch { get(context).warm() } }

        /** Starts the search of [request] and its word match: the caller waits for them with [within]. The catalog is read off the caller's thread. */
        fun start(context: Context, request: String): LibraryQuery {
            val search = scope.async { get(context) }
            return LibraryQuery(scope.async { search.await().found(request) }, scope.async { LibraryFound(search.await().words(request), LibraryFound.WORDS) })
        }

        /**
         * The candidates of [query] when its search is ready within [wait] ms, else its word match (K1): the embedding model loads for seconds
         * after the start of the voice mode, and a template call never waits longer than [wait] for its search (S4).
         */
        suspend fun within(query: LibraryQuery?, wait: Long = LIBRARY_WAIT_MS): LibraryFound {
            suspend fun ready(found: Deferred<LibraryFound>) = withTimeoutOrNull(wait) {
                try { found.await() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            }
            return query?.let { ready(it.search) ?: ready(it.words) } ?: LibraryFound.EMPTY
        }
    }
}

/** The word stems of a text: each word of 3 or more letters, cut to 5 letters, so a Russian word matches in each case form. */
internal fun stems(text: String): Set<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.map { it.take(5) }.toSet()

/** The word match of an entry: 1 for each name with all its stems in the request, 0.5 for each such other word. */
private fun matchScore(request: Set<String>, entry: LibraryEntry): Float {
    fun hit(phrase: String) = stems(phrase).let { it.isNotEmpty() && request.containsAll(it) }
    return entry.names.count(::hit) + 0.5f * entry.words.count(::hit)
}

private fun cosine(left: FloatArray, right: FloatArray): Float {
    if (left.size != right.size) return 0f
    var dot = 0.0
    var a = 0.0
    var b = 0.0
    for (index in left.indices) {
        dot += left[index] * right[index]
        a += left[index] * left[index]
        b += right[index] * right[index]
    }
    return if (a == 0.0 || b == 0.0) 0f else (dot / sqrt(a * b)).toFloat()
}
