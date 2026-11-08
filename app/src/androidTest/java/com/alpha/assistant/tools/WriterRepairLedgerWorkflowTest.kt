package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.GenerationBehavior
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WriterRepairLedgerWorkflowTest {
    @Test fun authoredLedgerCheckDistinguishesTheTwoTeamBalances(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sandbox = SandboxClient(instrumentation.targetContext)
        val generation = ToolGenerationService(object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                error("Authored ledger checks run locally.")
            }
        }, sandbox)
        val artifact = instrumentation.context.assets.open("behavior-replay/ledger-with-prefixed-balances.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val spec = ToolSpec.parsePersisted(artifact.getJSONObject("spec").toString())
        val checker = ToolGenerationSmoke(sandbox, spec, "ledger", 1, spec.initialState, {}, generation)
        val preview = checker.run()
        val scenario = GenerationBehavior.parse(JSONObject("""
            {"status":"checkable","scenario":{
            "slots":{"quiz":{"kind":"action"},"amberBalance":{"kind":"value"},"chalkBalance":{"kind":"value"}},
            "steps":[{"op":"action","slot":"quiz"},{"op":"assert","slot":"amberBalance","test":"equals","value":3},
            {"op":"assert","slot":"chalkBalance","test":"equals","value":0}]}}
        """.trimIndent()), emptySet()) as GenerationBehavior.Scenario
        suspend fun check(bindings: String) {
            checker.checkBehavior(scenario, GenerationBehavior.bindings(JSONObject(bindings), scenario.slots),
                preview, ToolGenerationRoute.WHOLE_SCREEN, emptySet())
        }
        val failure = runCatching {
            check("""{"quiz":"postQuiz","amberBalance":[0,1],"chalkBalance":[0,1]}""")
        }.exceptionOrNull()
        assertTrue(failure is BehaviorCheckFailure)
        check("""{"quiz":"postQuiz","amberBalance":[0,0],"chalkBalance":[0,1]}""")
    }
}
