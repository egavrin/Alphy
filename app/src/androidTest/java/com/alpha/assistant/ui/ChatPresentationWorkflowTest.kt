package com.alpha.assistant.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Resources
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.Source
import com.alpha.assistant.CloudTestActivity
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ChatPresentationWorkflowTest {
    private val robot = ShellRobot("chat-presentation")

    // Failure cases: a fragment enables links or actions, TalkBack reads it, or the accepted answer has no live region.
    @Test fun provisionalTextIsInertUntilTheAcceptedAnswerCompletes() {
        val fixture = ShellFixture("chat-stream-ui")
        val complete = CompletableDeferred<Unit>()
        val fragment = "**Ожидаемая часть** [пример](https://example.com/)"
        val answer = "Окончательный ответ."
        check(fixture.keys.save("alpha-test-only").isSuccess)
        CloudTestActivity.factory = { app -> ShellViewModel(app, object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                emit(CloudEvent.AnswerFragment(fragment))
                complete.await()
                emit(CloudEvent.RoundComplete("""{"kind":"answer","text":${JSONObject.quote("**$answer**")},"brief":"","targetId":null,"search":"none"}""", false))
            }
        }, fixture.store, fixture.keys) }
        try {
            fixture.launch().use { scenario ->
                robot.edit(robot.text(R.string.composer_label), "Проверка ответа")
                robot.click(robot.text(R.string.composer_send))
                val incomplete = robot.waitFor("Ответ не завершён")
                robot.waitFor("DeepSeek отвечает…")
                assertTrue(robot.composedText(scenario, fragment))
                robot.find(fragment)?.let { node ->
                    assertFalse(node.isImportantForAccessibility)
                    assertFalse(node.isVisibleToUser)
                    assertFalse(node.isClickable)
                    assertFalse(node.isLongClickable)
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, node.liveRegion)
                }
                assertTrue(generateSequence(incomplete) { it.parent }.none { it.isLongClickable || it.liveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE })
                complete.complete(Unit)
                val completed = robot.waitFor(answer)
                assertTrue(generateSequence(completed) { it.parent }.any { it.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE })
                assertNull(robot.find("Ответ не завершён"))
                openActions(answer)
                robot.waitFor("Копировать")
                robot.click("Выбрать текст для заметки")
                robot.waitFor("Передать выбранный текст в заметки")
                robot.click("Закрыть")
                robot.waitFor(answer)
            }
        } finally {
            complete.complete(Unit)
            fixture.remove()
        }
    }

    // Failure cases: citation numbers change, an icon lacks a fallback, or a source launches with the wrong action or URL.
    @Test fun sourceChipsOpenOnlyValidUrlsAndHaveFallbackIcons() {
        val fixture = ShellFixture("chat-sources-ui")
        val publicUrl = "https://example.com/public?topic=weather#forecast"
        val sources = listOf(
            Source("Public article", publicUrl, siteName = "Example", siteIcon = "https://127.0.0.1/icon.png"),
            Source("Unsafe article", "javascript:alert(1)")
        )
        fixture.store.saveConversation(Conversation("chat", "Источники", 1, 1, messages = listOf(
            Message("request", "user", "Найдите источник", 1, "sent",
                attachments = listOf(Attachment("content://fixture/image", "карта.png", "image/png"))),
            Message("answer", "assistant", "Ответ по источнику [1].", 1, "complete", searchStatus = "found", sources = sources))))
        fixture.store.saveSetting("selected", "chat")
        fixture.install()
        val launchedIntent = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                launchedIntent.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        try {
            fixture.launch().use { scenario ->
                robot.waitFor("Вложение · карта.png · только в исходном запросе")
                robot.waitFor("Веб-поиск завершён")
                val valid = "Источник 1. Example. Public article"
                assertTrue(robot.waitFor(valid).isVisibleToUser)
                assertTrue(robot.composed(scenario, "source-fallback-1"))
                robot.instrumentation.addMonitor(monitor)
                try {
                    robot.click(valid)
                    assertEquals(1, monitor.hits)
                    val intent = launchedIntent.get()
                    assertEquals(Intent.ACTION_VIEW, intent?.action)
                    assertEquals(publicUrl, intent?.dataString)
                    val invalid = "Источник 2. Unsafe article"
                    assertTrue(robot.waitFor(invalid).isVisibleToUser)
                    assertTrue(robot.composed(scenario, "source-fallback-2"))
                    robot.click(invalid)
                    robot.waitFor("Не удалось открыть ссылку")
                    assertEquals(1, monitor.hits)
                } finally {
                    robot.instrumentation.removeMonitor(monitor)
                }
            }
        } finally {
            fixture.remove()
        }
    }

    // Failure cases: a previous failure offers retry, or key recovery and retry become unreachable at large font scale.
    @Test fun latestFailureHasRetryAndSettingsAtLargeFontScale() {
        val fixture = ShellFixture("chat-failure-ui")
        val user = Message("user", "user", "Исходный запрос", 1, "sent")
        val old = Message("old", "assistant", "Первый фрагмент", 1, "cancelled", replyTo = user.id)
        val latest = Message("latest", "assistant", "Последний фрагмент", 1, "error",
            replyTo = user.id, error = "Добавьте ключ.", errorCode = "auth")
        fixture.store.saveConversation(Conversation("chat", "Восстановление", 1, 1, messages = listOf(user, old, latest)))
        fixture.store.saveSetting("selected", "chat")
        fixture.install()
        val fontScale = robot.shell("settings get system font_scale")
        try {
            robot.shell("settings put system font_scale 2.0")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 2f }
            fixture.launch().use { scenario ->
                scenario.onActivity { assertEquals(2f, it.resources.configuration.fontScale) }
                robot.reveal("Последний фрагмент")
                openActions("Последний фрагмент")
                robot.waitFor("Повторить запрос")
                robot.back()
                val settings = robot.text(R.string.open_settings)
                robot.reveal(settings)
                robot.click(settings)
                robot.waitFor(robot.text(R.string.settings_deepseek))
            }
        } finally {
            fixture.remove()
            robot.shell(if (fontScale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $fontScale")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == (fontScale.toFloatOrNull() ?: 1f) }
        }
    }

    private fun openActions(text: String) {
        val node = generateSequence(robot.waitFor(text)) { it.parent }.firstOrNull { it.isLongClickable }
        assertTrue("Accessible long click for $text", node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
    }
}
