package com.alpha.assistant.tools

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.capabilities.DeviceReadHost
import com.alpha.assistant.capabilities.DeviceReadHosts
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.network.NetworkAccessDenial
import com.alpha.assistant.network.NetworkResult
import com.alpha.assistant.network.NetworkResponse
import com.alpha.assistant.network.NetworkTarget
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.scheduling.SourceOffer
import com.alpha.assistant.scheduling.SourceRefresh
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real artifact and ledger workflow with controlled transport gates and no external destination. */
@RunWith(AndroidJUnit4::class)
class ToolNetworkEffectWorkflowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theVisibleMiniAppRoutesAnUngrantedNetworkActionThroughTheHost() {
        val shell=ShellFixture("tool-network-action-ui")
        val name="${shell.store.databaseName}-tools.db"
        val robot=ShellRobot("tool-network-action-ui")
        try {
            ToolStore(app,name).use { store ->
                val fixture=Fixture(store)
                shell.install(saveKey=false)
                shell.launch().use { scenario ->
                    robot.waitUntil {shell.model?.let {!it.loading && !it.tools.busy}==true}
                    scenario.onActivity {checkNotNull(shell.model).openSurface(fixture.tool.id)}
                    robot.waitUntil {shell.model?.tools?.busy==false}
                    robot.click("Send")
                    robot.waitUntil {fixture.state().getString("outcome")=="denied"}
                    assertEquals(1,fixture.state().getInt("actions"))
                    fixture.assertMetadataOnly()
                    assertEquals(0,shell.provider.calls)
                }
                ToolNetworkGrants.retireLibrary(app,store.library)
            }
        } finally {shell.remove()}
    }

    @Test fun deniedGrantPreservesTheActionAndNeverSendsOrStoresPayload(): Unit = runBlocking {
        withFixture { fixture ->
            val action = fixture.action()
            val runner = ToolNetworkEffects(fixture.store,fixture.engine,fixture.authority) { _, _, _, _ -> error("An ungranted action sent a request") }
            runner.execute(action,fixture.host,fixture.hosts,fixture.controller)
            val saved = fixture.state()
            assertEquals(1,saved.getInt("actions"))
            assertEquals("denied",saved.getString("outcome"))
            assertEquals("",saved.getString("body"))
            fixture.assertMetadataOnly()
        }
    }

    @Test fun responseIsEphemeralUntilTheOrderedAppResultAndCannotReplay(): Unit = runBlocking {
        withFixture { fixture ->
            assertTrue(fixture.authority.setGranted(fixture.tool.id,true).isSuccess)
            val action = fixture.action()
            val second = DeviceReadHost(fixture.tool.id,fixture.tool.revision).also {fixture.hosts.update(it,true)}
            var sends = 0
            val runner = ToolNetworkEffects(fixture.store,fixture.engine,fixture.authority) { _, key, request, terminal ->
                sends++
                assertEquals("running",fixture.store.effect(key)?.phase)
                assertEquals("https://fixture.invalid/private-url-sentinel",request.url)
                assertEquals("private-request-sentinel",request.body?.decodeToString())
                assertEquals("private-header-sentinel",request.headers.single().value)
                fixture.assertMetadataOnly()
                fixture.hosts.update(fixture.host,null)
                assertTrue(fixture.hosts.initiating(second))
                val result = NetworkResult.Delivered(422)
                terminal(result)
                ToolNetworkExchange(result,NetworkResponse(422,emptyList(),"private-response-sentinel".encodeToByteArray()))
            }
            runner.execute(action,fixture.host,fixture.hosts,fixture.controller)
            assertEquals("private-response-sentinel",fixture.state().getString("body"))
            assertEquals(422,fixture.state().getInt("httpStatus"))
            assertEquals("PrivateRequestIdSentinel",fixture.state().getString("requestId"))
            assertEquals("committed",fixture.store.deliveries(fixture.tool.id).single().phase)
            fixture.assertMetadataOnly()
            runner.execute(action,fixture.host,fixture.hosts,fixture.controller)
            assertEquals(1,sends)
        }
    }

    @Test fun liveDataGenerationUsesTheDocumentedExtractorOrOffersALocalShell(): Unit = runBlocking {
        val endpoint = "https://fixture.invalid/sensors"
        val network = JSONObject("""{"id":"refresh","capability":"network.request","args":{
            "method":"GET","url":"$endpoint","extract":{"kind":"number","path":["readings",0,"value"]}}}""")
        val name = "live-data-generation-${UUID.randomUUID()}.db"
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                if (request.replyWithFixturePlan(emit)) return
                val prompt = request.turns.first().text
                assertTrue(prompt.contains("Use the exact endpoint, JSON key/index path, and value type"))
                assertTrue(prompt.contains("Do not emit a live network effect for an incomplete source"))
                val configured = prompt.contains("Use $endpoint")
                val effects = if (configured) ",effects:[$network]" else ""
                emit(CloudEvent.Text(JSONObject("""{"formatVersion":3,"title":"Sensor dashboard","description":"",
                    "stateSchema":{"count":"number"},"initialState":{"count":0},
                    "ui":{"type":"column","children":[{"type":"text","text":"Unavailable"},
                    {"type":"button","text":"Refresh","action":"refresh"},{"type":"button","text":"Add","action":"add"}]}}""")
                    .put("capabilities", if (configured) JSONArray().put("network.request") else JSONArray())
                    .put("capabilityResults", configured)
                    .put("source", """
                        if (input.event.actionId === 'add') input.state.count++;
                        if (input.event.actionId === 'refresh') return {state:input.state$effects};
                        return {state:input.state};
                    """.trimIndent()).toString()))
            }
        }
        try {
            ToolStore(app, name).use { store ->
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(app)))
                for (configured in listOf(true, false)) {
                    val request = "Create a sensor dashboard with a local counter. " + if (configured)
                        "Use $endpoint and read the number at readings[0].value."
                    else "Show the live value as unavailable while the endpoint and JSON path are missing."
                    val draft = engine.createDraft(request, null)
                    engine.generate(draft.id, "fixture", emptyList(), CloudSession())
                    val saved = engine.accept(draft.id)
                    assertTrue(saved.ui.contains("Unavailable"))
                    val refresh = engine.interactForeground(saved.id, saved.revision, saved.stateVersion, "refresh", null, acceptNetwork = true)
                    if (configured) assertEquals(network.getJSONObject("args").toString(), checkNotNull(refresh.network).args.toString())
                    else assertNull(refresh.network)
                    val updated = engine.interact(refresh.tool.id, refresh.tool.revision, refresh.tool.stateVersion, "add", null)
                    assertEquals(1, JSONObject(updated.state).getInt("count"))
                }
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test fun liveJsonRefreshKeepsItsLastValueAndTimeAfterFailure(): Unit = runBlocking {
        val request = JSONObject("""{"id":"PrivateRequestIdSentinel","capability":"network.request","args":{
            "method":"GET","url":"https://fixture.invalid/private-url-sentinel","extract":{"kind":"number","path":["readings",0,"value"]}}}""")
        withFixture(request) { fixture ->
            assertTrue(fixture.authority.setGranted(fixture.tool.id,true).isSuccess)
            val refreshCache = SourceRefresh(fixture.store)
            assertTrue(refreshCache.approve(checkNotNull(SourceOffer.from(
                fixture.tool, request.getString("id"), request.getJSONObject("args")))))
            var body = """{"readings":[{"value":4}]}"""
            val runner = ToolNetworkEffects(fixture.store,fixture.engine,fixture.authority) { _, _, outbound, terminal ->
                assertEquals("https://fixture.invalid/private-url-sentinel", outbound.url)
                assertTrue(outbound.headers.isEmpty())
                val result = NetworkResult.Delivered(200)
                terminal(result)
                ToolNetworkExchange(result, NetworkResponse(200, emptyList(), body.encodeToByteArray()))
            }
            suspend fun refresh() = runner.execute(fixture.action(checkNotNull(fixture.store.tool(fixture.tool.id))),
                fixture.host,fixture.hosts,fixture.controller)

            val before = System.currentTimeMillis()
            refresh()
            assertEquals("succeeded", fixture.state().getString("outcome"))
            assertEquals("4", fixture.state().getString("body"))
            assertEquals("https://fixture.invalid/private-url-sentinel", fixture.state().getString("source"))
            val firstTime = fixture.state().getLong("dataTime")
            assertTrue(firstTime in before..System.currentTimeMillis())

            body = """{"readings":[{"value":9}]}"""
            refresh()
            assertEquals("9", fixture.state().getString("body"))
            val lastTime = fixture.state().getLong("dataTime")
            assertTrue(lastTime >= firstTime)
            body = """{"readings":[{"value":"invalid"}]}"""
            refresh()
            assertEquals("failed", fixture.state().getString("outcome"))
            assertEquals("9", fixture.state().getString("body"))
            assertEquals(lastTime, fixture.state().getLong("dataTime"))
            val cached = checkNotNull(refreshCache.status(fixture.tool.id))
            assertTrue(cached.failed)
            assertEquals(9.0, cached.sample?.getDouble("value"))
            fixture.assertMetadataOnly()
        }
    }

    @Test fun revisedArtifactNeverConsumesTheResponseFromItsPriorVersion(): Unit = runBlocking {
        withFixture { fixture ->
            assertTrue(fixture.authority.setGranted(fixture.tool.id,true).isSuccess)
            val action=fixture.action()
            val started=CompletableDeferred<Unit>()
            val release=CompletableDeferred<Unit>()
            val runner=ToolNetworkEffects(fixture.store,fixture.engine,fixture.authority) { _, _, _, terminal ->
                started.complete(Unit)
                release.await()
                val result=NetworkResult.Delivered(200)
                terminal(result)
                ToolNetworkExchange(result,NetworkResponse(200,emptyList(),"private-response-sentinel".encodeToByteArray()))
            }
            val operation=async {runner.execute(action,fixture.host,fixture.hosts,fixture.controller)}
            withTimeout(5_000) {started.await()}
            val current=checkNotNull(fixture.store.tool(fixture.tool.id))
            val revised=current.copy(revision=current.revision+1,stateVersion=current.stateVersion+1)
            fixture.store.commitTool(revised,current,true)
            release.complete(Unit)
            withTimeout(5_000) {operation.await()}
            assertEquals(revised,fixture.store.tool(fixture.tool.id))
            assertEquals("rejected",fixture.store.deliveries(fixture.tool.id).single().phase)
            fixture.assertMetadataOnly()
        }
    }

    @Test fun lastHostLossAndRevocationDropLateResultsAndRetainUncertainWriteReceipt(): Unit = runBlocking {
        for (revoke in listOf(false,true)) withFixture { fixture ->
            assertTrue(fixture.authority.setGranted(fixture.tool.id,true).isSuccess)
            val action = fixture.action()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val runner = ToolNetworkEffects(fixture.store,fixture.engine,fixture.authority) { _, _, _, terminal ->
                started.complete(Unit)
                try {
                    release.await()
                    val result = NetworkResult.Delivered(200)
                    terminal(result)
                    ToolNetworkExchange(result,NetworkResponse(200,emptyList(),"private-response-sentinel".encodeToByteArray()))
                } finally {
                    if (!release.isCompleted) terminal(NetworkResult.UnknownOutcome(NetworkResult.Cancelled))
                }
            }
            val operation = async { runner.execute(action,fixture.host,fixture.hosts,fixture.controller) }
            withTimeout(5_000) { started.await() }
            if (revoke) {
                assertTrue(fixture.authority.setGranted(fixture.tool.id,false).isSuccess)
                release.complete(Unit)
            } else {
                fixture.hosts.update(fixture.host,null)
            }
            withTimeout(5_000) { operation.await() }
            assertEquals(1,fixture.state().getInt("actions"))
            assertEquals("",fixture.state().getString("body"))
            assertEquals("rejected",fixture.store.deliveries(fixture.tool.id).single().phase)
            if (!revoke) assertEquals("unknownOutcome",fixture.store.effects(fixture.tool.id).single().result.status)
            fixture.assertMetadataOnly()
        }
    }

    @Test fun publicationPermitIsSingleUseAndUsesTheRevocationOrder(): Unit = runBlocking {
        withFixture { fixture ->
            assertTrue(fixture.authority.setGranted(fixture.tool.id,true).isSuccess)
            val before = checkNotNull(fixture.authority.activate(fixture.tool,fixture.controller,"first"))
            val permit = fixture.authority.publicationPermit(before) { true }
            assertTrue(permit())
            assertTrue(fixture.authority.setGranted(fixture.tool.id,false).isSuccess)
            assertFalse(permit())
            assertTrue(fixture.authority.setGranted(fixture.tool.id,true).isSuccess)
            val after = checkNotNull(fixture.authority.activate(fixture.tool,fixture.controller,"second"))
            val late = fixture.authority.publicationPermit(after) { true }
            assertTrue(fixture.authority.setGranted(fixture.tool.id,false).isSuccess)
            assertFalse(late())
        }
    }

    @Test fun previousProcessWriteAndWidgetCannotReplay(): Unit = runBlocking {
        withFixture { fixture ->
            val tool = fixture.tool
            assertTrue(runCatching {fixture.engine.interact(tool.id,tool.revision,tool.stateVersion,"send",null,allowDeviceEffects=false)}.isFailure)
            assertEquals(tool,fixture.store.tool(tool.id))
            assertTrue(fixture.store.effects(tool.id).isEmpty())
            fixture.action()
            val effect=fixture.store.effects(tool.id).single()
            assertNotNull(fixture.store.claimEffect(effect.key))
            fixture.store.writableDatabase.execSQL("UPDATE capability_effects SET session='previous-process' WHERE effect_key=?", arrayOf(effect.key))
            fixture.store.recoverCapabilityWork()
            assertEquals("unknownOutcome",fixture.store.effect(effect.key)?.result?.status)
            assertNull(fixture.store.claimEffect(effect.key))
            fixture.engine.deliverCapabilityResults(tool.id)
            assertEquals("",fixture.state().getString("body"))
            fixture.assertMetadataOnly()
        }
    }

    @Test fun androidPolicySeparatesLanPermissionFromPublicHttpsAndCleartext(): Unit = runBlocking {
        val allowed = AtomicBoolean(false)
        val context = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String) = if (permission == Manifest.permission.ACCESS_LOCAL_NETWORK)
                if (allowed.get()) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        val public = NetworkTarget("https","fixture.invalid",443)
        val lan = NetworkTarget("https","device.local",443)
        val policy = AndroidMiniAppNetworkAccess(context, sdk = 37, cleartext = { false }, onLink = { false })
        val privatePeer = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(),168.toByte(),1,10)),443)
        val publicPeer = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(93,184.toByte(),1,1)),443)
        assertNull(policy.checkTarget(public))
        assertNull(policy.checkDestination(public,publicPeer))
        assertEquals(NetworkAccessDenial.LOCAL_NETWORK_PERMISSION,policy.checkTarget(lan))
        assertEquals(NetworkAccessDenial.LOCAL_NETWORK_PERMISSION,policy.checkDestination(public,privatePeer))
        val onLink = AndroidMiniAppNetworkAccess(context, sdk=37, cleartext={false}, onLink={it==publicPeer.address})
        assertEquals(NetworkAccessDenial.LOCAL_NETWORK_PERMISSION,onLink.checkDestination(public,publicPeer))
        allowed.set(true)
        assertNull(policy.checkTarget(lan))
        assertNull(policy.checkDestination(public,privatePeer))
        assertEquals(NetworkAccessDenial.CLEARTEXT_POLICY,policy.checkTarget(lan.copy(scheme="http",port=80)))
        assertTrue(runCatching {
            CapabilityCatalog.parseEffects(JSONArray(listOf(fixtureRequest(),fixtureRequest().put("id","second"))),setOf("network.request"),"action")
        }.isFailure)
    }

    private suspend fun withFixture(request: JSONObject = fixtureRequest(), work: suspend (Fixture) -> Unit) = withContext(Dispatchers.IO) {
        val name = "tool-network-effect-${UUID.randomUUID()}.db"
        try {
            ToolStore(app,name).use { store ->
                try {
                    val fixture=Fixture(store,request)
                    checkNotNull(fixture.authority.refreshAccess()) {"The saved fixture app could not load network access."}
                    work(fixture)
                } finally {ToolNetworkGrants.retireLibrary(app,store.library)}
            }
        } finally { app.deleteDatabase(name) }
    }

    private inner class Fixture(val store: ToolStore, request: JSONObject = fixtureRequest()) {
        private val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                error("A local network effect called the model provider")
            }
        }
        val engine = ToolEngine(store,ToolGenerationService(provider,SandboxClient(app)))
        val authority = ToolNetworkGrants.authority(store)
        val controller = Any()
        val hosts = DeviceReadHosts { true }
        val tool = ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Network fixture").put("description","")
            .put("capabilities",JSONArray(listOf("network.request"))).put("capabilityResults",true)
            .put("stateSchema",JSONObject("""{"actions":"number","outcome":"string","body":"string","httpStatus":"number","requestId":"string","source":"string","dataTime":"number"}"""))
            .put("initialState",JSONObject("""{"actions":0,"outcome":"","body":"","httpStatus":0,"requestId":"","source":"","dataTime":0}"""))
            .put("ui",JSONObject("""{"type":"button","text":"Send","action":"send"}"""))
            .put("source","""if(input.event.type==='action'){input.state.actions++;return {state:input.state,effects:[$request]};}
                if(input.event.type==='capabilityResults'){const r=input.event.entries[0];input.state.outcome=r.status;
                if(r.data.value!==undefined){input.state.body=String(r.data.value);input.state.source=r.data.source;input.state.dataTime=r.data.dataTimeMs;}
                else if(r.data.bodyText!==undefined)input.state.body=r.data.bodyText;
                input.state.httpStatus=r.data.httpStatus||0;input.state.requestId=r.requestId;}return {state:input.state};""").toString()).let { spec ->
            ToolRecord(UUID.randomUUID().toString(),1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
                .also {store.commitTool(it,null,true)}
        }
        val host = DeviceReadHost(tool.id,tool.revision).also {hosts.update(it,true)}

        suspend fun action(current: ToolRecord = tool): ToolForegroundAction =
            engine.interactForeground(current.id,current.revision,current.stateVersion,"send",null,acceptNetwork=true)

        fun state() = JSONObject(store.tool(tool.id)!!.state)

        fun assertMetadataOnly() {
            val records = store.effects(tool.id).map {it.request.json().toString()+it.result.json().toString()} + store.deliveries(tool.id).mapNotNull {it.event}
            assertTrue(records.isNotEmpty())
            assertFalse(records.any {"private-" in it || "fixture.invalid" in it || "PrivateRequestIdSentinel" in it})
        }
    }

    private fun fixtureRequest() = JSONObject("""{"id":"PrivateRequestIdSentinel","capability":"network.request","args":{"method":"POST","url":"https://fixture.invalid/private-url-sentinel","headers":[{"name":"X-Fixture","value":"private-header-sentinel"}],"bodyText":"private-request-sentinel"}}""")
}
