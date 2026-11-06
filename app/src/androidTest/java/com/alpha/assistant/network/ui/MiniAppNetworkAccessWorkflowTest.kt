package com.alpha.assistant.network.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class MiniAppNetworkAccessWorkflowTest {
    private val robot = ShellRobot("mini-app-network-access")

    @Test fun explicitActionsChangeOnlyTheSelectedMiniApp() = withScreen { scenario, scope ->
        val host = TestHost(scope, listOf(MAP, NOTES.copy(granted = true)))
        mount(scenario, host.access)
        val allow = robot.text(R.string.mini_app_network_allow, MAP.title)
        val revoke = robot.text(R.string.mini_app_network_revoke, MAP.title)
        val other = robot.text(R.string.mini_app_network_revoke, NOTES.title)

        robot.reveal(allow)
        assertTrue(host.writes.isEmpty())
        robot.click(allow)
        robot.reveal(revoke)
        assertEquals(listOf(MAP.copy(granted = true), NOTES.copy(granted = true)), host.apps.value)
        robot.click(revoke)
        robot.reveal(allow)
        robot.reveal(other)

        assertEquals(listOf(MAP.id to true, MAP.id to false), host.writes.toList())
        assertEquals(listOf(MAP, NOTES.copy(granted = true)), host.apps.value)
    }

    @Test fun failedRevocationRetainsTheSavedGrantAndOffersRetryAfterRemount() = withScreen { scenario, scope ->
        val granted = MAP.copy(granted = true)
        val host = TestHost(scope, listOf(granted)) { attempt ->
            if (attempt == 1) Result.failure(IOException("Write failed")) else Result.success(Unit)
        }
        val revoke = robot.text(R.string.mini_app_network_revoke, MAP.title)
        val allow = robot.text(R.string.mini_app_network_allow, MAP.title)
        val failure = robot.text(R.string.mini_app_network_revoke_failed)
        val unavailable = robot.text(R.string.mini_app_network_unavailable)
        mount(scenario, host.access)

        robot.reveal(revoke)
        robot.click(revoke)
        robot.reveal(failure)
        assertEquals(listOf(granted), host.apps.value)
        mount(scenario, null)
        robot.waitGone(failure)
        robot.reveal(unavailable)
        mount(scenario, host.access)
        robot.waitGone(unavailable)
        robot.reveal(robot.text(R.string.mini_app_network_granted))
        assertTrue(robot.reveal(revoke).isEnabled)
        robot.reveal(failure)
        robot.reveal(revoke)
        robot.click(revoke)
        robot.reveal(allow)
        robot.waitGone(failure)

        assertEquals(listOf(MAP.id to false, MAP.id to false), host.writes.toList())
        assertEquals(listOf(MAP), host.apps.value)
    }

    @Test fun aPendingEnableDoesNotSendAnotherWriteOrShowAGrant() = withScreen { scenario, scope ->
        val result = CompletableDeferred<Result<Unit>>()
        val host = TestHost(scope, listOf(MAP)) { result.await() }
        val allow = robot.text(R.string.mini_app_network_allow, MAP.title)
        val pending = robot.text(R.string.mini_app_network_pending)
        mount(scenario, host.access)

        val button = grantButton(allow)
        assertTrue(button.isEnabled)
        assertTrue(robot.diagnostics.click(allow, button))
        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        robot.reveal(pending)
        val disabled = grantButton(allow)
        assertFalse(disabled.isEnabled)
        assertFalse(disabled.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertEquals(listOf(MAP.id to true), host.writes.toList())
        assertEquals(listOf(MAP), host.apps.value)

        result.complete(Result.failure(IOException("Write failed")))
        robot.reveal(robot.text(R.string.mini_app_network_allow_failed))
        assertTrue(grantButton(allow).isEnabled)
        assertEquals(listOf(MAP.id to true), host.writes.toList())
        assertEquals(listOf(MAP), host.apps.value)
    }

    @Test fun absentHostExplainsTheScopeWithoutOfferingAGrant() = withScreen { scenario, _ ->
        mount(scenario, null)

        assertTrue(robot.waitFor("Сеть для мини-приложений").isHeading)
        robot.reveal("Доступ охватывает любые адреса в интернете и локальной сети. Он действует и при перенаправлениях, и при загрузке содержимого веб-страниц, например изображений и шрифтов.")
        robot.reveal("Мини-приложение с доступом к сети может отправлять любые данные, которые Android позволяет ему получить. Alphy не запрашивает подтверждение для каждой отправки.")
        robot.reveal("Разрешения Android, в том числе для локальной сети, действуют отдельно. Доступ к сети в Alphy не заменяет их.")
        robot.reveal("Мини-приложение может передавать доступные ему данные в ИИ Alphy. Для этого действует согласие, данное при добавлении ключа поставщика ИИ. Доступ мини-приложения к сети для этого не нужен.")
        assertFalse(robot.reveal("Настройки доступа к сети недоступны.").isClickable)
        assertFalse(robot.texts().any { it.startsWith("Разрешить доступ:") || it.startsWith("Отозвать доступ:") })
    }

    @Test fun theLastMiniAppActionFitsAtDoubleFontScale() = withScreen { scenario, scope ->
        val app = NOTES.copy(title = "Заметки о поездках и прогулках")
        val host = TestHost(scope, listOf(MAP, app))
        val allow = robot.text(R.string.mini_app_network_allow, app.title)
        mount(scenario, host.access, fontScale = 2f)

        robot.reveal(allow)
        robot.assertTarget(allow)
        robot.click(allow)
        robot.reveal(robot.text(R.string.mini_app_network_revoke, app.title))
        assertEquals(listOf(app.id to true), host.writes.toList())
    }

    private fun grantButton(label: String): AccessibilityNodeInfo =
        generateSequence(robot.reveal(label)) { it.parent }
            .first { it.isClickable || it.className == "android.widget.Button" }

    private fun mount(
        scenario: ActivityScenario<CloudTestActivity>,
        host: MiniAppNetworkAccessHost?,
        fontScale: Float = 1f,
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(
                                Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) { MiniAppNetworkAccessSection(host) }
                        }
                    }
                }
            }
        }
    }

    private fun withScreen(run: (ActivityScenario<CloudTestActivity>, CoroutineScope) -> Unit) {
        val fixture = ShellFixture("mini-app-network-access")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            fixture.install(saveKey = false)
            fixture.launch().use { run(it, scope) }
        } finally {
            scope.cancel()
            fixture.remove()
        }
    }

    private class TestHost(
        scope: CoroutineScope,
        initial: List<MiniAppNetworkAccessApp>,
        save: suspend (Int) -> Result<Unit> = { Result.success(Unit) },
    ) {
        val apps = MutableStateFlow(initial)
        val writes = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val access = MiniAppNetworkAccessHost(apps, scope) { id, enabled ->
            writes += id to enabled
            save(writes.size).onSuccess {
                apps.value = apps.value.map { if (it.id == id) it.copy(granted = enabled) else it }
            }
        }
    }

    private companion object {
        val MAP = MiniAppNetworkAccessApp("map", "Карта", granted = false)
        val NOTES = MiniAppNetworkAccessApp("notes", "Заметки", granted = false)
    }
}
