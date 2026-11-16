package com.alpha.assistant.agent

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PhoneCameraWorkflowTest {
    private val robot = ShellRobot("phone-camera")
    private val shutter = "Сделать снимок"
    private val front = "Передняя камера"
    private val rear = "Задняя камера"
    private val savedFront = "Снимок · Передняя камера"
    private val continueCamera = "Продолжить к камере"

    @Test fun approvedSelfieStaysInChatOutsideProviderContext() {
        // An emulator can declare the front-camera feature without a front lens, so the check reads the lenses.
        val cameras = robot.instrumentation.targetContext.getSystemService(CameraManager::class.java)
        assumeTrue("This device has no front camera", cameras.cameraIdList.any {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        })
        val fixture = ShellFixture("phone-selfie")
        val chat = "photo-chat"
        try {
            fixture.seedChat(chat, "Photo chat", "Camera workflow", selected = true)
            fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence("Take a selfie.", "selfie"))
            fixture.provider.agentProposal = """{"steps":[{"function":"camera.capture","args":{"lens":"front"}}]}"""
            fixture.install()
            fixture.launch().use {
                send("Take a selfie.")
                robot.reveal(robot.text(R.string.agent_execute))
                assertNull(robot.find(shutter))
                robot.click(robot.text(R.string.agent_execute))
                robot.waitFor(front)
                robot.cameraEvidence("phone-front-preview", CameraCharacteristics.LENS_FACING_FRONT)
                robot.click(shutter)
                robot.waitFor(robot.text(R.string.tool_camera_keep))
                robot.click(robot.text(R.string.tool_camera_keep))
                robot.reveal(savedFront, forward = null)
                robot.cameraEvidence("phone-front-kept")
                assertEquals(2, fixture.provider.calls)
                assertTrue(checkNotNull(fixture.model).snapshot.draft.attachments.isEmpty())
                fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence("Open Android Settings.", "Android Settings"))
                fixture.provider.agentProposal = """{"steps":[{"function":"app.open","args":{"app":"settings"}}]}"""
                send("Open Android Settings.")
                robot.waitUntil { fixture.provider.calls == 4 }
                robot.reveal(savedFront, forward = null)
                assertTrue(checkNotNull(fixture.provider.lastRequest).turns.all { it.images.isEmpty() })
            }
            fixture.launch().use {
                robot.waitUntil { fixture.model?.agent?.keptPhotos(chat)?.size == 1 }
                robot.reveal(savedFront, forward = null)
                robot.click("Удалить снимок")
                robot.waitGone(savedFront)
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
    }

    @Test fun clockReturnsToOneExplicitContinueBeforeRearCapture() {
        val fixture = ShellFixture("phone-clock-camera")
        try {
            val request = timerThenPhoto(fixture)
            fixture.install()
            fixture.launch().use { scenario ->
                send(request)
                robot.reveal(robot.text(R.string.agent_execute))
                scenario.onActivity {
                    val plan = checkNotNull(fixture.model?.agent?.plan)
                    assertEquals(listOf("clock.setTimer", "camera.capture"), plan.steps.map { it.command.function })
                }
                runTimerStep(scenario)
                robot.reveal(continueCamera, forward = null)
                assertNull(robot.find(shutter))
                robot.cameraEvidence("clock-continue")
                val waiting = checkNotNull(fixture.model?.agent?.plan)
                robot.click(continueCamera)
                robot.waitFor(rear)
                assertNull(robot.find(continueCamera))
                scenario.onActivity { fixture.model?.agent?.execute(waiting) }
                robot.cameraEvidence("phone-rear-preview", CameraCharacteristics.LENS_FACING_BACK)
                robot.click(shutter)
                robot.click(robot.text(R.string.tool_camera_keep))
                robot.waitUntil {
                    val model = fixture.model ?: return@waitUntil false
                    val chat = model.snapshot.selectedId ?: return@waitUntil false
                    model.agent.keptPhotos(chat).size == 1
                }
                robot.awaitFrames(2)
                robot.reveal("Снимок · Задняя камера", forward = null)
                assertEquals(2, checkNotNull(fixture.model?.agent?.plan).results.size)
                scenario.onActivity { checkNotNull(fixture.model).deleteConversation(checkNotNull(fixture.model?.snapshot?.selectedId)) }
                assertEquals(2, fixture.provider.calls)
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
    }

    @Test fun cancelAfterClockReturnEndsThePlanWithoutOpeningCamera() {
        val fixture = ShellFixture("phone-clock-cancel")
        try {
            val request = timerThenPhoto(fixture)
            fixture.install()
            fixture.launch().use { scenario ->
                send(request)
                robot.reveal(robot.text(R.string.agent_execute))
                runTimerStep(scenario)
                robot.reveal(continueCamera, forward = null)
                val waiting = checkNotNull(fixture.model?.agent?.plan)
                robot.click(robot.text(R.string.cancel))
                robot.reveal(robot.text(R.string.agent_stopped), forward = null)
                scenario.onActivity { fixture.model?.agent?.execute(waiting) }
                assertNull(robot.find(shutter))
                assertNull(robot.find(continueCamera))
                assertEquals(1, checkNotNull(fixture.model?.agent?.plan).results.size)
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
    }

    private fun send(text: String) {
        robot.edit(robot.text(R.string.composer_label), text)
        robot.click(robot.text(R.string.composer_send))
    }

    /**
     * Approve the plan and complete its timer step. By default, the test blocks the timer request, so no real Clock
     * timer exists, and a pause and a resume stand in for the return from Clock. Pass `-e realClock true` on a test
     * emulator to use the real Clock app. Never use it on a personal phone.
     */
    private fun runTimerStep(scenario: ActivityScenario<*>) {
        if (InstrumentationRegistry.getArguments().getString("realClock") == "true") {
            val clock = checkNotNull(Intent(AlarmClock.ACTION_SET_TIMER)
                .resolveActivity(robot.instrumentation.targetContext.packageManager)).packageName
            robot.click(robot.text(R.string.agent_execute))
            robot.waitUntil { robot.diagnostics.freshRoot()?.packageName?.toString() == clock }
            robot.cameraEvidence("clock-handoff")
            robot.back()
            return
        }
        val timer = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != AlarmClock.ACTION_SET_TIMER) return null
                timer.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        robot.instrumentation.addMonitor(monitor)
        try {
            robot.click(robot.text(R.string.agent_execute))
            robot.waitUntil { monitor.hits == 1 }
        } finally {
            robot.instrumentation.removeMonitor(monitor)
        }
        assertEquals(300, timer.get().getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun timerThenPhoto(fixture: ShellFixture): String {
        fixture.provider.devicePlanSteps = listOf(
            PhoneActionEvidence("Set a five-minute timer", "five-minute"),
            PhoneActionEvidence("take a photo with the rear camera", "rear camera"))
        fixture.provider.agentProposal = """{"steps":[{"function":"clock.setTimer","args":{"seconds":300}},{"function":"camera.capture","args":{"lens":"rear"}}]}"""
        return "Set a five-minute timer and take a photo with the rear camera."
    }
}
