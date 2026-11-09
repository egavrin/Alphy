package com.alpha.assistant.tools

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.seedOpenChat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCapabilityUiWorkflowTest {
    private val robot = ShellRobot("device-capability")

    @Test fun shareOnlyAppReviewsExactTextBeforeOpeningAndroidChooser() {
        val spec = """
            {"formatVersion":3,"title":"Share fixture","description":"","capabilities":["share.text"],
            "stateSchema":{"count":"number"},"initialState":{"count":0},
            "ui":{"type":"button","text":"Review share text","action":"go"},
            "source":"return {state:input.state,effects:input.event.type==='action'?[{id:'share',capability:'share.text',args:{text:'Alphy exact fixture text'}}]:[]};"}
        """.trimIndent()
        withApp(spec) { tools, record ->
            robot.click("Review share text")
            robot.waitFor("Alphy exact fixture text")
            robot.click(robot.text(R.string.cancel))
            robot.waitUntil { tools.effects(record.id).singleOrNull()?.result?.status == "cancelled" }
            robot.click("Review share text")
            robot.waitFor("Alphy exact fixture text")
            robot.click(robot.text(R.string.tool_share_confirm))
            robot.waitUntil { tools.effects(record.id).any { it.result.status == "launched" } }
            robot.back()
        }
    }

    @Test fun tiltChangesTheSavedGameStateAndNativeStopEndsDelivery() {
        val spec = """
            {"formatVersion":3,"title":"Tilt fixture","description":"","capabilities":["sensor.accelerometer.stream"],
            "stateSchema":{"count":"number"},"initialState":{"count":0},
            "surfaces":[{"id":"board","width":300,"height":300}],
            "ui":{"type":"column","children":[{"type":"button","text":"Start tilt","action":"go"},{"type":"text","text":"Samples","bind":"count"}]},
            "source":"const s=input.state,e=input.event;if(e.type==='action')return {state:s,effects:[{id:'tilt',capability:'sensor.accelerometer.stream',args:{enabled:true}}]};if(e.type==='sensor' && Math.abs(e.x)>1)s.count++;return {state:s,surfaces:{board:{commands:[{type:'text',x:30,y:30,text:String(s.count),size:20,color:'default'}]}}};"}
        """.trimIndent()
        withApp(spec) { tools, record ->
            robot.click("Start tilt")
            robot.waitFor(robot.text(R.string.tool_stop_tilt))
            fun count() = JSONObject(checkNotNull(tools.tool(record.id)).state).getInt("count")
            robot.waitUntil { count() > 0 }
            robot.click(robot.text(R.string.tool_stop_tilt))
            robot.waitUntil { robot.find(robot.text(R.string.tool_stop_tilt)) == null }
            SystemClock.sleep(450)
            val stopped = count()
            SystemClock.sleep(450)
            assertEquals(stopped, count())
            assertNull(robot.find(robot.text(R.string.tool_stop_tilt)))
        }
    }

    private val photoSpec = """
            {"formatVersion":3,"title":"Photo fixture","description":"","capabilities":["camera.capture"],
            "capabilityResults":true,"stateSchema":{"photo":"string"},"initialState":{"photo":""},
            "ui":{"type":"button","text":"Take fixture photo","action":"capture"},
            "source":"const e=input.event;if(e.type==='action')return {state:input.state,effects:[{id:'capture',capability:'camera.capture',args:{}}]};if(e.type==='capabilityResults'){const photo=e.entries.find(r=>r.capability==='camera.capture'&&r.status==='succeeded');if(photo)return {state:{photo:photo.data.resourceId}};}return {state:input.state};"}
        """.trimIndent()
    @Test fun nativePhotoDefaultsToRearAndCommitsOnlyAfterKeep() {
        withApp(photoSpec) { tools, record ->
            try {
                robot.click("Take fixture photo")
                robot.waitFor("Задняя камера")
                robot.cameraEvidence("mini-app-rear-preview", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
                robot.click("Сделать снимок")
                robot.waitFor(robot.text(R.string.tool_camera_keep))
                robot.back()
                robot.waitUntil { tools.effects(record.id).singleOrNull()?.result?.status == "cancelled" }
                assertEquals("", JSONObject(checkNotNull(tools.tool(record.id)).state).getString("photo"))
                robot.click("Take fixture photo")
                robot.waitFor("Задняя камера")
                robot.click("Сделать снимок")
                robot.click(robot.text(R.string.tool_camera_keep))
                robot.waitUntil { JSONObject(checkNotNull(tools.tool(record.id)).state).getString("photo").isNotEmpty() }
                val result = tools.effects(record.id).single { it.result.status == "succeeded" }.result
                val id = result.data.getString("resourceId")
                val resources = com.alpha.assistant.capabilities.CapabilityResources(robot.instrumentation.targetContext, tools)
                val saved = resources.resolve(record.id, id)
                assertEquals("cameraPhoto", saved.kind)
                check(saved.sizeBytes in 1..10L * 1024 * 1024)
                check(runCatching { resources.resolve("another-mini-app", id) }.isFailure)
            } finally {
                com.alpha.assistant.capabilities.CapabilityResources(robot.instrumentation.targetContext, tools).releaseOwner(record.id)
            }
        }
    }

    @Test fun nativePhotoCannotCommitAfterOwnerIsArchived() = withApp(photoSpec) { tools, record ->
        robot.click("Take fixture photo")
        robot.click("Сделать снимок")
        robot.waitFor(robot.text(R.string.tool_camera_keep))
        tools.setArchived(checkNotNull(tools.tool(record.id)), true)
        if (robot.find(robot.text(R.string.tool_camera_keep)) != null) robot.click(robot.text(R.string.tool_camera_keep))
        robot.waitGone(robot.text(R.string.tool_camera_keep))
        assertEquals("cancelled", tools.effects(record.id).single().result.status)
        assertEquals("", JSONObject(checkNotNull(tools.tool(record.id)).state).getString("photo"))
    }

    @Test fun nativePhotoDoesNotOpenWithoutCameraPermission() = withApp(photoSpec) { tools, record ->
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            robot.instrumentation.targetContext.checkSelfPermission(android.Manifest.permission.CAMERA))
        robot.click("Take fixture photo")
        robot.waitUntil { tools.effects(record.id).singleOrNull()?.result?.status == "denied" }
        assertNull(robot.find("Сделать снимок"))
    }

    @Test fun androidCameraReturnsAPlayablePrivateCapture() {
        val spec = """
            {"formatVersion":3,"title":"Capture fixture","description":"","capabilities":["video.capture","video.play"],
            "capabilityResults":true,"stateSchema":{"clip":"string"},"initialState":{"clip":""},
            "ui":{"type":"column","children":[{"type":"button","text":"Record short clip","action":"capture"},{"type":"video","text":"Recorded clip","bind":"clip","action":"play"}]},
            "source":"const e=input.event;if(e.type==='action'&&e.actionId==='capture')return {state:input.state,effects:[{id:'capture',capability:'video.capture',args:{}}]};if(e.type==='action'&&e.video)return {state:input.state,effects:[{id:'play',capability:'video.play',args:e.video}]};if(e.type==='capabilityResults'){const clip=e.entries.find(r=>r.capability==='video.capture'&&r.status==='succeeded');if(clip)return {state:{clip:clip.data.resourceId}};}return {state:input.state};"}
        """.trimIndent()
        withApp(spec) { tools, record ->
            try {
                robot.click("Record short clip")
                val camera = "com.android.camera2"
                robot.waitUntil { robot.diagnostics.freshRoot()?.packageName?.toString() == camera }
                fun control(vararg names: String) {
                    var node: android.view.accessibility.AccessibilityNodeInfo? = null
                    robot.waitUntil {
                        val root = robot.diagnostics.freshRoot()
                        node = names.asSequence().flatMap { name ->
                            root?.findAccessibilityNodeInfosByViewId("$camera:id/$name").orEmpty().asSequence()
                        }.firstOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
                        node != null
                    }
                    check(checkNotNull(node).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                }
                robot.find("Next")?.takeIf { it.isClickable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                control("shutter_button")
                SystemClock.sleep(2300)
                control("shutter_button")
                control("btn_done", "done_button", "review_done", "done")
                robot.waitUntil { JSONObject(checkNotNull(tools.tool(record.id)).state).optString("clip").isNotBlank() }
                robot.click(robot.text(R.string.tool_video_play, "Recorded clip"))
                robot.waitUntil { tools.effects(record.id).any { it.request.capability == "video.play" && it.result.status == "succeeded" } }
            } finally {
                com.alpha.assistant.capabilities.CapabilityResources(robot.instrumentation.targetContext, tools).releaseOwner(record.id)
            }
        }
    }

    private fun withApp(raw: String, test: (ToolStore, ToolRecord) -> Unit) {
        val fixture = ShellFixture("device-capability")
        try {
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { tools ->
                val spec = ToolSpec.parse(raw)
                val record = ToolRecord("device-fixture", 1, 1, spec.json, spec.initialState, spec.ui.toString(), "device-chat", true)
                tools.saveTool(record, true)
                fixture.store.saveConversation(Conversation("device-chat", "Device fixture", 1, 1, messages = listOf(
                    Message("device-artifact", "assistant", "", 1, "complete", toolId = record.id, toolRevision = 1))))
                fixture.store.seedOpenChat("device-chat")
                fixture.install(saveKey = false)
                fixture.launch().use {
                    robot.click(robot.text(R.string.tool_open))
                    try { test(tools, record) }
                    catch (failure: AssertionError) {
                        throw AssertionError("Device workflow failed. Effects=${tools.effects(record.id)}. " +
                            "Controller=${fixture.model?.tools?.errorCode} ${fixture.model?.tools?.error}", failure)
                    }
                }
            }
        } finally { fixture.remove() }
    }
}
