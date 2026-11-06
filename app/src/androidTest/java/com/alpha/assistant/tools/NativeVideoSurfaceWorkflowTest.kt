package com.alpha.assistant.tools

import android.graphics.SurfaceTexture
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVideoSurfaceWorkflowTest {
    private val robot = ShellRobot("native-video-surface")

    @Test fun videoActionsRequireTheActiveBoundResourceAndUseAStaticWidgetProjection() {
        val clip = "a".repeat(64)
        val root = JSONObject("""{"type":"pager","text":"Клипы","bind":"page","action":"changePage","children":[
            {"type":"video","text":"Первый клип","bind":"clip","action":"playFirst","enabledBind":"allowed"},
            {"type":"video","text":"Второй клип","bind":"clip","action":"playSecond"}]}""")
        val fields = JSONObject("""{"page":"number","clip":"string","allowed":"boolean"}""")
        val state = JSONObject().put("page", 0).put("clip", clip).put("allowed", true)
        ToolUiCatalog.validate(root, fields, state)
        val first = ToolUiCatalog.validateVideoAction(root, "playFirst", clip, state)
        assertEquals("video", first.getString("type"))
        assertTrue(runCatching { ToolUiCatalog.validateAction(root, "playFirst", clip, state) }.isFailure)
        assertTrue(runCatching { ToolUiCatalog.validateLiveInput(root, "playFirst", clip, state) }.isFailure)
        assertTrue(runCatching { ToolUiCatalog.validateVideoAction(root, "playSecond", clip, state) }.isFailure)
        assertTrue(runCatching { ToolUiCatalog.validateVideoAction(root, "playFirst", "b".repeat(64), state) }.isFailure)
        state.put("allowed", false)
        assertTrue(runCatching { ToolUiCatalog.validateVideoAction(root, "playFirst", clip, state) }.isFailure)
        state.put("allowed", true)
        first.put("visible", false)
        assertTrue(runCatching { ToolUiCatalog.validateVideoAction(root, "playFirst", clip, state) }.isFailure)
        first.remove("visible")
        state.put("page", 1)
        assertEquals("playSecond", ToolUiCatalog.validateVideoAction(root, "playSecond", clip, state).getString("action"))

        val projection = ToolUiCatalog.widgetNodes(first, state).single()
        assertEquals("Первый клип", projection.getString("text"))
        assertFalse(projection.getBoolean("enabled"))
        assertFalse(projection.has("action"))
        assertFalse(projection.has("bind"))

        for (malformed in listOf("content://provider/clip.mp4", "/tmp/clip.mp4", "A".repeat(64), 7)) {
            state.put("clip", malformed)
            assertTrue(runCatching { ToolUiCatalog.validate(root, fields, state) }.isFailure)
        }
        state.put("clip", "")
        ToolUiCatalog.validate(root, fields, state)
        assertTrue(runCatching { ToolUiCatalog.validateVideoAction(root, "playSecond", "", state) }.isFailure)
        first.put("url", "https://example.org/clip.mp4")
        assertTrue(runCatching { ToolUiCatalog.validate(root, fields, state) }.isFailure)
    }

    @Test fun independentNodesPassTheExactLeaseAndReplacingABindingRevokesOnlyItsSurface() = withActivity { scenario ->
        val firstClip = "a".repeat(64)
        val secondClip = "b".repeat(64)
        val replacement = "c".repeat(64)
        val host = NodeHost()
        val clip = mutableStateOf(firstClip)
        val interactive = mutableStateOf(true)
        val ordinaryEvents = AtomicInteger()
        val ui = """{"type":"column","children":[
            {"type":"video","text":"Первый клип","bind":"first","action":"playFirst"},
            {"type":"video","text":"Второй клип","bind":"second","action":"playSecond"}]}"""
        scenario.onActivity { activity ->
            activity.setContent {
                AlphaTheme {
                    Box(Modifier.safeDrawingPadding().width(216.dp)) {
                        val state = JSONObject().put("first", clip.value).put("second", secondClip).toString()
                        ToolContent(ui, state, enabled = interactive.value, videoHost = host) { _, _ ->
                            ordinaryEvents.incrementAndGet()
                        }
                    }
                }
            }
        }
        val firstPlay = robot.text(R.string.tool_video_play, "Первый клип")
        val secondPlay = robot.text(R.string.tool_video_play, "Второй клип")
        robot.waitUntil { host.available.size == 2 }
        val first = host.available.single { it.first.actionId == "playFirst" }
        val second = host.available.single { it.first.actionId == "playSecond" }
        assertNotSame(first.second, second.second)
        robot.assertTarget(firstPlay)
        robot.assertTarget(secondPlay)
        robot.click(secondPlay)
        assertEquals(listOf(second), host.plays.toList())
        assertEquals(0, ordinaryEvents.get())

        scenario.onActivity { clip.value = replacement }
        robot.waitUntil { host.available.size == 3 && host.lost.size == 1 }
        assertEquals(listOf(first), host.lost.toList())
        assertReleased(first.second)
        assertTrue(second.second.isCurrent)
        val rebound = host.available.last()
        assertEquals(replacement, rebound.first.resourceId)
        assertNotSame(first.first, rebound.first)
        robot.click(firstPlay)
        assertEquals(listOf(second, rebound), host.plays.toList())

        scenario.onActivity { interactive.value = false }
        robot.waitGone(firstPlay)
        robot.waitGone(secondPlay)
        assertTrue(robot.waitFor("Первый клип").isVisibleToUser)
        robot.waitUntil { host.lost.size == 3 }
        assertReleased(second.second)
        assertReleased(rebound.second)
        assertEquals(3, host.available.size)
        assertEquals(0, ordinaryEvents.get())
    }

    @Test fun replacingAProducerAndZeroSizeCannotReviveAStaleSurface() {
        val host = Host()
        robot.instrumentation.runOnMainSync {
            val boundary = NativeVideoSurfaceBinding(host)
            val firstView = Any()
            val nextView = Any()
            val firstTexture = SurfaceTexture(false)
            val nextTexture = SurfaceTexture(false)
            try {
                boundary.setActive(true)
                boundary.attach(firstView, hardwareAccelerated = true)
                boundary.present(firstView, firstTexture, 120, 80)
                val first = host.available.single()
                boundary.resize(firstView, firstTexture, 200, 100)
                assertSame(first, host.available.single())
                boundary.resize(firstView, firstTexture, 0, 100)
                assertReleased(first)

                boundary.attach(nextView, hardwareAccelerated = true)
                boundary.present(nextView, nextTexture, 120, 80)
                val next = host.available.last()
                assertNotSame(first, next)
                boundary.present(firstView, firstTexture, 120, 80)
                boundary.resize(nextView, firstTexture, 0, 0)
                boundary.destroy(firstView, firstTexture)
                boundary.detach(firstView)
                assertTrue(next.isCurrent)
                assertEquals(2, host.available.size)
                assertEquals(listOf(first), host.lost.toList())

                boundary.setActive(false)
                assertReleased(next)
                boundary.resize(nextView, nextTexture, 160, 100)
                assertEquals(2, host.available.size)
                boundary.setActive(true)
                val resumed = host.available.last()
                assertNotSame(next, resumed)
                boundary.detach(nextView)
                assertReleased(resumed)
                assertEquals(listOf(first, next, resumed), host.lost.toList())
            } finally {
                boundary.setActive(false)
                firstTexture.release()
                nextTexture.release()
            }
        }
    }

    @Test fun resizePauseAndRecreateKeepOverlaysUsableAndRevokeTheOldLease() = withActivity { scenario ->
        val host = Host()
        val mounted = mutableStateOf(true)
        val width = mutableStateOf(160)
        val status = mutableStateOf(NativeVideoStatus.Preparing)
        val taps = AtomicInteger()
        fun mount() = scenario.onActivity { activity ->
            activity.setContent {
                AlphaTheme {
                    Box(Modifier.safeDrawingPadding()) {
                        if (mounted.value) NativeVideoSurface(host, active = true, status = status.value,
                            modifier = Modifier.width(width.value.dp).height(112.dp)) {
                            TextButton(onClick = { taps.incrementAndGet() }, modifier = Modifier.align(Alignment.BottomEnd).height(48.dp)) {
                                Text("Параметры")
                            }
                        }
                    }
                }
            }
        }
        mount()
        robot.waitUntil { host.available.size == 1 }
        val first = host.available.single()
        scenario.onActivity { status.value = NativeVideoStatus.Playing }
        robot.waitFor(robot.text(R.string.tool_video_playing))
        val before = robot.assertTarget("Параметры")
        robot.shell("input tap ${before.centerX()} ${before.centerY()}")
        robot.waitUntil { taps.get() == 1 }
        scenario.onActivity { width.value = 200 }
        robot.waitUntil { robot.bounds("Параметры").right > before.right }
        assertSame(first, host.available.single())

        scenario.moveToState(Lifecycle.State.STARTED)
        assertReleased(first)
        assertEquals(listOf(first), host.lost.toList())
        scenario.moveToState(Lifecycle.State.RESUMED)
        robot.waitUntil { host.available.size == 2 }
        val resumed = host.available.last()
        assertNotSame(first, resumed)
        robot.click("Параметры")
        assertEquals(2, taps.get())

        scenario.recreate()
        assertReleased(resumed)
        mount()
        robot.waitUntil { host.available.size == 3 }
        val replacement = host.available.last()
        assertNotSame(resumed, replacement)
        scenario.onActivity { mounted.value = false }
        robot.waitGone("Параметры")
        assertReleased(replacement)
        assertEquals(listOf(first, resumed, replacement), host.lost.toList())
    }

    @Test fun errorAndMissingHostHaveAccessibleStatus() = withActivity { scenario ->
        val host = Host()
        val attached = mutableStateOf<NativeVideoSurfaceHost?>(host)
        val status = mutableStateOf(NativeVideoStatus.Failed)
        scenario.onActivity { activity ->
            activity.setContent {
                AlphaTheme {
                    Box(Modifier.safeDrawingPadding()) {
                        NativeVideoSurface(attached.value, active = true, status = status.value,
                            modifier = Modifier.width(200.dp).height(112.dp))
                    }
                }
            }
        }
        assertTrue(robot.waitFor(robot.text(R.string.tool_video_failed)).isVisibleToUser)
        robot.waitUntil { host.available.size == 1 }
        val lease = host.available.single()
        scenario.onActivity { status.value = NativeVideoStatus.Playing }
        assertTrue(robot.waitFor(robot.text(R.string.tool_video_playing)).isVisibleToUser)
        scenario.onActivity { attached.value = null }
        assertTrue(robot.waitFor(robot.text(R.string.tool_video_unavailable)).isVisibleToUser)
        robot.waitGone(robot.text(R.string.tool_video_playing))
        assertReleased(lease)
        assertEquals(listOf(lease), host.lost.toList())
    }

    private fun withActivity(block: (ActivityScenario<CloudTestActivity>) -> Unit) {
        val fixture = ShellFixture("video-surface")
        try {
            fixture.install()
            fixture.launch().use(block)
        } finally { fixture.remove() }
    }

    private fun assertReleased(lease: NativeVideoSurfaceLease) {
        assertFalse(lease.isCurrent)
        assertFalse(lease.surface.isValid)
    }

    private class Host : NativeVideoSurfaceHost {
        val available = CopyOnWriteArrayList<NativeVideoSurfaceLease>()
        val lost = CopyOnWriteArrayList<NativeVideoSurfaceLease>()

        override fun onSurfaceAvailable(lease: NativeVideoSurfaceLease) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            assertTrue(lease.isCurrent)
            available += lease
        }

        override fun onSurfaceLost(lease: NativeVideoSurfaceLease) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            assertFalse(lease.isCurrent)
            assertTrue("Host can detach while the borrowed Surface still exists", lease.surface.isValid)
            lost += lease
        }
    }

    private class NodeHost : NativeVideoUiHost {
        val available = CopyOnWriteArrayList<Pair<NativeVideoControl, NativeVideoSurfaceLease>>()
        val lost = CopyOnWriteArrayList<Pair<NativeVideoControl, NativeVideoSurfaceLease>>()
        val plays = CopyOnWriteArrayList<Pair<NativeVideoControl, NativeVideoSurfaceLease>>()

        override fun onSurfaceAvailable(control: NativeVideoControl, lease: NativeVideoSurfaceLease) {
            assertTrue(lease.isCurrent)
            available += control to lease
        }

        override fun onSurfaceLost(control: NativeVideoControl, lease: NativeVideoSurfaceLease) {
            assertFalse(lease.isCurrent)
            assertTrue(lease.surface.isValid)
            lost += control to lease
        }

        override fun play(control: NativeVideoControl, lease: NativeVideoSurfaceLease) {
            assertTrue(lease.isCurrent)
            assertTrue(control to lease in available)
            assertFalse(control to lease in lost)
            plays += control to lease
        }
    }
}
