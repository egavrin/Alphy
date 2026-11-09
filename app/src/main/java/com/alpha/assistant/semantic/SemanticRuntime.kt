package com.alpha.assistant.semantic

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MODEL_SHA = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
private const val DIMENSION = 768
internal const val VECTOR_SIGNATURE = "embeddinggemma2:$MODEL_SHA:litertlm-0.18.0:retrieval-utf8-216:max256:dim$DIMENSION"
internal const val MODEL_BYTES = 164_626_432L
private const val ASSET = "semantic/embeddinggemma-2-text-270m.litertlm"

// The prefixes and special tokens fit in the space left by the UTF-8 byte budget.
private const val CHUNK_BYTES = 216

internal data class TextChunk(val start: Int, val end: Int)

internal fun textChunks(text: String): List<TextChunk> = buildList {
    var start = 0
    while (start < text.length) {
        var end = start
        var bytes = 0
        var lastBreak = start
        while (end < text.length) {
            val point = text.codePointAt(end)
            val size = when {
                point < 0x80 -> 1
                point < 0x800 -> 2
                point < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > CHUNK_BYTES) break
            bytes += size
            end += Character.charCount(point)
            if (Character.isWhitespace(point) && bytes >= CHUNK_BYTES / 2) lastBreak = end
        }
        if (end < text.length && lastBreak > start) end = lastBreak
        if (text.substring(start, end).isNotBlank()) add(TextChunk(start, end))
        start = end
    }
}

internal class SemanticUnavailable(cause: Throwable? = null) : Exception("Semantic retrieval is unavailable", cause)

internal data class Ranked<T>(val item: T, val score: Float)

internal class SemanticRuntime private constructor(private val context: Context) {
    private val lock = Mutex()
    private val epoch = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val model = File(context.noBackupFilesDir, "semantic-${MODEL_SHA.take(12)}.litertlm")
    private var prepared = false
    private var engine: EmbeddingEngine? = null
    private var engineEpoch = -1L
    private val catalog = mutableMapOf<String, FloatArray>()
    private val preparation = MutableStateFlow<Long?>(null)
    val preparationBytes = preparation.asStateFlow()

    suspend fun queries(text: String): List<FloatArray> =
        embed(textChunks(text).map { "task: search query | text: ${text.substring(it.start, it.end)}" })

    suspend fun documents(texts: List<String>): List<FloatArray> =
        embed(texts.map { "task: search result | text: $it" })

    suspend fun <T> rank(query: String, items: List<T>, text: (T) -> String, cache: Boolean = false): List<Ranked<T>> {
        if (items.isEmpty() || query.isBlank()) return emptyList()
        val ticket = ticket()
        val queries = queries(query)
        val chunks = items.map { item ->
            val value = text(item)
            textChunks(value).map { "task: search result | text: ${value.substring(it.start, it.end)}" }
        }
        val inputs = chunks.flatten().distinct()
        val vectors = inputs.zip(embed(inputs, cache)).toMap()
        val ranked = items.zip(chunks).mapNotNull { (item, texts) ->
            val score = texts.maxOfOrNull { input -> queries.maxOf { cosine(it, vectors.getValue(input)) } }
            score?.let { Ranked(item, it) }
        }.sortedByDescending { it.score }
        checkActive(ticket)
        return ranked
    }

    private suspend fun embed(inputs: List<String>, cache: Boolean = false): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        val generation = epoch.get()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    inputs.map { input ->
                        checkActive(generation)
                        val result = (if (cache) catalog[input] else null) ?: open(generation)
                            .computeEmbedding(listOf(InputData.Text(input))).embedding.also { vector ->
                                check(vector.size == DIMENSION && vector.all(Float::isFinite) && vector.any { it != 0f })
                                if (cache) catalog[input] = vector
                            }
                        checkActive(generation)
                        result
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: LinkageError) {
                    throw SemanticUnavailable(error)
                } catch (error: Exception) {
                    throw SemanticUnavailable(error)
                }
            }
        }
    }

    fun ticket(): Long = epoch.get()

    suspend fun checkActive(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (epoch.get() != generation || stopped) throw SemanticUnavailable()
    }

    private suspend fun open(generation: Long): EmbeddingEngine {
        if (engineEpoch == generation) engine?.let { return it }
        closeEngine()
        if (!prepared) {
            preparation.value = 0
            try {
                if (!model.isFile || model.length() != MODEL_BYTES ||
                    !model.inputStream().use { verify(it, null, generation) }) {
                    val partial = File(model.parentFile, model.name + ".partial")
                    try {
                        context.assets.open(ASSET).use { input ->
                            partial.outputStream().use { output -> check(verify(input, output, generation)) }
                        }
                        checkActive(generation)
                        Files.move(partial.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        partial.delete()
                    }
                }
                prepared = true
            } finally {
                preparation.value = null
            }
        }
        checkActive(generation)
        val created = EmbeddingEngine(EmbeddingEngineConfig(
            modelPath = model.absolutePath,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath,
            maxInputLength = 256,
        ))
        try {
            created.initialize()
            engine = created
            engineEpoch = generation
        } finally {
            if (engine !== created) created.close()
        }
        checkActive(generation)
        return created
    }

    private suspend fun verify(input: java.io.InputStream, output: java.io.OutputStream?, generation: Long): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            checkActive(generation)
            val count = input.read(buffer)
            if (count == -1) break
            size += count
            if (size > MODEL_BYTES) return false
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
            preparation.value = size
        }
        return size == MODEL_BYTES && digest.digest().toHexString() == MODEL_SHA
    }

    private fun release() {
        val next = epoch.incrementAndGet()
        scope.launch {
            lock.withLock {
                if (engineEpoch < next) closeEngine()
            }
        }
    }

    private fun closeEngine() {
        val closing = engine
        engine = null
        runCatching { closing?.close() }
    }

    companion object {
        @Volatile private var instance: SemanticRuntime? = null
        private var visibleActivities = 0
        @Volatile private var stopped = false

        fun get(context: Context): SemanticRuntime = instance ?: synchronized(this) {
            instance ?: SemanticRuntime(context.applicationContext).also { instance = it }
        }

        fun activityStarted() {
            visibleActivities++
            stopped = false
        }

        fun activityStopped() {
            if (--visibleActivities == 0) {
                stopped = true
                instance?.release()
            }
        }
        fun trim() { instance?.release() }
    }
}

private fun cosine(left: FloatArray, right: FloatArray): Float {
    check(left.size == right.size)
    var dot = 0.0
    var leftSquared = 0.0
    var rightSquared = 0.0
    for (index in left.indices) {
        val a = left[index].toDouble()
        val b = right[index].toDouble()
        dot += a * b
        leftSquared += a * a
        rightSquared += b * b
    }
    return (dot / sqrt(leftSquared * rightSquared)).toFloat()
}
