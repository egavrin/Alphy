package com.alpha.assistant.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AlarmSequenceWorkflowTest {
    @Test fun oneApprovalEnablesBothAlarmsInRealClock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("alarmSequenceClock") == "true")
        val evidence = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "alarm-sequence-qa")
        check(evidence.isDirectory || evidence.mkdirs())
        val robot = ShellRobot("alarm-sequence-clock")
        val fixture = ShellFixture("alarm-sequence-clock")
        try {
            robot.shell("am start -W -a ${AlarmClock.ACTION_SHOW_ALARMS}")
            robot.waitUntil { robot.diagnostics.freshRoot()?.packageName?.toString() == CLOCK }
            val before = clockSwitches(robot)
            assertTrue("Expected baseline Clock alarms, found $before", before.isNotEmpty())
            assertFalse("A target alarm was enabled before approval: $before",
                targetAlarms.any { time -> before.any { it.first == time && it.second } })
            capture(evidence, "clock-before", robot)

            fixture.provider.devicePlanSteps = listOf(
                PhoneActionEvidence(REQUEST, "7:00 AM"), PhoneActionEvidence(REQUEST, "9:00 AM"))
            fixture.provider.agentProposal = ALARMS
            fixture.install()
            fixture.launch().use { scenario ->
                robot.edit(robot.text(R.string.composer_label), REQUEST)
                robot.click(robot.text(R.string.composer_send))
                robot.reveal(robot.text(R.string.agent_execute))
                val agent = checkNotNull(fixture.model).agent
                scenario.onActivity {
                    val review = checkNotNull(agent.plan)
                    assertEquals(AgentPhase.REVIEW, review.phase)
                    assertEquals(listOf("07:00", "09:00"), review.steps.map { it.target?.after })
                }
                capture(evidence, "alphy-review", robot)
                robot.click(robot.text(R.string.agent_execute))

                robot.waitUntil(30_000) {
                    agent.plan?.phase in setOf(AgentPhase.COMPLETED, AgentPhase.STOPPED, AgentPhase.FAILED, AgentPhase.INTERRUPTED)
                }
                val finished = checkNotNull(agent.plan)
                File(evidence, "alphy-results.txt").writeText(
                    "Input: $REQUEST\nExecute taps: 1\nPhase: ${finished.phase}\nResults: ${finished.results}\n"
                )
                assertEquals(finished.results.toString(), AgentPhase.COMPLETED, finished.phase)
                assertEquals(List(2) { robot.text(R.string.agent_alarm_sent) }, finished.results)
                assertEquals(2, fixture.provider.calls)
                capture(evidence, "alphy-finished", robot)

                robot.shell("am start -W -a ${AlarmClock.ACTION_SHOW_ALARMS}")
                robot.waitUntil {
                    val rows = clockSwitches(robot)
                    targetAlarms.all { time -> rows.count { it.first == time && it.second } == 1 }
                }
                val after = clockSwitches(robot)
                capture(evidence, "clock-after", robot)
                File(evidence, "clock-switches.txt").writeText("Before: $before\nAfter: $after\n")
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
    }

    @Test fun failedSecondDispatchPreservesTheFirstResult(): Unit = runBlocking {
        withRuntime("alarm-dispatch-failure-${UUID.randomUUID()}") { agent ->
            val plan = agent.prepare("test-chat", REQUEST, ALARMS)
            val approved = checkNotNull(agent.approve(plan.id, plan.revision))
            assertNull(agent.approve(plan.id, plan.revision))
            val hours = mutableListOf<Int>()
            val finished = agent.execute(approved, { true }, { error("Alarm used ordinary handoff") }, launchAlarm = { intent ->
                assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
                hours += intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                if (hours.size == 2) throw ActivityNotFoundException("Clock unavailable")
            })
            assertEquals(listOf(7, 9), hours)
            assertEquals(AgentPhase.FAILED, finished.phase)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals(listOf(context.getString(R.string.agent_alarm_sent), context.getString(R.string.agent_alarm_no_handler)),
                finished.results)
            assertNull(agent.approve(plan.id, plan.revision))
        }
    }

    @Test fun stopWhileClockOwnsTheFirstStepSkipsTheSecond(): Unit = runBlocking {
        val fixture = ShellFixture("alarm-stop-race")
        try {
            fixture.install()
            fixture.launch().use { scenario ->
                withRuntime("alarm-dispatch-stop-${UUID.randomUUID()}") { agent ->
                    val plan = agent.prepare("test-chat", REQUEST, ALARMS)
                    val approved = checkNotNull(agent.approve(plan.id, plan.revision))
                    lateinit var alarms: ClockAlarmSequence
                    scenario.onActivity { alarms = ClockAlarmSequence(approved, it) }
                    val dispatched = CompletableDeferred<Unit>()
                    val returned = CompletableDeferred<Unit>()
                    val returnObserved = CompletableDeferred<Unit>()
                    val hours = mutableListOf<Int>()
                    val execution = async {
                        agent.execute(approved, { true }, { error("Alarm used ordinary handoff") }, launchAlarm = { intent ->
                            hours += intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                            withContext(Dispatchers.Main) {
                                dispatched.complete(Unit)
                                returned.await()
                                returnObserved.complete(Unit)
                                alarms.awaitStopRecorded()
                                error("Clock return after Stop")
                            }
                        })
                    }
                    dispatched.await()
                    withContext(Dispatchers.Main) {
                        assertTrue(alarms.requestStop())
                        returned.complete(Unit)
                    }
                    returnObserved.await()
                    val early = withTimeoutOrNull(100) { execution.await() }
                    agent.stop(plan.id)
                    withContext(Dispatchers.Main) { alarms.releaseWait() }
                    assertNull(early)
                    val finished = execution.await()
                    assertEquals(listOf(7), hours)
                    assertEquals(AgentPhase.STOPPED, finished.phase)
                    assertEquals(1, finished.results.size)
                    assertNull(agent.approve(plan.id, plan.revision))
                }
            }
        } finally { fixture.remove() }
    }

    @Test fun lateReturnCannotReleaseAnotherAlarmHandoff(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val codes = mutableListOf<Int>()
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                    input: I, options: ActivityOptionsCompat?) { codes += requestCode }
            }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                awaitClockAlarmReturn(registry, "first-alarm", intent, CompletableDeferred(), timeoutMs = 50)
            }
            assertFalse(first.await())
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                awaitClockAlarmReturn(registry, "second-alarm", intent, CompletableDeferred())
            }
            assertEquals(2, codes.size)
            registry.dispatchResult(codes[0], Activity.RESULT_OK, null)
            assertFalse(second.isCompleted)
            registry.dispatchResult(codes[1], Activity.RESULT_CANCELED, null)
            assertTrue(second.await())
        }
    }

    @Test fun inFlightAlarmIsNotReplayedInAnotherProcess(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("alarmRecoveryPhase")
        assumeTrue(phase in setOf("seed", "recover"))
        val token = checkNotNull(args.getString("alarmRecoveryId"))
        require(token.matches(Regex("[a-z0-9-]{1,48}")))
        withRuntime("alarm-recovery-$token", cleanup = phase == "recover") { agent ->
            if (phase == "seed") {
                val plan = agent.prepare("test-chat", REQUEST, ALARMS)
                val approved = checkNotNull(agent.approve(plan.id, plan.revision))
                val cut = AssertionError("Test process boundary")
                val hours = mutableListOf<Int>()
                val thrown = runCatching {
                    agent.execute(approved, { true }, { error("Alarm used ordinary handoff") }, launchAlarm = { intent ->
                        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
                        hours += intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                        if (hours.size == 2) throw cut
                    })
                }.exceptionOrNull()
                assertSame(cut, thrown)
                val pending = checkNotNull(agent.latest("test-chat"))
                assertEquals(listOf(7, 9), hours)
                assertEquals(AgentPhase.RUNNING, pending.phase)
                assertEquals(1, pending.results.size)
                assertEquals(1, pending.claimed)
            } else {
                val interrupted = checkNotNull(agent.latest("test-chat"))
                assertEquals(AgentPhase.INTERRUPTED, interrupted.phase)
                assertEquals(1, interrupted.results.size)
                assertEquals(1, interrupted.claimed)
                assertNull(agent.approve(interrupted.id, interrupted.revision))
            }
            println("ALARM_RECOVERY $token $phase PID=${android.os.Process.myPid()}")
        }
    }

    private suspend fun withRuntime(name: String, cleanup: Boolean = true, test: suspend (AgentRuntime) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            MemoryStore(context, "$name-memory.db").use { memory ->
                ToolStore(context, "$name-tools.db").use { tools ->
                    AgentRuntime(context, "$name-agent.db", memory, tools).use { test(it) }
                }
            }
        } finally {
            if (cleanup) listOf("$name-memory.db", "$name-tools.db", "$name-agent.db").forEach(context::deleteDatabase)
        }
    }

    private fun clockSwitches(robot: ShellRobot): List<Pair<String, Boolean>> {
        val root = robot.diagnostics.freshRoot()?.takeIf { it.packageName?.toString() == CLOCK } ?: return emptyList()
        return nodes(root).filter { it.viewIdResourceName == "$CLOCK:id/onoff" && it.isCheckable }
            .map { node ->
                val title = node.contentDescription.toString().replace(Regex("[\\s\\u200a\\u202f]+"), " ")
                val time = title.substringBefore(" alarm").trim().let {
                    when (it) { "07:00", "7:00" -> "7:00 AM"; "09:00", "9:00" -> "9:00 AM"; else -> it }
                }
                time to node.isChecked
            }.toList()
    }

    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(nodes(it)) }
    }

    private fun capture(dir: File, name: String, robot: ShellRobot) {
        val screenshot = checkNotNull(robot.automation.takeScreenshot())
        try {
            File(dir, "$name.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }

    private companion object {
        const val CLOCK = "com.google.android.deskclock"
        const val REQUEST = "Set alarms for 7:00 AM and 9:00 AM."
        const val ALARMS = """{"steps":[{"function":"clock.setAlarm","args":{"hour":7,"minute":0}},{"function":"clock.setAlarm","args":{"hour":9,"minute":0}}]}"""
        val targetAlarms = listOf("7:00 AM", "9:00 AM")
    }
}
