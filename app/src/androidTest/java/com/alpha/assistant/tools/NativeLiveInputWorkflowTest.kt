package com.alpha.assistant.tools

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLiveInputWorkflowTest {
    private val robot = ShellRobot("native-live-input")
    private val text = "Заметка"
    private val other = "Другая заметка"
    private val slider = "Уровень"

    @Test fun textUsesLiveReceiptsAndKeepsNewerTypingUntilTheFinalAck() {
        val signals = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        val admission = CompletableDeferred<Unit>()
        val channel = Channel(admission) {
            assertEquals("Host blocks actions before admission starts", true to false, signals.lastOrNull())
        }
        withFixture(channel = channel, onLiveInputState = { unfinished, unsaved -> signals += unfinished to unsaved }) { scenario, shown, discrete ->
            robot.edit(text, "первая")
            val old = channel.await(textAction)
            robot.waitFor(robot.text(R.string.tool_live_input_pending))
            robot.edit(text, "последняя")
            val newest = channel.await(textAction, "последняя")
            publish(scenario, shown, old)
            assertEquals("последняя", robot.field(text).text.toString())
            robot.waitFor("Сохранённая заметка: первая")
            robot.click("Продолжить")
            robot.waitFor(robot.text(R.string.tool_live_input_finish_first))
            assertTrue(discrete.isEmpty())
            assertEquals("последняя", robot.field(text).text.toString())
            publish(scenario, shown, newest)
            robot.waitFor("Сохранённая заметка: последняя")

            val finish = robot.text(R.string.tool_live_input_finish, text)
            robot.assertTarget(finish)
            val editor = Rect().also { robot.field(text).getBoundsInScreen(it) }
            robot.shell("input tap ${editor.centerX()} ${editor.centerY()}")
            robot.waitUntil { robot.keyboardTop(scenario) != null }
            if (Build.VERSION.SDK_INT >= 30) {
                val ime = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                assertTrue(robot.field(text).performAction(ime.id))
            } else robot.shell("input keyevent KEYCODE_ENTER")
            robot.waitUntil { channel.finishAttempts.get() == 1 }
            if (robot.keyboardTop(scenario) != null) robot.back()
            robot.click("Продолжить")
            assertTrue("A final value can still be waiting for admission", channel.calls.none { it.finished })
            assertTrue(discrete.isEmpty())
            assertEquals(listOf(true to false), signals.toList())
            admission.complete(Unit)
            val final = channel.await(textAction, "последняя", finished = true)
            robot.click("Продолжить")
            assertTrue("Admission alone cannot release the discrete action", discrete.isEmpty())
            assertEquals(listOf(true to false), signals.toList())
            publish(scenario, shown, final)
            robot.waitGone(robot.text(R.string.tool_live_input_pending))
            assertEquals(listOf(true to false, false to false), signals.toList())
            robot.click("Продолжить")
            assertEquals(listOf("next"), discrete)
            assertTrue(channel.frames.get() > 0)
        }
    }

    @Test fun sliderTouchReleaseSubmitsTheFinalValueAndPublishesItWithoutApply() {
        val channel = Channel()
        withFixture(channel = channel) { scenario, shown, _ ->
            val control = robot.bounds(slider)
            robot.shell("input swipe ${control.left + control.width() / 4} ${control.centerY()} " +
                "${control.left + control.width() * 3 / 4} ${control.centerY()} 350")
            val final = channel.await("level", finished = true)
            val value = (final.value as Number).toDouble()
            assertTrue(value in 1.0..10.0)
            assertTrue(channel.calls.any { !it.finished && it.id == "level" })
            assertNull(robot.find(robot.text(R.string.tool_generated_apply, slider)))
            publish(scenario, shown, final)
            val committed = JSONObject(shown.value.tool.state).get("level")
            assertEquals(value, (committed as Number).toDouble(), 0.0)
            robot.waitFor("Сохранённый уровень: $committed")
        }
    }

    @Test fun disabledControlCancelsSharedLeaseWithoutResendingTheOtherDraft() {
        val channel = Channel()
        withFixture(channel = channel) { scenario, shown, _ ->
            robot.edit(text, "текст")
            val first = channel.await(textAction)
            robot.edit(other, "черновик")
            val second = channel.await("other")
            scenario.onActivity {
                shown.value = changed(shown.value, JSONObject(shown.value.tool.state).put("editable", false))
            }
            robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
            robot.waitUntil { !first.active() && !second.active() }
            assertEquals("черновик", robot.field(other).text.toString())
            assertTrue(channel.cancellations.get() > 0)
            assertEquals(2, channel.calls.size)
            robot.click(robot.text(R.string.tool_generated_apply, other))
            val retry = channel.await("other", "черновик", finished = true)
            publish(scenario, shown, retry)
            robot.waitFor("Другая сохранённая: черновик")
        }
    }

    @Test fun foregroundLossAndReplacementInvalidateOldReceipts() {
        val channel = Channel()
        withFixture(channel = channel) { scenario, shown, _ ->
            robot.edit(text, "локально")
            val pending = channel.await(textAction)
            scenario.moveToState(Lifecycle.State.STARTED)
            assertFalse(pending.active())
            scenario.moveToState(Lifecycle.State.RESUMED)
            robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
            assertEquals(1, channel.calls.size)
            scenario.onActivity {
                shown.value = shown.value.copy(tool = shown.value.tool.copy(revision = 2))
            }
            robot.waitUntil { robot.field(text).text.toString().let { it.isEmpty() || it == text } }
            assertFalse(pending.active())
            assertEquals(1, channel.calls.size)
        }
    }

    @Test fun hostSeesUnsavedOnRejectionAndOnDisposalBeforeTheFinalReceipt() {
        val showControls = mutableStateOf(true)
        val signals = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        val channel = Channel()
        withFixture(channel = channel, showControls = showControls,
            onLiveInputState = { unfinished, unsaved -> signals += unfinished to unsaved }) { scenario, shown, discrete ->
            robot.edit(text, "локально")
            val offer = channel.await(textAction)
            robot.click(robot.text(R.string.tool_live_input_finish, text))
            val rejected = channel.await(textAction, "локально", finished = true)
            rejected.receipt.complete(LiveInputResult.Rejected(LiveInputRejection.Conflict))
            robot.waitUntil { signals.lastOrNull() == (false to true) }
            robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
            assertFalse(offer.active())
            assertEquals("локально", robot.field(text).text.toString())

            robot.click(robot.text(R.string.tool_generated_apply, text))
            robot.waitUntil { channel.calls.count { it.finished } == 2 }
            val retry = channel.calls.last()
            assertTrue(retry.active())
            assertEquals(true to false, signals.last())
            scenario.onActivity { showControls.value = false }
            robot.waitGone(text)
            robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
            assertFalse(retry.active())
            assertEquals(listOf(true to false, false to true, true to false, false to true), signals.toList())
            retry.receipt.complete(LiveInputResult.Accepted(shown.value))
            assertTrue(discrete.isEmpty())
        }
    }

    @Test fun rejectedAndHiddenInputRemainsExplicitAtLargeSystemFont() {
        val font = robot.shell("settings get system font_scale")
        val channel = Channel()
        try {
            robot.shell("settings put system font_scale 2.0")
            withFixture(channel = channel) { scenario, shown, _ ->
                scenario.onActivity { assertEquals(2f, it.resources.configuration.fontScale, 0f) }
                robot.edit(text, "локально")
                val pending = channel.await(textAction)
                pending.receipt.complete(LiveInputResult.Rejected(LiveInputRejection.Conflict))
                robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
                assertEquals("локально", robot.field(text).text.toString())
                val retry = robot.text(R.string.tool_generated_apply, text)
                robot.assertTarget(retry)
                robot.click(retry)
                val final = channel.await(textAction, "локально", finished = true)
                scenario.onActivity {
                    shown.value = changed(shown.value, JSONObject(shown.value.tool.state).put("shown", false))
                }
                robot.waitGone(text)
                robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
                assertFalse(final.active())
                assertEquals(2, channel.calls.size)
            }
        } finally {
            if (font == "null") robot.shell("settings delete system font_scale")
            else robot.shell("settings put system font_scale $font")
            assertEquals(font, robot.shell("settings get system font_scale"))
        }
    }

    @Test fun fallbackRequiresExplicitApplyAndUnavailableHostDisablesInput() {
        val applied = CopyOnWriteArrayList<Pair<String, Any?>>()
        withFixture(fallback = { expected, id, value, active ->
            assertTrue(active())
            applied += id to value
            LiveInputResult.Accepted(changed(ArtifactSnapshot(expected, 0), JSONObject(expected.state).put(id, value)))
        }) { _, _, discrete ->
            robot.edit(text, "явно")
            assertTrue(applied.isEmpty())
            val apply = robot.text(R.string.tool_generated_apply, text)
            robot.assertTarget(apply)
            robot.click(apply)
            robot.waitUntil { applied.size == 1 }
            assertEquals(listOf(textAction to "явно"), applied.toList())
            assertTrue(discrete.isEmpty())
        }
        withFixture { _, _, discrete ->
            robot.waitFor(robot.text(R.string.tool_live_input_unavailable))
            val field = robot.waitFor(text)
            assertTrue(generateSequence(field) { it.parent }.any { !it.isEnabled })
            val apply = robot.waitFor(robot.text(R.string.tool_generated_apply, text))
            assertTrue(generateSequence(apply) { it.parent }.any { !it.isEnabled })
            assertTrue(discrete.isEmpty())
        }
    }

    private fun publish(scenario: ActivityScenario<CloudTestActivity>, shown: androidx.compose.runtime.MutableState<ArtifactSnapshot>, call: Call) {
        scenario.onActivity {
            assertTrue(call.active())
            val next = changed(shown.value, JSONObject(shown.value.tool.state).put(call.id, call.value))
            shown.value = next
            call.receipt.complete(LiveInputResult.Accepted(next))
        }
    }

    private fun changed(previous: ArtifactSnapshot, state: JSONObject) = previous.copy(sequence = previous.sequence + 1,
        tool = previous.tool.copy(state = state.toString(), stateVersion = previous.tool.stateVersion + 1))

    private fun withFixture(channel: Channel? = null, fallback: (suspend (ToolRecord, String, Any?, () -> Boolean) -> LiveInputResult)? = null,
        showControls: androidx.compose.runtime.State<Boolean> = mutableStateOf(true),
        onLiveInputState: (Boolean, Boolean) -> Unit = { _, _ -> },
        block: (ActivityScenario<CloudTestActivity>, androidx.compose.runtime.MutableState<ArtifactSnapshot>, List<String>) -> Unit) {
        val fixture = ShellFixture("native-live-input")
        val spec = ToolSpec.parse(DEFINITION)
        val initial = ArtifactSnapshot(ToolRecord("editor", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true), 0, sessionGeneration = 1)
        val shown = mutableStateOf(initial)
        val discrete = CopyOnWriteArrayList<String>()
        val hostUnsaved = mutableStateOf(false)
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AlphaTheme {
                            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                                val snapshot = shown.value
                                val live = if (channel != null) NativeLiveInput.automatic(channel, snapshot, true)
                                    else fallback?.let { NativeLiveInput.apply(snapshot.tool, true, it) }
                                if (showControls.value) {
                                    ToolContent(snapshot.tool.ui, snapshot.tool.state, true, liveInput = live,
                                        onLiveInputState = { unfinished, unsaved ->
                                            hostUnsaved.value = unsaved
                                            onLiveInputState(unfinished, unsaved)
                                        }) { id, _ -> discrete += id }
                                } else if (hostUnsaved.value) Text(stringResource(R.string.tool_live_input_unsaved))
                            }
                        }
                    }
                }
                robot.waitFor(text)
                block(scenario, shown, discrete)
            }
        } finally { fixture.remove() }
    }

    private data class Call(val id: String, val value: Any?, val finished: Boolean, val active: () -> Boolean,
        val receipt: CompletableDeferred<LiveInputResult> = CompletableDeferred())

    private inner class Channel(private val finishAdmission: CompletableDeferred<Unit>? = null,
        private val beforeAdmission: () -> Unit = {}) : LiveArtifactUiHost {
        val calls = CopyOnWriteArrayList<Call>()
        val cancellations = AtomicInteger()
        val frames = AtomicInteger()
        val finishAttempts = AtomicInteger()
        override suspend fun offerLiveInput(expected: ArtifactSnapshot, actionId: String, value: Any?, isActive: () -> Boolean): LiveInputSubmission {
            beforeAdmission()
            return submit(Call(actionId, value, false, isActive))
        }
        override suspend fun finishLiveInput(expected: ArtifactSnapshot, actionId: String, value: Any?, isActive: () -> Boolean): LiveInputSubmission {
            beforeAdmission()
            finishAttempts.incrementAndGet()
            finishAdmission?.await()
            return submit(Call(actionId, value, true, isActive))
        }
        private fun submit(call: Call) = LiveInputSubmission.Pending(call.receipt).also { calls += call }
        override fun liveInputFrame(frameTimeNanos: Long) { frames.incrementAndGet() }
        override fun cancelLiveInput() { cancellations.incrementAndGet() }
        override suspend fun open() = error("The preview receives an open snapshot")
        override suspend fun startSession() = error("The preview receives an open snapshot")
        override suspend fun snapshot() = error("The preview receives an open snapshot")
        override suspend fun flush() = error("The preview receives an open snapshot")
        override suspend fun event(event: JSONObject) = error("Live input uses a dedicated path")
        override suspend fun close() = Unit
        fun await(id: String, value: Any? = null, finished: Boolean = false): Call {
            fun match(call: Call) = call.id == id && call.finished == finished && (value == null || call.value == value)
            robot.waitUntil { calls.any(::match) }
            return calls.last(::match)
        }
    }

    private companion object {
        const val textAction = "note"
        val DEFINITION = """
            {"formatVersion":3,"title":"Редактор","description":"","capabilities":[],"source":"return {state:input.state};",
             "stateSchema":{"note":"string","other":"string","level":"number","editable":"boolean","shown":"boolean"},
             "initialState":{"note":"","other":"","level":1,"editable":true,"shown":true},"ui":{"type":"column","children":[
               {"type":"input","text":"Заметка","bind":"note","action":"note","inputMode":"live","maxLines":1,"enabledBind":"editable","visibleBind":"shown"},
               {"type":"input","text":"Другая заметка","bind":"other","action":"other","inputMode":"live","maxLines":1},
               {"type":"slider","text":"Уровень","bind":"level","action":"level","inputMode":"live","min":1,"max":10,"step":1},
               {"type":"text","text":"Сохранённая заметка","bind":"note"},
               {"type":"text","text":"Другая сохранённая","bind":"other"},
               {"type":"text","text":"Сохранённый уровень","bind":"level"},
               {"type":"button","text":"Продолжить","action":"next"}
             ]}}
        """.trimIndent()
    }
}
