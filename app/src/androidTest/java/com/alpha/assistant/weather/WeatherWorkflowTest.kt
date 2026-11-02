package com.alpha.assistant.weather

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Failure cases: city names trigger location, temporal words become cities, a late result
 * submits after cancellation, a draft edit or conversation switch, or a place survives retry. */
class WeatherWorkflowTest {
    @Test fun onlyRecognizedWeatherWithoutNamedPlaceCanRequestLocation() {
        listOf("какая сегодня погода", "погода завтра", "weather here", "What's the weather today?",
            "What is the weather like near me?", "погода в моем городе").forEach { assertTrue(it, WeatherRequest.canLocate(it)) }
        listOf("weather in London", "какая сегодня погода в Москве", "погода завтра в Казани",
            "weather in Reading", "Create a weather app", "Search my calendar", "weather SECRET_CITY",
            "weather in Today").forEach { assertFalse(it, WeatherRequest.canLocate(it)) }
    }

    @Test fun genericConvertersAndForecastsDoNotBecomeCurrentWeather() {
        listOf("Create a temperature converter", "Forecast sales revenue", "Explain temperature scales",
            "Explain the difference between weather and climate.", "What is the difference between weather and climate?",
            "Translate weather today into Russian", "Summarize this weather report", "Переведи текст о погоде",
            "Кратко изложи прогноз погоды", "How does weather affect climate?", "What is weather?").forEach {
            assertFalse(it, WeatherRequest.isWeather(it))
        }
        listOf("weather in London", "какая погода в Москве", "temperature in London", "forecast tomorrow").forEach {
            assertTrue(it, WeatherRequest.isWeather(it))
        }
    }

    @Test fun cancellationDraftSwitchAndRetryCannotReuseLocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val name = "weather-${UUID.randomUUID()}"
        val store = ShellStore(app, "$name.db")
        val keys = SecureKeyStore(app, name, name)
        check(keys.save("alpha-test-only").isSuccess)
        lateinit var model: ShellViewModel
        var pending = CompletableDeferred<WeatherPlace>()
        var entered = 0
        var continued = 0
        CloudTestActivity.factory = { application ->
            ShellViewModel(application, object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    request.weatherLocation!!.invoke()
                    continued++
                    emit(CloudEvent.RoundComplete(ChatToolReply.encode(ChatToolReply.Decision("answer", "Weather fixture", "", null, "none")), false))
                }
            }, store, keys, weatherLocator = object : WeatherLocator {
                override fun hasPermission() = true
                override suspend fun locate(): WeatherPlace {
                    entered++
                    val result = pending
                    return withContext(NonCancellable) { result.await() }
                }
            }).also { model = it }
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitUntil(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while(SystemClock.uptimeMillis() < deadline) { if(condition()) return; SystemClock.sleep(30) }
            fail("Weather workflow timed out")
        }
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitUntil { !model.loading }
                scenario.onActivity { activity -> model.weatherVisibilityChanged(false, activity); model.editDraft(Draft("weather here")); model.send() }
                waitUntil { model.activeConversationId == null }
                assertEquals(0, entered)
                assertEquals("weather_location", model.snapshot.selected!!.messages.last().errorCode)
                val nextSurface = Any()
                scenario.onActivity { activity ->
                    model.weatherVisibilityChanged(true, activity)
                    model.weatherVisibilityChanged(true, nextSurface)
                    model.editDraft(Draft("weather here")); model.send()
                }
                waitUntil { entered == 1 }
                scenario.onActivity { activity ->
                    model.weatherVisibilityChanged(false, activity)
                    model.weatherHidden(activity)
                }
                pending.complete(WeatherPlace("London", System.currentTimeMillis()))
                waitUntil { model.activeConversationId == null }
                assertEquals("complete", model.snapshot.selected!!.messages.last().status)
                assertEquals(1, continued)
                scenario.onActivity { activity ->
                    model.weatherVisibilityChanged(true, activity)
                    model.weatherVisibilityChanged(false, nextSurface)
                    model.weatherHidden(nextSurface)
                }
                for(action in listOf("cancel", "draft", "switch")) {
                    pending = CompletableDeferred()
                    val expected = entered + 1
                    main { model.select(null); model.editDraft(Draft("weather here")); model.send() }
                    waitUntil { entered == expected }
                    val original = model.snapshot.selectedId
                    main { when(action) {
                        "cancel" -> model.cancelRequest()
                        "draft" -> model.editDraft(Draft("Keep my new draft"))
                        else -> model.select(null)
                    } }
                    pending.complete(WeatherPlace("London", System.currentTimeMillis()))
                    waitUntil { model.activeConversationId == null }
                    SystemClock.sleep(100)
                    assertEquals(1, continued)
                    assertEquals("cancelled", model.snapshot.conversations.single { it.id == original }.messages.last().status)
                    if(action == "draft") assertEquals("Keep my new draft", model.snapshot.draft.text)
                    if(action == "switch") assertNull(model.snapshot.selectedId)
                }
                val canceled = model.snapshot.conversations.last()
                pending = CompletableDeferred()
                val expected = entered + 1
                main { model.select(canceled.id); model.retry(canceled.messages.last().id) }
                waitUntil { entered == expected }
                assertEquals(1, continued)
                pending.complete(WeatherPlace("Paris", System.currentTimeMillis()))
                waitUntil { model.activeConversationId == null }
                assertEquals(2, continued)
                assertEquals("complete", model.snapshot.selected!!.messages.last().status)
                java.io.File(app.getExternalFilesDir(null), "weather-workflow.txt").writeText(
                    "Revision: ${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}\n" +
                    "Command: connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.alpha.assistant.weather.WeatherWorkflowTest\n" +
                    "Inputs: isolated chat store, controlled approximate places, and delayed request-owned results.\n" +
                    "Expected and observed: shared surface transition retains foreground; background access blocked; cancel, draft edit, and conversation switch reject late places; retry acquires a new place.\n")
            }
        } finally {
            pending.complete(WeatherPlace("London", System.currentTimeMillis()))
            CloudTestActivity.factory = null
            store.close(); app.deleteDatabase("$name.db"); app.deleteDatabase("$name.db-tools.db"); keys.clear()
        }
    }
}
