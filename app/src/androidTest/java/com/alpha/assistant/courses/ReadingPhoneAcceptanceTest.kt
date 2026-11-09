package com.alpha.assistant.courses

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import com.alpha.assistant.tools.OneUseCredential
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingPhoneAcceptanceTest {
    /** Deliberately limited to the separately installed personal reading build. */
    @Test fun provisionCredentials() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("readingCredentialRun")
        assumeTrue(id != null)
        checkNotNull(id)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.alpha.assistant.readingtest")
        val voice = OneUseCredential.receive("$id-eleven") { println("READING_CREDENTIAL_READY eleven") }
        val voiceId = OneUseCredential.receive("$id-voice") { println("READING_CREDENTIAL_READY voice") }
        ReadingVoice(context).saveVerifiedCredential(voice, voiceId)
        val deepseek = OneUseCredential.receive("$id-deepseek") { println("READING_CREDENTIAL_READY deepseek") }
        SecureKeyStore(context).save(deepseek).getOrThrow()
        println("READING_CREDENTIALS_SAVED")
    }
    @Test fun generateTwoLivePracticeSessions() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("readingLivePractice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.alpha.assistant.readingtest")
        val fixture = ShellFixture("reading-live-practice")
        val database = "${fixture.store.databaseName}-courses.db"
        try {
            fixture.install(saveKey = false)
            CloudTestActivity.factory = { app ->
                ShellViewModel(app, store = fixture.store, keys = SecureKeyStore(app)).also { fixture.model = it }
            }
            fixture.launch().use {
                val model = checkNotNull(fixture.model)
                val store = model.courseStore
                val program = checkNotNull(model.readingProgram)
                val seed = ReadingMaterial.practice(listOf(ReadingItem(ReadingMaterial.vocabulary.first(), false)), 0, false)
                var course = store.saveReading(seed)
                val oldWords = mutableSetOf<String>()
                repeat(3) { index ->
                    if (index > 0) {
                        course = withTimeout(60_000) { program.next(course.id) }
                        check(course.setup.topic.startsWith("Практика"))
                        val words = course.outline.steps().map { it.slide.goal }
                        check(words.size == 6 && words.none { it in oldWords })
                        oldWords.addAll(words)
                        android.util.Log.i("AlphyReading", "live practice batch=$index accepted=${words.size} words=${words.joinToString(",")}")
                    }
                    while (!course.progress.isComplete) {
                        val slide = checkNotNull(store.slide(course.id, checkNotNull(course.progress.currentSlideId)))
                        val key = JSONObject(JSONObject(slide.content.definitionJson).getJSONObject("initialState").getString(EXERCISE_FIELD))
                        val answer = if (key.getString("kind") == "CHOICE") CourseAnswer.Choice(key.getString("correct"))
                        else key.getJSONArray("correctOrder").let { list ->
                            CourseAnswer.Order(List(list.length()) { list.getString(it) })
                        }
                        course = model.courseController.submitAnswer(course.id, course.revision, slide.id, UUID.randomUUID().toString(), answer)
                    }
                }
            }
        } finally {
            fixture.remove()
            context.deleteDatabase(database)
        }
    }
}
