package com.alpha.assistant.media

import com.alpha.assistant.ui.seedOpenChat
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RealVideoPickerWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val robot = ShellRobot("real-video-picker")

    @Test fun selectedVideoDecodesAndDoesNotReplayAfterSurfaceLoss() = verifySelectedVideo("video.pick")

    @Test fun selectedFileReachesOnlyItsOwningMiniApp() = verifySelectedVideo("file.pick")

    private fun verifySelectedVideo(picker: String) {
        assertFalse(com.alpha.assistant.capabilities.CapabilityCatalog.phoneGrants.contains("file.pick"))

        val fixture = ShellFixture("real-video-picker")
        val displayName = "${fixture.name}.mp4"
        val clip = instrumentation.context.assets.open("media/video-e2e-rgb.mp4").use { it.readBytes() }
        val evidence = File(checkNotNull(context.getExternalFilesDir(null)), "video-e2e/${fixture.name}")
        check(evidence.mkdirs())
        val report = JSONObject()
            .put("revision", InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied"))
            .put("targetPackage", context.packageName)
            .put("fixtureName", displayName)
            .put("fixtureBytes", clip.size)
            .put("fixtureSha256", MessageDigest.getInstance("SHA-256").digest(clip).joinToString("") { "%02x".format(it) })
            .put("outcome", "failed")
        val originalGrants = resolver.persistedUriPermissions.map { it.uri }.toSet()
        var media: Uri? = null
        var observationsPassed = false
        try {
            val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val row = checkNotNull(resolver.insert(videos, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }))
            media = row
            checkNotNull(resolver.openOutputStream(row)).use { it.write(clip) }
            assertEquals(1, resolver.update(row, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null))

            ToolStore(context, "${fixture.name}.db-tools.db").use { tools ->
                try {
                    seed(fixture, tools, picker)
                    fixture.install(saveKey = false)
                    fixture.launch().use { scenario ->
                        robot.rotate(scenario, landscape = false)
                        openSavedTool()
                        robot.reveal(PICK)
                        robot.click(PICK)
                        report.put("pickerPackage", chooseDocument(displayName))
                        robot.waitUntil { tools.resources(TOOL).size == 1 }
                        val selection = tools.resources(TOOL).single()
                        assertEquals(if (picker == "file.pick") "document" else "video", selection.kind)
                        assertEquals("video/mp4", selection.mimeType)
                        assertEquals(clip.size.toLong(), selection.sizeBytes)
                        val selectedUri = Uri.parse(selection.locator)
                        val selectedName = resolver.query(selectedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        assertEquals(displayName, selectedName)
                        assertEquals("succeeded", tools.effects(TOOL).single { it.request.capability == picker }.result.status)
                        robot.waitUntil { tools.tool(TOOL)?.let { JSONObject(it.state).optString("clip") == selection.id } == true }
                        report.put("pickedExactFixture", true)

                        play(scenario)
                        val firstTexture = awaitFrame(scenario, Frame.RED, evidence, "first-red.png")
                        assertTrue(firstTexture === awaitFrame(scenario, Frame.GREEN, evidence, "first-green.png"))
                        report.put("firstDecoderFrames", "red, green on the same native texture")
                        scenario.moveToState(Lifecycle.State.CREATED)
                        awaitCancelled(tools, attempts = 1)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        assertNoReplay(scenario, tools, attempts = 1, freshTexture = false)
                        report.put("background", "cancelled, ready after resume, no new playback")

                        play(scenario)
                        val secondTexture = awaitFrame(scenario, Frame.RED, evidence, "second-red.png")
                        scenario.recreate()
                        awaitCancelled(tools, attempts = 2)
                        robot.reveal(robot.text(R.string.tool_video_ready))
                        val replacement = checkNotNull(texture(scenario))
                        assertNotSame("Activity recreation must replace the native texture", secondTexture, replacement)
                        scenario.onActivity { assertFalse(secondTexture.isAttachedToWindow) }
                        assertNoReplay(scenario, tools, attempts = 2, freshTexture = true)
                        report.put("surfaceReplacement", "cancelled, new idle texture, no new playback")
                        assertEquals(0, fixture.provider.calls)
                        report.put("providerCalls", 0)
                    }
                } finally {
                    val selected = (tools.resources(TOOL) + tools.acquisitions().filter { it.owner == TOOL })
                        .map { Uri.parse(it.locator) }.toSet()
                    resolver.persistedUriPermissions.filter { it.isReadPermission && it.uri in selected && it.uri !in originalGrants }
                        .forEach { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    assertTrue("Fixture grants must be released", resolver.persistedUriPermissions.none {
                        it.isReadPermission && it.uri in selected && it.uri !in originalGrants
                    })
                    report.put("fixtureGrantsReleased", true)
                }
            }
            observationsPassed = true
        } finally {
            try {
                try {
                    media?.let { assertEquals(1, resolver.delete(it, null, null)) }
                } finally {
                    fixture.remove()
                    for (suffix in listOf(".db", ".db-tools.db", ".db-tools.db-automations.db")) {
                        assertFalse("Fixture database must be removed: $suffix", context.getDatabasePath(fixture.name + suffix).exists())
                    }
                }
                report.put("fixtureMediaAndDatabasesRemoved", true)
                if (observationsPassed) report.put("outcome", "passed")
            } finally {
                File(evidence, "report.json").writeText(report.toString(2))
                println("VIDEO_E2E evidence=${evidence.absolutePath} outcome=${report.getString("outcome")}")
            }
        }
    }

    private fun seed(fixture: ShellFixture, tools: ToolStore, picker: String) {
        val source = """
            const event = input.event;
            if (event.type === 'action' && event.actionId === 'pick') {
                return {state:input.state,effects:[{id:'pickClip',capability:'$picker',args:${if (picker == "file.pick") "{mime:'video/*'}" else "{}"}}]};
            }
            if (event.type === 'action' && event.actionId === 'play' && event.video) {
                return {state:input.state,effects:[{id:'playClip',capability:'video.play',args:event.video}]};
            }
            if (event.type === 'capabilityResults') {
                const selected = event.entries.find(entry => entry.capability === '$picker' && entry.status === 'succeeded');
                if (selected) return {state:{clip:selected.data.resourceId}};
            }
            return {state:input.state};
        """.trimIndent()
        val spec = ToolSpec.parse(JSONObject("""
            {"formatVersion":3,"title":"Проверка видео","description":"","capabilities":["$picker","video.play"],
             "capabilityResults":true,"stateSchema":{"clip":"string"},"initialState":{"clip":""},
             "ui":{"type":"column","children":[{"type":"button","text":"$PICK","action":"pick"},
                     {"type":"video","text":"$CLIP","bind":"clip","action":"play"}]}}
        """.trimIndent()).put("source", source).toString())
        tools.saveTool(ToolRecord(TOOL, 1, 1, spec.json, spec.initialState, spec.ui.toString(), "video-chat", true), true)
        fixture.store.saveConversation(Conversation("video-chat", "Проверка видео", 1, 1, messages = listOf(
            Message("video-artifact", "assistant", "", 1, "complete", toolId = TOOL, toolRevision = 1),
        )))
        fixture.store.seedOpenChat("video-chat")
    }

    private fun openSavedTool() {
        robot.click(robot.text(R.string.tool_open))
        robot.waitFor(robot.text(R.string.tool_content))
    }

    private fun chooseDocument(name: String): String {
        var picker = ""
        robot.waitUntil {
            picker = robot.diagnostics.freshRoot()?.packageName?.toString().orEmpty()
            picker in PICKER_PACKAGES
        }
        fun target(labels: Set<String>): AccessibilityNodeInfo? {
            val root = robot.diagnostics.freshRoot()?.takeIf { it.packageName?.toString() == picker } ?: return null
            fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isVisibleToUser && (node.text?.toString() in labels || node.contentDescription?.toString() in labels)) {
                    generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }?.let { return it }
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let(::find)?.let { return it } }
                return null
            }
            return find(root)
        }
        fun labels(resource: String, vararg defaults: String): Set<String> {
            val localized = runCatching {
                val resources = context.packageManager.getResourcesForApplication(picker)
                val id = resources.getIdentifier(resource, "string", picker)
                if (id == 0) null else resources.getString(id)
            }.getOrNull()
            return (defaults.toList() + listOfNotNull(localized)).toSet()
        }
        fun click(node: AccessibilityNodeInfo) = assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))

        val exact = setOf(name)
        if (target(exact) == null) {
            val recent = labels("root_recent", "Recent", "Недавние")
            val drawer = target(labels("drawer_open", "Show roots", "Показать корневые папки"))
            if (drawer != null) {
                click(drawer)
                var entry: AccessibilityNodeInfo? = null
                robot.waitUntil { (target(recent).also { entry = it }) != null }
                click(checkNotNull(entry))
            } else {
                target(recent)?.let(::click)
            }
        }
        var file: AccessibilityNodeInfo? = null
        robot.waitUntil { (target(exact).also { file = it }) != null }
        click(checkNotNull(file))
        robot.waitUntil { robot.diagnostics.freshRoot()?.packageName?.toString() == context.packageName }
        return picker
    }

    private fun play(scenario: ActivityScenario<CloudTestActivity>) {
        robot.reveal(robot.text(R.string.tool_video_ready))
        val play = robot.text(R.string.tool_video_play, CLIP)
        robot.reveal(play)
        robot.click(play)
        robot.waitFor(robot.text(R.string.tool_video_playing))
        assertTrue("Playback must have a visible native texture", texture(scenario) != null)
    }

    private fun awaitCancelled(tools: ToolStore, attempts: Int) {
        robot.waitUntil { playbackStatuses(tools).let { it.size == attempts && it.all { status -> status == "cancelled" } } }
    }

    private fun playbackStatuses(tools: ToolStore): List<String> = tools.effects(TOOL)
        .filter { it.request.capability == "video.play" }.map { it.result.status }

    private fun assertNoReplay(scenario: ActivityScenario<CloudTestActivity>, tools: ToolStore, attempts: Int, freshTexture: Boolean) {
        robot.reveal(robot.text(R.string.tool_video_ready))
        val initialFrame = sample(scenario)?.use { it.frame }
        val playing = robot.text(R.string.tool_video_playing)
        val until = SystemClock.elapsedRealtime() + 1_200
        while (SystemClock.elapsedRealtime() < until) {
            assertEquals(List(attempts) { "cancelled" }, playbackStatuses(tools))
            assertFalse("Playback appeared without another press", robot.find(playing)?.isVisibleToUser == true)
            val frame = sample(scenario)?.use { it.frame }
            assertTrue("The native texture advanced without another press", frame == null || !freshTexture && frame == initialFrame)
            SystemClock.sleep(75)
        }
    }

    private fun awaitFrame(scenario: ActivityScenario<CloudTestActivity>, expected: Frame, evidence: File, name: String): TextureView {
        var observed: TextureView? = null
        robot.waitUntil {
            sample(scenario)?.use { sample ->
                if (sample.frame == expected) {
                    File(evidence, name).outputStream().use { check(sample.image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    observed = sample.texture
                }
            }
            observed != null
        }
        return checkNotNull(observed)
    }

    private fun texture(scenario: ActivityScenario<CloudTestActivity>): TextureView? {
        var texture: TextureView? = null
        scenario.onActivity { texture = findTexture(it.window.decorView) }
        return texture
    }

    private fun sample(scenario: ActivityScenario<CloudTestActivity>): Sample? {
        var sample: Sample? = null
        scenario.onActivity { activity ->
            val texture = findTexture(activity.window.decorView)
            texture?.getBitmap(24, 14)?.let { sample = Sample(texture, it) }
        }
        return sample
    }

    private fun findTexture(view: View): TextureView? {
        if (view is TextureView && view.isAvailable && view.isShown) return view
        if (view is ViewGroup) repeat(view.childCount) { findTexture(view.getChildAt(it))?.let { found -> return found } }
        return null
    }

    private enum class Frame { RED, GREEN, BLUE }

    private class Sample(val texture: TextureView, val image: Bitmap) : AutoCloseable {
        val frame: Frame? get() {
            val pixel = image.getPixel(image.width / 2, image.height / 2)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            return when {
                red > 150 && red > maxOf(green, blue) + 70 -> Frame.RED
                green > 150 && green > maxOf(red, blue) + 70 -> Frame.GREEN
                blue > 150 && blue > maxOf(red, green) + 70 -> Frame.BLUE
                else -> null
            }
        }
        override fun close() = image.recycle()
    }

    private companion object {
        const val TOOL = "real-video"
        const val PICK = "Выбрать тестовое видео"
        const val CLIP = "Тестовый клип"
        val PICKER_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
    }
}
