package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.agent.AppSteps
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs scenarios on saved app definitions with the agent step checker, without a model.
 * Argument appScenarioDir: a directory in the app's external files with one input file for each app,
 * {"id","spec","scenarios":[{"name","steps"}]}. The test writes <name>.result.json next to each input.
 */
@RunWith(AndroidJUnit4::class)
class AppScenarioCheckTest {
    private val noModel = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
            error("The scenario check makes no model request.")
    }

    @Test fun runScenarios(): Unit = runBlocking {
        val name = InstrumentationRegistry.getArguments().getString("appScenarioDir")
        assumeTrue("Pass appScenarioDir to run saved app scenarios.", name != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), checkNotNull(name))
        val sandbox = SandboxClient(context)
        val service = ToolGenerationService(noModel, sandbox)
        checkNotNull(directory.listFiles { file -> file.name.endsWith(".input.json") }).sorted().forEach { input ->
            val job = JSONObject(input.readText())
            val results = JSONArray()
            try {
                val spec = ToolSpec.parsePersisted(job.getJSONObject("spec").toString())
                val smoke = ToolGenerationSmoke(sandbox, spec, job.getString("id"), 1, spec.initialState, checkActive = {}, generation = service)
                val scenarios = job.getJSONArray("scenarios")
                for (index in 0 until scenarios.length()) {
                    val scenario = scenarios.getJSONObject(index)
                    val outcome = runCatching {
                        val steps = AppSteps.parse(scenario.get("steps"))
                        smoke.probe { AppSteps.run(this, steps, everyStep = false) }
                    }
                    results.put(JSONObject().put("name", scenario.getString("name")).apply {
                        outcome.onSuccess { result ->
                            put("passed", result.failure == null).put("expectations", result.expectations)
                            result.failure?.let { put("failure", it) }
                            result.final?.let { put("screen", it.text).put("visible", JSONArray(it.visibleText)) }
                        }.onFailure { put("passed", false).put("failure", "${it.javaClass.simpleName}: ${it.message}") }
                    })
                }
            } catch (failure: Exception) {
                results.put(JSONObject().put("name", "load").put("passed", false).put("failure", "${failure.javaClass.simpleName}: ${failure.message}"))
            }
            File(directory, input.name.removeSuffix(".input.json") + ".result.json").writeText(results.toString(2))
        }
    }
}
