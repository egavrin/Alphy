package com.alpha.assistant.agent

import android.os.Process
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.memory.MemoryStore
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed and recover in different app processes against the same isolated journal. */
@RunWith(AndroidJUnit4::class)
class CameraContinuationRecoveryTest {
    @Test fun waitingPlanDoesNotResumeInAnotherProcess() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("cameraRecoveryPhase")
        assumeTrue(phase in setOf("seed", "recover"))
        val token = checkNotNull(args.getString("cameraRecoveryId"))
        require(token.matches(Regex("[a-z0-9-]{1,48}")))
        val context = instrumentation.targetContext
        val name = "camera-recovery-$token"
        try {
            MemoryStore(context, "$name-memory.db").use { memory ->
                ToolStore(context, "$name-tools.db").use { tools ->
                    AgentRuntime(context, "$name-agent.db", memory, tools).use { agent ->
                        if (phase == "seed") {
                            val plan = agent.prepare("test-chat", "Set a timer, then take a photo.", """
                                {"steps":[{"function":"clock.setTimer","args":{"seconds":300}},{"function":"camera.capture","args":{"lens":"rear"}}]}
                            """.trimIndent())
                            val approved = checkNotNull(agent.approve(plan.id, plan.revision))
                            val launches = mutableListOf<String?>()
                            val waiting = agent.execute(approved, { true }, { launches += it.action })
                            assertEquals(listOf(AlarmClock.ACTION_SET_TIMER), launches)
                            assertEquals(AgentPhase.WAITING_FOR_CAMERA, waiting.phase)
                            assertEquals(1, waiting.results.size)
                        } else {
                            val interrupted = checkNotNull(agent.latest("test-chat"))
                            assertEquals(AgentPhase.INTERRUPTED, interrupted.phase)
                            assertEquals(1, interrupted.results.size)
                            assertNull(agent.approve(interrupted.id, interrupted.revision, continueAfterClock = true))
                        }
                        println("CAMERA_RECOVERY $phase PID=${Process.myPid()}")
                    }
                }
            }
        } finally {
            if (phase == "recover") {
                listOf("$name-memory.db", "$name-tools.db", "$name-agent.db").forEach(context::deleteDatabase)
            }
        }
        Unit
    }
}
