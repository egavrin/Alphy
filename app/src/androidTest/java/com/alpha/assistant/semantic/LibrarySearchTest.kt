package com.alpha.assistant.semantic

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.ui.VisibleSemanticRuntime
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The search of the ready objects of the library (stage 6, S1 to S5, and the gate of M2). */
@RunWith(AndroidJUnit4::class)
class LibrarySearchTest {
    @get:Rule val semantic = VisibleSemanticRuntime()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private fun text(name: String) = assets.open("library/$name").use { it.readBytes().decodeToString() }
    private val catalog by lazy { LibraryEntry.parse(context.assets.open("library/catalog.json").use { it.readBytes().decodeToString() }) }
    /** The entries of the spec list (test fixture), with the ready objects of the catalog in place of their fixture entries. */
    private val all by lazy { catalog + LibraryEntry.parse(text("names.json")).filter { entry -> catalog.none { it.id == entry.id } } }
    private fun store() = File(context.cacheDir, "library-test-${System.nanoTime()}.bin").also { it.deleteOnExit() }

    /** A fake model: each text gets a vector of its letters, and [wait] ms before each answer. */
    private class Letters(val wait: Long = 0, val fail: Boolean = false) : LibraryVectors {
        var documents = 0
        private fun vector(text: String) = FloatArray(32) { index -> text.lowercase().count { it.code % 32 == index }.toFloat() }
        override suspend fun query(text: String): List<FloatArray> { delay(wait); if (fail) throw SemanticUnavailable(); return listOf(vector(text)) }
        override suspend fun documents(texts: List<String>): List<FloatArray> { if (fail) throw SemanticUnavailable(); documents += texts.size; return texts.map(::vector) }
    }

    /**
     * Gate of M2: for at least 90 of the 100 held-out requests, a fitting entry is among the 24 candidates. Also measures the load of the model
     * and the vectors of the catalog, and the search time of each request, on this device.
     */
    @Test fun aFittingEntryIsAmongTheCandidatesOfNinetyRequests() = runBlocking {
        val search = LibrarySearch(all, appModel(), store())
        val load = SystemClock.elapsedRealtime()
        search.warm()
        val loaded = SystemClock.elapsedRealtime() - load
        val requests = JSONObject(text("search.json")).getJSONArray("requests")
        val times = mutableListOf<Long>()
        val misses = mutableListOf<String>()
        for (index in 0 until requests.length()) {
            val request = requests.getJSONObject(index)
            val fits = request.getJSONArray("fits").let { list -> List(list.length()) { list.getString(it) } }
            val start = SystemClock.elapsedRealtime()
            val found = search.candidates(request.getString("text")).map { it.id }
            times += SystemClock.elapsedRealtime() - start
            if (found.none { it in fits }) misses += "${request.getString("id")}:${fits.joinToString("+")}:first=${found.take(3).joinToString("+")}"
        }
        val sorted = times.sorted()
        Log.i("AlphyLibraryGate", "entries=${all.size} requests=${requests.length()} found=${requests.length() - misses.size} load_ms=$loaded " +
            "search_median_ms=${sorted[sorted.size / 2]} search_max_ms=${sorted.last()} over_wait=${times.count { it > LIBRARY_WAIT_MS }} made=${search.made}")
        misses.forEach { Log.i("AlphyLibraryGate", "miss $it") }
        assertTrue("found ${requests.length() - misses.size} of ${requests.length()}: $misses", requests.length() - misses.size >= 90)
    }

    @Test fun aNameInRussianOrEnglishFindsItsEntryFirst() = runBlocking {
        val search = LibrarySearch(all, appModel(), store())
        for ((request, id) in listOf("Покажи сердце и его камеры" to "body.heart", "How is the heart built?" to "body.heart",
            "Расскажи про кольца Сатурна" to "space.saturn", "Name the sides of a right triangle" to "math.right_triangle")) {
            assertEquals(request, id, search.candidates(request).first().id)
        }
    }

    @Test fun aSlowSearchDoesNotDelayTheTemplateCall() = runBlocking {
        val search = LibrarySearch(catalog, Letters(wait = 300), store())
        val start = SystemClock.elapsedRealtime()
        val found = LibrarySearch.within(search.query("сердце", this))
        val waited = SystemClock.elapsedRealtime() - start
        assertTrue("waited $waited ms", waited < LIBRARY_WAIT_MS + 100)
        // A late search gives the word match at once (fix list 4, K1).
        assertEquals(listOf("body.heart") to "1 words", found.entries.map { it.id } to found.mark)
        // A ready search gives its candidates, and the host line names each one with its parts.
        val ready = LibrarySearch.within(LibrarySearch(catalog, Letters(), store()).query("сердце", this))
        assertEquals("body.heart" to LibraryFound.VECTORS, ready.entries.first().id to ready.source)
        assertEquals("${LIBRARY_CANDIDATES} vectors", ready.mark)
        val line = AppFilePrompt.load(context).candidatesText(ready.entries)
        assertTrue(line, line.contains("- heart: ") && line.contains("Parts: vena_cava, ") && line.lines().size == ready.entries.size + 1)
        assertEquals("", AppFilePrompt.load(context).candidatesText(emptyList()))
        assertEquals("0 none", LibrarySearch.within(null).mark)
    }

    /** K1: a model that has not loaded never answers, and a request that names an object gets it from the word match inside the wait. */
    @Test fun aModelThatNeverAnswersGivesTheWordMatchInsideTheWait() = runBlocking {
        val loading = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val search = LibrarySearch(catalog, Never, store())
            for ((request, id) in listOf("Объясни, как работает сердце" to "body.heart", "How does the heart work?" to "body.heart",
                "А почему у сердца четыре камеры" to "body.heart", "Расскажи про кольца Сатурна" to "space.saturn")) {
                val start = SystemClock.elapsedRealtime()
                val found = LibrarySearch.within(search.query(request, loading))
                val waited = SystemClock.elapsedRealtime() - start
                assertTrue("$request waited $waited ms", waited < LIBRARY_WAIT_MS + 100)
                assertTrue("$request: ${found.entries.map { it.id }}", id in found.entries.map { it.id })
                assertEquals(request, LibraryFound.WORDS, found.source)
            }
        } finally { loading.cancel() }
    }

    /** K1: a model that loads late gives the word match to the first call, and the ranked list to a later request. */
    @Test fun aLateModelGivesTheWordMatchFirstAndTheRankedListLater() = runBlocking {
        val loaded = SystemClock.elapsedRealtime() + 600
        val late = object : LibraryVectors {
            val letters = Letters()
            override suspend fun query(text: String): List<FloatArray> { delay(maxOf(0, loaded - SystemClock.elapsedRealtime())); return letters.query(text) }
            override suspend fun documents(texts: List<String>) = letters.documents(texts)
        }
        val search = LibrarySearch(catalog, late, store())
        val first = LibrarySearch.within(search.query("Объясни, как работает сердце", this))
        assertEquals(LibraryFound.WORDS to "body.heart", first.source to first.entries.first().id)
        delay(maxOf(0, loaded - SystemClock.elapsedRealtime()) + 100)
        val later = LibrarySearch.within(search.query("Покажи, как кровь течёт по сердцу", this))
        assertEquals(LibraryFound.VECTORS to LIBRARY_CANDIDATES, later.source to later.entries.size)
    }

    /** A model that has not loaded: each query waits until the test ends. */
    private object Never : LibraryVectors {
        override suspend fun query(text: String): List<FloatArray> = awaitCancellation()
        override suspend fun documents(texts: List<String>): List<FloatArray> = awaitCancellation()
    }

    @Test fun noModelGivesTheWordMatchOnly() = runBlocking {
        val found = LibrarySearch(all, Letters(fail = true), store()).candidates("Нарисуй сердце")
        assertEquals(listOf("body.heart"), found.map { it.id }.filter { it in catalog.map(LibraryEntry::id) })
        assertTrue(found.map { it.id }.toString(), found.isNotEmpty() && found.size < all.size)
        assertEquals(emptyList<LibraryEntry>(), LibrarySearch(all, null, null).candidates("Спой мне колыбельную песню"))
    }

    @Test fun aNewSignatureIgnoresTheOldVectors() = runBlocking {
        val file = store()
        val first = Letters()
        LibrarySearch(catalog, first, file, signature = "model-a").warm()
        assertEquals(catalog.size, first.documents)
        val again = Letters()
        LibrarySearch(catalog, again, file, signature = "model-a").warm()
        assertEquals("the same signature keeps the vectors", 0, again.documents)
        val other = Letters()
        LibrarySearch(catalog, other, file, signature = "model-b").warm()
        assertEquals("a new signature makes new vectors", catalog.size, other.documents)
    }

    private fun appModel(): LibraryVectors {
        val runtime = SemanticRuntime.get(context)
        return object : LibraryVectors {
            override suspend fun query(text: String) = runtime.queries(text)
            override suspend fun documents(texts: List<String>) = runtime.documents(texts)
        }
    }
}
