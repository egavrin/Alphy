package com.alpha.assistant.tools

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LiveInputHostWorkflowTest {
    private val robot = ShellRobot("live-input-host")

    @Test fun aChatCardAndFullAppShareDurableLiveState() = withFixture { fixture, store ->
        val tool = seed(store, "shared", "Shared", conversationId = "chat")
        fixture.seedChat("chat", "Discussion", "Live editor discussion", selected = true)
        fixture.install()
        fixture.launch().use {
            finishText(store, tool.id, "Shared note", "from chat")
            robot.click(robot.text(R.string.tool_open))
            robot.waitFor(robot.text(R.string.tool_library_back))
            assertEquals("from chat", robot.field("Shared note").text.toString())
            finishText(store, tool.id, "Shared note", "from the full app")
            val saved = store.tool(tool.id)
            robot.edit("Shared note", "blocked effect")
            robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
            assertEquals(saved, store.tool(tool.id))
            assertTrue(store.effects(tool.id).isEmpty())
            robot.diagnostics.capture("shared-durable-ack")
        }
        assertEquals(0, fixture.provider.calls)
    }

    @Test fun twoNativeInputsWriteWhileAGraphicsHostOwnsTheRetainedSlot(): Unit = runBlocking {
        withFixture { fixture, store ->
            val graphics = seed(store, "graphics", "Graphics", graphics = true)
            val left = seed(store, "left", "Left")
            val right = seed(store, "right", "Right")
            fixture.install()
            fixture.launch().use { scenario ->
                val controller = checkNotNull(fixture.model).tools
                val graphicsHost = controller.artifactHost(graphics)
                graphicsHost.open()
                try {
                    scenario.onActivity { activity ->
                        activity.setContent {
                            AlphaTheme {
                                Row(Modifier.safeDrawingPadding()) {
                                    for (id in listOf(left.id, right.id)) {
                                        Box(Modifier.weight(1f)) {
                                            controller.tools.find { it.id == id }?.let { tool ->
                                                val spec = ToolSpec.parsePersisted(tool.spec)
                                                MiniAppContent(controller, tool, tool, spec.title, enabled = !controller.busy,
                                                    embedded = true, presentation = spec.nativePresentation)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    robot.edit("Left note", "left pending")
                    robot.edit("Right note", "right pending")
                    finishText(store, left.id, "Left note", "left final")
                    finishText(store, right.id, "Right note", "right final")
                    assertEquals(graphics.id, graphicsHost.snapshot().tool.id)
                    assertTrue(store.effects(left.id).isEmpty() && store.effects(right.id).isEmpty())
                    robot.diagnostics.capture("desktop-with-retained-graphics")
                } finally {
                    graphicsHost.close()
                }
            }
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun sharedGraphicsAdapterRejectsPendingInputOnCoverBackgroundAndRemoval() = withFixture { fixture, store ->
        val record = seed(store, "covered", "Covered", graphics = true)
        val snapshot = ArtifactSnapshot(record, 1L, sessionGeneration = 1L)
        val host = GatedHost(snapshot)
        val covered = mutableStateOf(false)
        val attached = mutableStateOf(true)
        val states = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        fixture.install()
        fixture.launch().use { scenario ->
            scenario.onActivity { activity ->
                val controller = checkNotNull(fixture.model).tools
                activity.setContent {
                    AlphaTheme {
                        Column(Modifier.safeDrawingPadding()) {
                            if (attached.value) {
                                MiniAppContent(controller, record, record, "Covered", enabled = true,
                                    graphicsHost = host, graphicsSnapshot = snapshot, liveAllowed = !covered.value,
                                    onLiveInputState = { unfinished, unsaved -> states += unfinished to unsaved })
                            }
                        }
                    }
                }
            }
            robot.edit("Covered note", "pending before cover")
            robot.waitUntil { host.values.contains("pending before cover") && host.frames.get() > 0 }
            assertEquals(true to false, states.last())
            scenario.onActivity { covered.value = true }
            robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
            robot.waitUntil { host.cancels.get() > 0 }
            assertEquals(false to true, states.last())
            assertFalse(checkNotNull(host.guard).invoke())
            assertEquals(0, host.opens.get())

            val cancelledBeforeSlider = host.cancels.get()
            scenario.onActivity { covered.value = false }
            val setProgress = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
            robot.waitUntil {
                val label = robot.find("Covered level") ?: return@waitUntil false
                val slider = generateSequence(label) { it.parent }.firstOrNull { setProgress in it.actionList }
                slider?.isEnabled == true && slider.isVisibleToUser && slider.performAction(setProgress.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0.75f)
                })
            }
            robot.waitUntil { host.values.any { it is Number && it.toDouble() == 4.0 } }
            scenario.moveToState(Lifecycle.State.CREATED)
            robot.waitUntil { host.cancels.get() > cancelledBeforeSlider }
            assertFalse(checkNotNull(host.guard).invoke())

            scenario.moveToState(Lifecycle.State.RESUMED)
            robot.field("Covered note")
            val beforeApply = host.values.size
            robot.click(robot.text(R.string.tool_generated_apply, "Covered note"))
            robot.waitUntil { host.values.size > beforeApply }
            val cancelledBeforeRemoval = host.cancels.get()
            scenario.onActivity { attached.value = false }
            robot.waitUntil { host.cancels.get() > cancelledBeforeRemoval && states.lastOrNull() == (false to true) }
            assertFalse(checkNotNull(host.guard).invoke())
            assertEquals(0, host.opens.get())
            assertEquals("", JSONObject(checkNotNull(store.tool(record.id)).state).getString("note"))
        }
    }

    @Test fun unsupportedGraphicsChannelUsesExplicitApplyAndRejectsAStaleDraft() = withFixture { fixture, store ->
        val record = seed(store, "fallback", "Fallback", graphics = true)
        fixture.install()
        fixture.launch().use { scenario ->
            val model = checkNotNull(fixture.model)
            scenario.onActivity { activity ->
                activity.setContent {
                    AlphaTheme {
                        Column(Modifier.safeDrawingPadding()) {
                            model.tools.tools.find { it.id == record.id }?.let {
                                MiniAppContent(model.tools, it, it, "Fallback", enabled = true)
                            }
                        }
                    }
                }
            }
            val apply = robot.text(R.string.tool_generated_apply, "Fallback note")
            robot.edit("Fallback note", "explicit value")
            assertEquals(record, store.tool(record.id))
            robot.click(apply)
            robot.waitUntil { JSONObject(checkNotNull(store.tool(record.id)).state).getString("note") == "explicit value" }
            assertEquals("explicit value", robot.field("Fallback note").text.toString())

            robot.edit("Fallback note", "stale draft")
            val saved = checkNotNull(store.tool(record.id))
            val updated = saved.copy(stateVersion = saved.stateVersion + 1,
                state = JSONObject(saved.state).put("note", "concurrent value").toString())
            store.saveTool(updated, false)
            robot.waitUntil { model.tools.tools.firstOrNull { it.id == record.id }?.stateVersion == updated.stateVersion }
            robot.click(apply)
            robot.waitFor(robot.text(R.string.tool_live_input_unsaved))
            assertEquals(updated, store.tool(record.id))
            assertTrue(store.effects(record.id).isEmpty())
        }
    }

    @Test fun immersiveControlsStayOpenUntilInputIsDurablyFinished() = withFixture { fixture, store ->
        val record = seed(store, "immersive", "Immersive", graphics = true, immersive = true)
        fixture.install()
        fixture.launch().use { scenario ->
            robot.waitUntil { robot.composed(scenario, "desktop") }
            scenario.onActivity { checkNotNull(fixture.model).openSurface(record.id) }
            robot.click(robot.text(R.string.tool_game_controls_open))
            robot.click(robot.text(R.string.tool_controls))
            val note = "Immersive note"
            val value = "wait for the final receipt"
            val finish = robot.text(R.string.tool_live_input_finish, note)
            robot.edit(note, value)
            robot.waitUntil { JSONObject(checkNotNull(store.tool(record.id)).state).getString("note") == value }
            val intermediate = checkNotNull(store.tool(record.id)).stateVersion

            robot.back()
            robot.waitFor(robot.text(R.string.tool_live_input_finish_first))
            assertEquals(value, robot.field(note).text.toString())
            assertEquals(intermediate, checkNotNull(store.tool(record.id)).stateVersion)
            robot.click(finish)
            robot.waitUntil { checkNotNull(store.tool(record.id)).stateVersion > intermediate }
            robot.waitGone(finish)
            robot.back()
            robot.waitGone(note)
            robot.click(robot.text(R.string.tool_game_left))
            robot.waitUntil { JSONObject(checkNotNull(store.tool(record.id)).state).getString("movementNote") == value }
            assertTrue(store.effects(record.id).isEmpty())
            robot.diagnostics.capture("immersive-after-final-ack")
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun finishText(store: ToolStore, id: String, label: String, value: String) {
        robot.edit(label, value)
        robot.waitUntil { JSONObject(checkNotNull(store.tool(id)).state).getString("note") == value }
        val intermediate = checkNotNull(store.tool(id)).stateVersion
        robot.click(robot.text(R.string.tool_live_input_finish, label))
        robot.waitUntil { checkNotNull(store.tool(id)).stateVersion > intermediate }
        robot.waitGone(robot.text(R.string.tool_live_input_finish, label))
    }

    private fun seed(store: ToolStore, id: String, title: String, conversationId: String? = null, graphics: Boolean = false,
        immersive: Boolean = false): ToolRecord {
        val json = JSONObject("""{"formatVersion":3,"title":"$title","description":"","capabilities":[],
            "stateSchema":{"note":"string","level":"number"},"initialState":{"note":"","level":1},
            "ui":{"type":"column","children":[
                {"type":"input","text":"$title note","bind":"note","action":"note","inputMode":"live","maxLines":1},
                {"type":"slider","text":"$title level","bind":"level","action":"level","inputMode":"live","min":1,"max":5,"step":1}]},
            "source":"if(input.event.type==='liveInput'){if(input.event.value==='blocked effect')return {state:input.state,effects:[]};input.state[input.event.actionId]=input.event.value;}return {state:input.state};"}""")
        if (graphics) json.put("surfaces", JSONArray("""[{"id":"board","width":64,"height":64}]"""))
        if (immersive) {
            json.put("presentation", JSONObject().put("mode", "immersive"))
            json.getJSONObject("stateSchema").put("movementNote", "string")
            json.getJSONObject("initialState").put("movementNote", "")
            json.put("source", "if(input.event.type==='control')input.state.movementNote=input.state.note;${json.getString("source")}")
        }
        val spec = ToolSpec.parse(json.toString())
        return ToolRecord(id, 1, 1, spec.json, spec.initialState, spec.ui.toString(), conversationId, true).also { store.saveTool(it, true) }
    }

    private inline fun withFixture(block: (ShellFixture, ToolStore) -> Unit) {
        val fixture = ShellFixture("live-input-host")
        val store = ToolStore(fixture.context, "${fixture.store.databaseName}-tools.db")
        try {
            block(fixture, store)
        } finally {
            store.close()
            fixture.remove()
        }
    }

    private class GatedHost(private val initial: ArtifactSnapshot) : LiveArtifactUiHost {
        val values = CopyOnWriteArrayList<Any?>()
        val frames = AtomicInteger()
        val opens = AtomicInteger()
        val cancels = AtomicInteger()
        private val receipts = CopyOnWriteArrayList<CompletableDeferred<LiveInputResult>>()
        @Volatile var guard: (() -> Boolean)? = null

        override suspend fun open() = initial.also { opens.incrementAndGet() }
        override suspend fun startSession() = initial
        override suspend fun snapshot() = initial
        override suspend fun flush() = initial
        override suspend fun event(event: JSONObject) = error("The live adapter must use its own channel.")
        override suspend fun close() = Unit
        override suspend fun offerLiveInput(expected: ArtifactSnapshot, actionId: String, value: Any?, isActive: () -> Boolean) = hold(value, isActive)
        override suspend fun finishLiveInput(expected: ArtifactSnapshot, actionId: String, value: Any?, isActive: () -> Boolean) = hold(value, isActive)
        override fun liveInputFrame(frameTimeNanos: Long) { frames.incrementAndGet() }
        override fun cancelLiveInput() {
            cancels.incrementAndGet()
            receipts.forEach { it.complete(LiveInputResult.Rejected(LiveInputRejection.Inactive)) }
        }
        private fun hold(value: Any?, isActive: () -> Boolean): LiveInputSubmission {
            guard = isActive
            val receipt = CompletableDeferred<LiveInputResult>()
            receipts += receipt
            values += value
            return LiveInputSubmission.Pending(receipt)
        }
    }
}
