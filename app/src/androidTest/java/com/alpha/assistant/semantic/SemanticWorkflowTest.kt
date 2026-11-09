package com.alpha.assistant.semantic

import android.app.appsearch.AppSearchManager
import android.app.appsearch.AppSearchResult
import android.app.appsearch.AppSearchSession
import android.app.appsearch.GetSchemaResponse
import android.app.appsearch.StorageInfo
import android.os.SystemClock
import android.util.Log
import com.alpha.assistant.agent.AgentCommand
import org.junit.Assume.assumeTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.ui.HistorySearch
import java.util.UUID
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledRuntimeRanksSupportedRequestsAndAbstainsOnUnrelatedRequests() = runBlocking {
        val runtime = SemanticRuntime.get(context)
        val scenarios = listOf(
            "Turn on the phone flashlight" to "flashlight.set",
            "Сделай снимок на камеру" to "camera.capture",
            "Busca la plaza central en el mapa" to "maps.open",
            "Открой форму создания события в календаре" to "calendar.create",
            "Write a sonnet about autumn" to null,
            "Explain how photosynthesis works" to null,
        )
        for ((query, expected) in scenarios) {
            val ranked = runtime.rank(query, CapabilityCatalog.definitions,
                { "${it.title} (${it.id}). ${it.explanation}" }, cache = true)
            val candidates = ranked.filter { it.score >= 0.75f }.take(5)
            Log.i("AlphySemanticTest", "scenario=$query expected=$expected candidates=${candidates.map { it.item.id to it.score }}")
            if (expected == null) assertTrue(query, candidates.isEmpty())
            else assertTrue(query, candidates.any { it.item.id == expected })
        }
    }

    @Test fun privateVectorIndexReopensAndConvergesAfterEditAndDelete() = runBlocking {
        val name = "workflow:${UUID.randomUUID()}"
        var index = SemanticIndex(context, name)
        val facts = linkedMapOf(
            "pet" to SemanticRecord("pet", "1", "I own a black Labrador dog named Pepper."),
            "credential" to SemanticRecord("credential", "1", "My account security recovery code is 389445."),
        )
        suspend fun search() = index.search("Which breed is my pet dog?", 0.65f, 5,
            { facts.values.toList() }, { id, revision -> facts[id]?.revision == revision })
        val start = SystemClock.elapsedRealtime()
        assertEquals("pet", search().first().id)
        Log.i("AlphySemanticTest", "indexColdMillis=${SystemClock.elapsedRealtime() - start}")
        index = SemanticIndex(context, name)
        assertEquals("pet", search().first().id)
        facts["pet"] = SemanticRecord("pet", "2", "My new pet dog is a dachshund named Hugo.")
        assertEquals("2", search().first().revision)
        facts.remove("pet")
        assertFalse(search().any { it.id == "pet" })
    }
    @Test fun developmentScoresForAgentAndMemory() = runBlocking {
        val runtime = SemanticRuntime.get(context)
        val steps = listOf(
            "Change my memory of my favorite color to green",
            "Create a calendar event for a single visit",
            "Напомни завтра в восемь позвонить врачу",
            "Apaga la linterna",
            "Build me an inventory app",
            "Take a photo using my camera",
            "Delete all my personal calendar appointments",
        )
        for (step in steps) {
            val top = runtime.rank(step, com.alpha.assistant.capabilities.CapabilityCatalog.phoneGrants, com.alpha.assistant.capabilities.CapabilityCatalog::phoneDescription, cache = true).take(5)
            Log.i("AlphySemanticTest", "agent query=$step scores=$top")
        }
        val facts = listOf(
            "My favorite color is blue",
            "My passport number is 40482819",
            "Люблю хвойные деревья, особенно сосны и ели.",
            "Мой код от банковского сейфа 0342.",
            "Mis gatos se llaman Luna y Pepa.",
            "Mi cuenta de correo es uno@example.test",
        )
        for (query in listOf("What is my favorite color?", "Какие деревья мне нравятся?", "¿Cómo se llaman mis gatos?")) {
            val top = runtime.rank(query, facts, { it })
            Log.i("AlphySemanticTest", "memory query=$query scores=$top")
        }
    }

    @Test fun heldOutMultilingualToolSelection() = runBlocking {
        val supported = listOf(
            "Switch the flashlight on" to "flashlight.set",
            "Погаси фонарик на телефоне" to "flashlight.set",
            "Enciende la linterna de mi móvil" to "flashlight.set",
            "Haz vibrar el móvil por medio segundo" to "vibration.play",
            "Поставь будильник на шесть утра" to "clock.setAlarm",
            "Pon una alarma a las seis y media" to "clock.setAlarm",
            "Start a countdown for ten minutes" to "clock.setTimer",
            "Запусти таймер на две минуты" to "clock.setTimer",
            "Traza una ruta desde Atocha hasta Sol" to "maps.route",
            "Проложи маршрут от дома до вокзала" to "maps.route",
            "Show Plaza Mayor on the map" to "maps.open",
            "Read the appointments on my calendar for Friday" to "calendar.read",
            "Покажи записи в календаре на эту неделю" to "calendar.read",
            "Move my dentist appointment to four" to "calendar.updateEvent",
            "Crea una cita única para ir al dentista" to "calendar.createOwned",
            "Schedule a recurring calendar event every day at noon" to "calendar.createOwned.daily",
            "Read my saved preference about tea" to "memory.read",
            "Обнови мою запись в памяти о любимом цвете" to "memory.write",
            "Pause my morning digest automation" to "automation.pause",
            "Activa de nuevo la automatización del resumen semanal" to "automation.resume",
            "Включи flashlight на моём телефоне" to "flashlight.set",
            "Start un temporizador de cinco minutos" to "clock.setTimer",
        )
        val unsupported = listOf(
            "Draft a poem about the ocean",
            "Explícame cómo se formó la Luna",
            "Переведи эту фразу на немецкий",
            "Toma una foto con la cámara del teléfono",
            "Borra todas las citas personales que ya tenía en mi calendario",
            "Summarize my unread text messages",
        )
        checkToolHoldout("heldOut", supported, unsupported)
    }

    @Test fun newHoldoutAfterCatalogCalibration() = runBlocking {
        val cases = listOf(
            "Enable the torch" to "flashlight.set",
            "Apaga la luz de la linterna" to "flashlight.set",
            "Отключи фонарик" to "flashlight.set",
            "Make my phone buzz briefly" to "vibration.play",
            "Set an alarm for a quarter past five" to "clock.setAlarm",
            "Despiértame a las siete con una alarma" to "clock.setAlarm",
            "Поставь таймер отсчёта на 90 секунд" to "clock.setTimer",
            "Inicia un temporizador de treinta segundos" to "clock.setTimer",
            "Open directions from my hotel to the airport" to "maps.route",
            "Cómo llego desde Valencia hasta Alicante" to "maps.route",
            "Покажи на карте Третьяковскую галерею" to "maps.open",
            "What meetings are on my calendar tomorrow?" to "calendar.read",
            "Muestra mis eventos del calendario de esta tarde" to "calendar.read",
            "Перенеси встречу с бухгалтером на час позже" to "calendar.updateEvent",
            "Add a one-off appointment for my haircut" to "calendar.createOwned",
            "Добавь ежедневное событие в календарь" to "calendar.createOwned.daily",
            "Какой сорт кофе я сохранял в памяти?" to "memory.read",
            "Replace my stored home address with the new address" to "memory.write",
            "Suspend the automation that posts my weather summary" to "automation.pause",
            "Включи обратно сценарий вечерней рассылки" to "automation.resume",
            "Turn off la linterna ahora" to "flashlight.set",
            "Поставь alarm на восемь" to "clock.setAlarm",
        )
        val negatives = listOf("Describe a fictional dragon", "Escribe una canción de cuna",
            "Сфотографируй этот стол", "Remove all of my existing calendar appointments")
        checkToolHoldout("newHoldout", cases, negatives)
    }

    @Test fun finalToolHoldout() = runBlocking {
        val labeled = listOf(
            "Could you light up the phone's torch?" to "flashlight.set",
            "Потуши фонарик" to "flashlight.set",
            "Activa la linterna del teléfono" to "flashlight.set",
            "Haz que el teléfono vibre un segundo" to "vibration.play",
            "Pon el despertador para las nueve" to "clock.setAlarm",
            "Сделай будильник на 5:30" to "clock.setAlarm",
            "Set a kitchen timer for 40 seconds" to "clock.setTimer",
            "Empieza una cuenta atrás de un minuto" to "clock.setTimer",
            "Cómo voy de la estación al museo" to "maps.route",
            "Построй маршрут от вокзала до аэропорта" to "maps.route",
            "Find the library on a map" to "maps.open",
            "Dime qué reuniones tengo en el calendario el lunes" to "calendar.read",
            "List the events already on my calendar for today" to "calendar.read",
            "Перенеси мою встречу в календаре на завтра" to "calendar.updateEvent",
            "Add a single calendar entry for lunch with Alex" to "calendar.createOwned",
            "Crea un evento en el calendario que se repita cada día" to "calendar.createOwned.daily",
            "Read the saved fact about my usual train" to "memory.read",
            "Обнови сохранённый в памяти домашний адрес" to "memory.write",
            "Detén la automatización del boletín matutino" to "automation.pause",
            "Re-enable the paused evening reminder automation" to "automation.resume",
            "Включи phone torch" to "flashlight.set",
            "Start a таймер for 30 seconds" to "clock.setTimer",
        )
        val negatives = listOf("Translate this sentence to Portuguese", "Cuéntame un cuento para dormir",
            "Сделай фотографию через заднюю камеру", "Delete all existing events from my personal calendar")
        checkToolHoldout("finalHoldout", labeled, negatives)
    }

    private suspend fun checkToolHoldout(label: String, supported: List<Pair<String, String>>, unsupported: List<String>) {
        val runtime = SemanticRuntime.get(context)
        val missed = supported.filter { (query, expected) ->
            val candidates = AgentCommand.shortlist(query, runtime)
            report("$label query=$query expected=$expected candidates=$candidates")
            expected !in candidates
        }
        val falsePositives = unsupported.filter { query ->
            val candidates = AgentCommand.shortlist(query, runtime)
            report("$label query=$query expected=none candidates=$candidates")
            candidates.isNotEmpty()
        }
        assertTrue("missed=${missed.size}/${supported.size} $missed; unsupported=$falsePositives",
            missed.size * 100 <= supported.size * 5 && falsePositives.isEmpty())
    }

    @Test fun releasingActiveInferenceSuppressesItsResultAndAllowsImmediateReopening() = runBlocking {
        val runtime = SemanticRuntime.get(context)
        assertEquals(768, runtime.queries("warmup").single().size)
        val obsolete = async {
            runCatching { runtime.queries("Write a journal entry about a walk through the city. ".repeat(80)) }
        }
        delay(90)
        SemanticRuntime.trim()
        val replacement = async { runtime.queries("Find my saved notes on the garden") }
        assertTrue(obsolete.await().exceptionOrNull() is SemanticUnavailable)
        assertEquals(768, replacement.await().single().size)
    }

    @Test fun coldBundledModelPreparation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("semanticCold") == "true")
        val file = java.io.File(context.noBackupFilesDir, "semantic-${MODEL_SHA.take(12)}.litertlm")
        assertFalse("Run with a clean QA install", file.exists())
        val runtime = SemanticRuntime.get(context)
        val start = SystemClock.elapsedRealtime()
        var copyingAt = 0L
        var preparedAt = 0L
        val progress = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runtime.preparationBytes.collect { bytes ->
                if (bytes != null && copyingAt == 0L) copyingAt = SystemClock.elapsedRealtime()
                if (bytes == null && copyingAt != 0L) preparedAt = SystemClock.elapsedRealtime()
            }
        }
        try {
            assertEquals(768, runtime.queries("What breed is my dog?").single().size)
            val total = SystemClock.elapsedRealtime() - start
            assertEquals(MODEL_BYTES, file.length())
            report("firstUseMillis=$total preparationMillis=${preparedAt - copyingAt} privateModelBytes=${file.length()}")
        } finally { progress.cancel() }
    }

    private fun report(value: String) {
        Log.i("AlphySemanticTest", value)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "$value\n") })
    }

    private suspend fun inspectIndex(name: String, expectedCount: Int): Long {
        val manager = checkNotNull(context.getSystemService(AppSearchManager::class.java))
        val executor = Executor { it.run() }
        val db = awaitIndex<AppSearchSession> {
            manager.createSearchSession(AppSearchManager.SearchContext.Builder("semantic-" + semanticRevision(name))
                .build(), executor, it)
        }
        db.use { session ->
            val schema = awaitIndex<GetSchemaResponse> { session.getSchema(executor, it) }
            assertEquals(setOf("SemanticChunk"), schema.schemaTypesNotDisplayedBySystem)
            assertTrue(schema.schemaTypesVisibleToPackages.isEmpty())
            assertTrue(schema.publiclyVisibleSchemas.isEmpty())
            val storage = awaitIndex<StorageInfo> { session.getStorageInfo(executor, it) }
            assertEquals(expectedCount, storage.aliveDocumentsCount)
            return storage.sizeBytes
        }
    }

    private suspend fun <T> awaitIndex(call: (Consumer<AppSearchResult<T>>) -> Unit): T = suspendCoroutine { continuation ->
        call(Consumer { result ->
            if (result.isSuccess) continuation.resume(checkNotNull(result.resultValue))
            else continuation.resumeWithException(IllegalStateException(result.errorMessage))
        })
    }

    @Test fun appSearchScale() {
        val mode = InstrumentationRegistry.getArguments().getString("semanticScale")
        assumeTrue(mode == "true")
        runBlocking {
            val corpus = SemanticIndex(context, "scale")
            val query = "What is the name of the dog I adopted?"
            var size = 0
            for (target in listOf(10_000, 50_000)) {
                val records = List(target) { position ->
                    SemanticRecord("sample-$position", "1",
                        if (position == 7) "I adopted a black Labrador dog named Pepper."
                        else listOf("The museum is open on Mondays.", "El autobús sale a las siete.",
                            "Купила красное пальто в Москве.", "The coral reef shelters thousands of fish.")[position % 4])
                }
                fun current(id: String, revision: String) = revision == "1" && id.substringAfterLast('-').toInt() < target
                val start = SystemClock.elapsedRealtime()
                corpus.update({ records }, ::current)
                val indexingMillis = SystemClock.elapsedRealtime() - start
                report("scale=$target indexMillis=$indexingMillis added=${target - size} indexBytes=${inspectIndex("scale", target)}")
                size = target
                assertEquals("sample-7", corpus.search(query, 0.78f, 5, { records }, ::current).first().id)
                val times = List(30) {
                    val startNanos = SystemClock.elapsedRealtimeNanos()
                    val hits = corpus.search(query, 0.78f, 5, { records }, ::current)
                    assertEquals("sample-7", hits.first().id)
                    (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
                }.sorted()
                val p95 = times[28]
                report("scale=$target p95Millis=$p95 samples=$times")
                if (target == 10_000) assertTrue("p95=$p95", p95 <= 250)
            }
        }
    }

    @Test fun heldOutInteractiveSearchFindsParaphrasedMessagesAndDrafts() = runBlocking {
        val name = "history-holdout-${UUID.randomUUID()}.db"
        val cases = listOf(
            "My Labrador is named Pepper." to "What is the name of my dog?",
            "The washing machine filter needs to be cleaned every month." to "How often should I service the washer filter?",
            "My bike is waiting for pickup at the repair shop." to "Collect the bicycle from the mechanic",
            "The dentist will see me at 3 pm next Tuesday." to "When is the appointment with my dentist?",
            "The spare house key is in the blue plant pot." to "Where did I put my backup key?",
            "We booked a flight to Montreal for the fifteenth." to "On what date do we fly to Montreal?",
            "The astronomy club meets at the university observatory." to "Where does the stargazing group gather?",
            "Марина попросила купить хлеб из ржаной муки." to "Какой хлеб нужен Марине?",
            "В воскресенье мы едем за город на лыжах кататься." to "Когда мы отправимся кататься на лыжах?",
            "Для рассады томатов я заказал новый грунт." to "Что я купил для помидоров?",
            "Кошку Луну нужно отвезти к ветеринару для прививки." to "Зачем везти Луну к ветеринару?",
            "Черновик доклада о солнечных батареях лежит в облаке." to "Где искать мой доклад о солнечной энергии?",
            "Список вещей для похода включает фонарь и спальник." to "Что взять в туристический поход?",
            "El autobús hacia Segovia sale de la estación a las siete." to "¿A qué hora pasa el autobús a Segovia?",
            "La clave del wifi para los invitados está en la recepción." to "¿Dónde consigo el acceso inalámbrico para huéspedes?",
            "La farmacia de guardia está en la plaza principal." to "¿Dónde hay una farmacia abierta?",
            "El martes tenemos ensayo de guitarra en el conservatorio." to "¿Cuándo practicamos guitarra?",
            "El paquete se quedó en una taquilla de Correos." to "¿Dónde puedo recoger el envío?",
            "The rain jacket is in the camping gear box." to "Где лежит my waterproof coat?",
            "La entrada al museo incluye la colección de fotografía." to "Does the museum ticket cover la exposición fotográfica?",
            "Счёт за электричество нужно оплатить до конца месяца." to "When is the electricity bill due?",
            "The recipe uses roasted pumpkin, ginger and coconut milk." to "¿Cómo era la receta de sopa de calabaza?",
        )
        try {
            ShellStore(context, name).use { store ->
                for ((index, pair) in cases.withIndex()) {
                    val id = "holdout-$index"
                    val isDraft = index == 2
                    val messages = if (isDraft) emptyList() else listOf(Message("$id-message", "user", pair.first, 1, "complete"))
                    val draft = if (isDraft) com.alpha.assistant.data.Draft(pair.first) else com.alpha.assistant.data.Draft()
                    store.saveConversation(Conversation(id, "Fixture $index", 1, 2, draft, messages))
                }
                val search = HistorySearch(context, store)
                val misses = cases.withIndex().filter { (index, pair) ->
                    val hits = search.search(pair.second).take(5)
                    val correct = hits.any { hit -> hit.conversationId == "holdout-$index" &&
                        if (index == 2) hit.draft else hit.messageId == "holdout-$index-message" }
                    report("interactiveHoldout index=$index found=$correct query=${pair.second}")
                    !correct
                }
                assertTrue("interactive misses=${misses.map { it.index }}", misses.size * 100 <= cases.size * 5)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun savedHistoryScale() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("semanticScale") == "true")
        val name = "history-scale-${UUID.randomUUID()}.db"
        try {
            ShellStore(context, name).use { store ->
                runBlocking {
                    val search = HistorySearch(context, store)
                    val topics = listOf("The museum is open on Mondays.", "El autobús sale a las siete.",
                        "Купила красное пальто в Москве.", "The coral reef shelters thousands of fish.")
                    var created = 0
                    for (target in listOf(10_000, 50_000)) {
                        for (position in created until target / 100) {
                            val id = "conversation-$position"
                            val messages = List(99) { index ->
                                val text = if (position == 1 && index == 13) "I adopted a black Labrador dog named Pepper."
                                    else topics[index % topics.size]
                                Message("$id-message-$index", "user", text, index.toLong(), "complete")
                            }
                            store.saveConversation(Conversation(id, "Fixture conversation $position", 1, 2, messages = messages))
                        }
                        created = target / 100
                        val query = "What is the name of the dog I adopted?"
                        val cold = SystemClock.elapsedRealtime()
                        assertEquals("conversation-1-message-13", search.search(query).first().messageId)
                        report("savedHistory=$target reconcileAndFirstQueryMillis=${SystemClock.elapsedRealtime() - cold}")
                        val warm = List(30) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            assertEquals("conversation-1-message-13", search.search(query).first().messageId)
                            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        }.sorted()
                        report("savedHistory=$target p95Millis=${warm[28]} samples=$warm")
                        if (target == 10_000) assertTrue("saved history p95=${warm[28]}", warm[28] <= 250)
                    }
                }
            }
        } finally { context.deleteDatabase(name) }
    }

}
