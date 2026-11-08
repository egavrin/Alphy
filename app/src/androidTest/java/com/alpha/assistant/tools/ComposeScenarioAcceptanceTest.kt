package com.alpha.assistant.tools

import com.alpha.assistant.ui.openSeededChat
import com.alpha.assistant.ui.seedOpenChat
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Harness failures: missing opt-in, wrong matrix identity, credential leaks, shared artifact state,
 * extra provider retries, lost evidence, automatic effects, and synthetic evidence reported as real.
 * Native interaction results require the separate manifest checks. Screenshots alone never pass them.
 */
class ComposeScenarioAcceptanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
    private fun cases()=JSONObject(instrumentation.context.assets.open("compose-scenarios.json").bufferedReader().use {it.readText()}).getJSONArray("scenarios")

    @Test fun scenarioMatrixKeepsOriginalRequestsAndExplicitChecks() {
        val cases=cases()
        assertEquals(6,cases.length())
        repeat(cases.length()) {index ->
            val case=cases.getJSONObject(index)
            assertEquals(index+1,case.getInt("id")); assertEquals(3,case.getInt("independent_generations"))
            assertTrue(case.getString("request").isNotBlank());assertTrue(case.getJSONObject("checks").length()>=4)
        }
        assertEquals("Generate arkanoid game",cases.getJSONObject(3).getString("request"))
    }

    @Test fun generateAndPresentIndependentScenario(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val live=args.getString("composeLive") == "true"
        val credentialProbe=args.getString("composeCredentialProbe") == "true"
        require(!live || !credentialProbe)
        val scenario=args.getString("composeScenario")?.toInt() ?: 4
        val repetition=args.getString("composeRepetition")?.toInt() ?: 1
        val runId=args.getString("composeRunId") ?: "synthetic-${java.util.UUID.randomUUID()}"
        val seconds=args.getString("composeReviewSeconds")?.toInt() ?: 1
        val revision=args.getString("composeRevision") ?: "unrecorded-synthetic"
        val sourceHash=args.getString("composeSourceSha256") ?: "unrecorded-synthetic"
        require(scenario in 1..6 && repetition in 1..3 && seconds in 1..180)
        require(runId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        if(live) {
            require(revision.matches(Regex("[a-fA-F0-9]{40}")))
            require(sourceHash.matches(Regex("[a-fA-F0-9]{64}")))
        }
        val case=cases().getJSONObject(scenario-1)
        val mode=if(credentialProbe) "credential-check" else if(live) "real" else "synthetic"
        val directory=File(app.getExternalFilesDir(null),"compose-acceptance/$mode/$runId")
        check(!directory.exists()) {"Evidence directory already exists."}
        check(directory.mkdirs())
        val responses=JSONArray()
        val routingStages=JSONArray()
        val attempts=JSONArray()
        var repaired=false
        var planRequests=0
        var selectorRequests=0
        val report=JSONObject().put("runId",runId).put("mode",mode).put("scenario",scenario).put("repetition",repetition)
            .put("revision",revision).put("sourceSha256",sourceHash).put("request",case.getString("request"))
            .put("generation","pending").put("responses",responses).put("routingStages",routingStages).put("generationAttempts",attempts).put("phase","generation")
            .put("interactions","UNREVIEWED").put("externalResults","UNVERIFIED")
        fun save() {File(directory,"report.json").writeText(report.toString(2))}
        fun screenshot(name: String) {
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {"Screenshot unavailable."}
            try {File(directory,"$name.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
            finally {bitmap.recycle()}
        }
        save()
        fun transferredKey(): String {
            check(args.getString("composeCredentialTransport")=="adb") {"Host credential transport is required."}
            return OneUseCredential.receive(runId) {report.put("phase","credentialReady");save()}
                .also {report.put("phase","generation").put("credentialTransport","oneUseAdbPeer");save()}
        }
        if(credentialProbe) {
            try {
                val key=transferredKey()
                check(key.startsWith("synthetic-credential-")) {"Credential probe requires a synthetic value."}
                check(directory.walkTopDown().filter {it.isFile}.none {it.readBytes().toString(Charsets.UTF_8).contains(key)})
                android.net.LocalServerSocket("compose-credential-$runId").close()
                report.put("phase","complete").put("generation","notRequested").put("providerCalls",0)
                    .put("secretInArtifacts",false).put("listenerClosed",true)
            } catch(failure: Exception) {
                report.put("phase","failed").put("failureType",failure.javaClass.simpleName)
                fail("Credential probe failed. Inspect its sanitized report.json.")
            } finally {save()}
            return@runBlocking
        }
        val name="compose-$runId"
        val shell=ShellStore(app,"$name.db")
        val store=ToolStore(app,"$name.db-tools.db")
        val uiKeys=SecureKeyStore(app,name,name)
        val offline=object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                error("The review activity cannot call a provider.")
            }
        }
        val liveProvider=if (live) DeepSeekProvider() else null
        val provider=object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                check(request.jsonObject && !request.search && request.turns.all {it.images.isEmpty()})
                val stage=checkNotNull(request.generationStage)
                if (stage != CloudGenerationStage.SELECTOR && request.turns.size > 1) repaired=true
                if (stage == CloudGenerationStage.PLAN || stage == CloudGenerationStage.SELECTOR) {
                    if (stage == CloudGenerationStage.PLAN) check(++planRequests <= 4) {"PLAN repair limit exceeded."}
                    else check(++selectorRequests <= 2) {"Selector request limit exceeded."}
                    routingStages.put(stage.name)
                    save()
                    if (liveProvider != null) liveProvider.respond(key,request,session,emit)
                    else check(request.replyWithFixturePlan(emit))
                    return
                }
                check(stage == CloudGenerationStage.COMPLETE)
                check(responses.length()<4) {"COMPLETE repair limit exceeded."}
                if(responses.length()>0) responses.getJSONObject(responses.length()-1).put("outcome","correctionRequested")
                val response=JSONObject().put("attempt",responses.length()+1).put("outcome","started").put("characters",0)
                responses.put(response);save()
                var characters=0
                val delegate=liveProvider ?: object: ChatProvider {
                    override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                        emit(CloudEvent.Text(ArkanoidFixture.spec()))
                    }
                }
                try {
                    delegate.respond(key,request,session) {event ->
                        if(event is CloudEvent.Text) characters+=event.value.length
                        emit(event)
                    }
                    response.put("outcome","completed")
                } finally {response.put("characters",characters);save()}
            }
        }
        val engine=ToolEngine(store,ToolGenerationService(provider,SandboxClient(app),null,{diagnostic ->
            attempts.put(diagnostic.report());save()
        }))
        var activity: ActivityScenario<CloudTestActivity>?=null
        try {
            check(store.tools().isEmpty() && store.drafts().isEmpty()) {"Independent storage is not empty."}
            val key=if(live) transferredKey() else "offline-fixture"
            val draft=engine.createDraft(case.getString("request"),null)
            engine.generate(draft.id,key,emptyList(),CloudSession())
            val tool=engine.accept(draft.id)
            report.put("generation","accepted").put("firstAttempt",!repaired).put("repaired",repaired)
                .put("artifactId",tool.id).put("artifactRevision",tool.revision).put("specSha256",hash(tool.spec))
                .put("initialStateSha256",hash(tool.state)).put("database","$name.db-tools.db")
            File(directory,"artifact.json").writeText(tool.spec)
            assertTrue(store.effects(tool.id).isEmpty())
            engine.closeArtifacts()
            shell.saveConversation(Conversation("review","Scenario review",1,1,messages=listOf(
                Message("artifact","assistant","Acceptance review",1,"complete",toolId=tool.id,toolRevision=tool.revision))))
            shell.seedOpenChat("review")
            uiKeys.save("review-ui-fixture-only").getOrThrow()
            lateinit var model: ShellViewModel
            CloudTestActivity.factory={ShellViewModel(it,offline,shell,uiKeys).openSeededChat(shell).also {created -> model=created}}
            activity=ActivityScenario.launch(CloudTestActivity::class.java)
            val deadline=SystemClock.uptimeMillis()+15000
            while((model.loading || model.tools.busy) && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(40)
            check(!model.loading && !model.tools.busy) {"Review activity did not load."}
            instrumentation.runOnMainSync {model.openSurface(tool.id)}
            SystemClock.sleep(750)
            screenshot("before")
            report.put("phase","review");save()
            SystemClock.sleep(seconds*1000L)
            screenshot("after")
            val saved=checkNotNull(store.tool(tool.id))
            report.put("finalStateSha256",hash(saved.state)).put("finalStateVersion",saved.stateVersion)
                .put("effects",JSONArray().apply {store.effects(tool.id).forEach {effect ->
                    put(JSONObject().put("capability",effect.request.capability).put("phase",effect.phase).put("status",effect.result.status))
                }}).put("phase","complete")
        } catch(failure: Exception) {
            report.put("phase","failed").put("generation",if(report.getString("generation")=="accepted") "accepted" else "failed")
                .put("failureType",failure.javaClass.simpleName)
            // Raw provider exceptions and response text can contain private data.
            fail("Scenario harness failed. Inspect its sanitized report.json.")
        } finally {
            activity?.close();CloudTestActivity.factory=null
            engine.closeArtifacts();store.close();shell.close()
            uiKeys.clear();app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(name)}
            fun outcome(number: Int): String? = (0 until attempts.length()).asSequence()
                .map(attempts::getJSONObject)
                .lastOrNull { it.getInt("attempt") == number && it.getString("disposition") != "continue" }
                ?.getString("outcome")
            report.put("firstAttemptOutcome",outcome(1) ?: "unknown")
                .put("repairOutcome",outcome(2) ?: "notRequested")
            save()
        }
    }
}
