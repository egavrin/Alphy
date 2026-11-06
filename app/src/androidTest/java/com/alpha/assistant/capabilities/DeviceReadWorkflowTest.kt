package com.alpha.assistant.capabilities

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.device.DeviceLocation
import com.alpha.assistant.device.DeviceReading
import com.alpha.assistant.device.DeviceSensorSample
import com.alpha.assistant.device.DeviceStatusSnapshot
import com.alpha.assistant.device.DeviceStatusSnapshot.ConnectivityStatus
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolLibraryStorage
import com.alpha.assistant.tools.ToolLibraryUnavailable
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.selectOtherLibraryForTest
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Check provider isolation, stale permission callbacks, foreground loss and malformed platform data. */
@RunWith(AndroidJUnit4::class)
class DeviceReadWorkflowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hosts = DeviceReadHosts { true }

    @Test fun visibleMiniAppControlReceivesTheRealDeviceClockWithoutProviderOrAnotherAction() {
        val fixture = ShellFixture("device-read-foreground")
        val name = "${fixture.store.databaseName}-tools.db"
        val robot = ShellRobot("device-read-foreground")
        try {
            ToolStore(app, name).use { store ->
                val tool = save(store, "device.context")
                fixture.install(saveKey = false)
                fixture.launch().use { scenario ->
                    robot.waitUntil { fixture.model?.let { !it.loading && !it.tools.busy } == true }
                    scenario.onActivity { checkNotNull(fixture.model).openSurface(tool.id) }
                    robot.waitUntil { fixture.model?.tools?.busy == false }
                    val before = System.currentTimeMillis()
                    robot.click("Read")
                    robot.waitUntil { store.deliveries(tool.id).singleOrNull()?.phase == "committed" }
                    val result = store.effects(tool.id).single().result
                    val clock = result.data.getLong("capturedAtEpochMillis")
                    assertTrue(clock in before..System.currentTimeMillis())
                    assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
                    assertTrue(result.data.has("localeTag"))
                    assertTrue(result.data.getString("connectivityStatus") in setOf("defaultNetworkPresent", "noAccessibleDefaultNetwork", "unavailable"))
                    assertEquals(clock, JSONObject(store.tool(tool.id)!!.state).getLong("sample"))
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally { fixture.remove() }
    }

    @Test fun declaredStatusUsesTheLocalResultEventWithoutBackgroundOrDuplicateDelivery(): Unit = runBlocking {
        withStore { store ->
            val tool = save(store, "device.context")
            val engine = engine(store)
            val next = engine.interact(tool.id, tool.revision, tool.stateVersion, "read", null)
            val key = store.effects(tool.id).single().key
            val host = DeviceReadHost(tool.id, tool.revision)
            hosts.update(host, true)
            val broker = CapabilityBroker(app, store)
            val reader = DeviceReadAdapter(app, capture = {
                DeviceStatusSnapshot(1712345678000, null, DeviceStatusSnapshot.BatteryStatus.UNKNOWN, null,
                    localeTag = "sr-Latn-RS", connectivityStatus = ConnectivityStatus.DEFAULT_NETWORK_PRESENT)
            })
            val result = broker.executeDeviceRead(key, host, hosts, { error("Permission requested") }, reader::read)
            assertEquals("succeeded", result?.status)
            assertTrue(result!!.data.isNull("batteryLevelPercent"))
            assertTrue(result.data.isNull("externalPowerConnected"))
            assertEquals("unknown", result.data.getString("batteryStatus"))
            assertEquals("sr-Latn-RS", result.data.getString("localeTag"))
            assertEquals("defaultNetworkPresent", result.data.getString("connectivityStatus"))
            engine.deliverCapabilityResults(tool.id)
            assertEquals(next, store.tool(tool.id))
            hosts.whileVisible(host) { active -> engine.deliverForegroundEffectResults(next, active) }
            val saved = checkNotNull(store.tool(tool.id))
            assertEquals(1712345678000L, JSONObject(saved.state).getLong("sample"))
            assertEquals("sr-Latn-RS", JSONObject(saved.state).getString("locale"))
            assertEquals("defaultNetworkPresent", JSONObject(saved.state).getString("connectivity"))
            assertEquals(next.stateVersion + 1, saved.stateVersion)
            assertEquals("committed", store.deliveries(tool.id).single().phase)
            broker.executeDeviceRead(key, host, hosts, { error("Permission replayed") }) { error("Read replayed") }
            engine.deliverCapabilityResults(tool.id)
            assertEquals(saved, store.tool(tool.id))
        }
    }

    @Test fun statusReportsOnlyTheClosedLocaleAndDefaultNetworkFields(): Unit = runBlocking {
        val definition = CapabilityCatalog.definition("device.context").json()
        val access = definition.getJSONObject("access")
        assertTrue(access.isNull("androidPermission"))
        assertFalse(access.getBoolean("toolGrant"))
        assertEquals(Manifest.permission.ACCESS_NETWORK_STATE, access.getJSONArray("manifestPermissions").getString(0))
        val schema = definition.getJSONObject("result").getJSONObject("data")
        val required = schema.getJSONArray("required")
        val fields = (0 until required.length()).map(required::getString).toSet()
        assertEquals(setOf("capturedAtEpochMillis", "batteryLevelPercent", "batteryStatus", "externalPowerConnected", "localeTag", "connectivityStatus"), fields)
        assertFalse(schema.getBoolean("additionalProperties"))

        val outcomes = mapOf(
            ConnectivityStatus.DEFAULT_NETWORK_PRESENT to "defaultNetworkPresent",
            ConnectivityStatus.NO_ACCESSIBLE_DEFAULT_NETWORK to "noAccessibleDefaultNetwork",
            ConnectivityStatus.UNAVAILABLE to "unavailable",
        )
        val status = request("device.context")
        for ((native, wire) in outcomes) {
            val adapter = DeviceReadAdapter(app, capture = {
                DeviceStatusSnapshot(1712345678000, 42, DeviceStatusSnapshot.BatteryStatus.DISCHARGING, false,
                    localeTag = null, connectivityStatus = native)
            })
            val result = adapter.read(status)
            assertEquals("succeeded", result.status)
            val data = CapabilityResultEvent.data(status.capability, result)
            assertEquals(fields, data.keys().asSequence().toSet())
            assertTrue(data.isNull("localeTag"))
            assertEquals(wire, data.getString("connectivityStatus"))
        }
        val base = DeviceReadAdapter(app, capture = {
            DeviceStatusSnapshot(1712345678000, null, DeviceStatusSnapshot.BatteryStatus.UNKNOWN, null, localeTag = "es-419")
        }).read(status)
        assertEquals("es-419", base.data.getString("localeTag"))
        val invalidFields = listOf(
            "localeTag" to "en-US-u-nu-latn", "localeTag" to "und", "localeTag" to 123,
            "connectivityStatus" to "internetReachable", "connectivityStatus" to JSONObject.NULL,
            "networkName" to "private network", "localAddress" to "192.168.1.1",
        )
        for ((field, value) in invalidFields) {
            val malformed = base.copy(data = JSONObject(base.data.toString()).put(field, value))
            assertTrue(runCatching { CapabilityResultEvent.data(status.capability, malformed) }.isFailure)
        }
    }

    @Test fun permissionPausesAndResumesTheOriginalHostWithoutRepeatingTheAction(): Unit = runBlocking {
        var permitted = false
        withStore({ permitted }) { store ->
            val tool = save(store, "location.once")
            val key = enqueue(store, tool)
            val host = DeviceReadHost(tool.id, tool.revision)
            hosts.update(host, true)
            val launched = CompletableDeferred<Unit>()
            val permission = CompletableDeferred<Boolean>()
            val callbackAccepted = CompletableDeferred<Unit>()
            val sampleStarted = CompletableDeferred<Unit>()
            val broker = CapabilityBroker(app, store)
            val result = async {
                broker.executeDeviceRead(key, host, hosts, { effect ->
                    assertEquals(key, effect.key)
                    launched.complete(Unit)
                    permission.await().also { callbackAccepted.complete(Unit) }
                }) {
                    sampleStarted.complete(Unit)
                    DeviceReadAdapter(app, location = { DeviceReading.Available(DeviceLocation(55.5, 37.25, null)) }).read(it)
                }
            }
            withTimeout(5_000) { launched.await() }
            hosts.update(host, false)
            permitted = true
            permission.complete(true)
            withTimeout(5_000) { callbackAccepted.await() }
            assertFalse(sampleStarted.isCompleted)
            hosts.update(host, true)
            assertEquals("succeeded", withTimeout(5_000) { result.await() }?.status)
            assertEquals(55.5, store.effect(key)!!.result.data.getDouble("latitudeDegrees"), 0.0)
            assertEquals(1, store.effects(tool.id).size)
        }
    }

    @Test fun libraryReplacementAndPreviousProcessCannotDeliverOldSamples(): Unit = runBlocking {
        withStore { store ->
            val old = save(store, "device.context")
            val host = DeviceReadHost(old.id, old.revision)
            hosts.update(host, true)
            val key = enqueue(store, old)
            val reader = DeviceReadAdapter(app, capture = {
                DeviceStatusSnapshot(1712345678000, 42, DeviceStatusSnapshot.BatteryStatus.DISCHARGING, false)
            })
            assertEquals("succeeded", CapabilityBroker(app, store).executeDeviceRead(key, host, hosts, { error("Permission requested") }, reader::read)?.status)
            store.writableDatabase.execSQL("UPDATE capability_effects SET session='old-process' WHERE effect_key=?", arrayOf(key))
            val engine = engine(store)
            engine.deliverCapabilityResults(old.id)
            assertEquals("rejected", store.deliveries(old.id).single().phase)
            assertEquals(0L, JSONObject(store.tool(old.id)!!.state).getLong("sample"))

            val next = store.tool(old.id)!!.copy(stateVersion = old.stateVersion + 2)
            store.commitTool(next, store.tool(old.id), false, effects = listOf(request("device.context")))
            val nextKey = store.effects(old.id).last().key
            selectOtherLibraryForTest(app, store.library)
            ToolStore(app, store.logicalDatabase).use { replacement ->
                replacement.commitTool(next, null, true)
                assertTrue(runCatching {
                    CapabilityBroker(app, store).executeDeviceRead(nextKey, host, hosts, { error("Permission requested") }) { error("Old library read") }
                }.exceptionOrNull() is ToolLibraryUnavailable)
                assertTrue(replacement.effects(old.id).isEmpty())
                assertEquals(next, replacement.tool(old.id))
            }
        }
    }

    @Test fun deniedAndRevokedLocationNeverExposeCoordinates(): Unit = runBlocking {
        var permitted = false
        withStore({ permitted }) { store ->
            val tool = save(store, "location.once")
            val host = DeviceReadHost(tool.id, tool.revision)
            hosts.update(host, true)
            val broker = CapabilityBroker(app, store)
            val denied = enqueue(store, tool)
            assertEquals("denied", broker.executeDeviceRead(denied, host, hosts, { false }) { error("Android denied access") }?.status)
            assertEquals(0, store.effect(denied)!!.result.data.length())
            val deniedVersion = checkNotNull(store.tool(tool.id))
            hosts.whileVisible(host) { active -> engine(store).deliverForegroundEffectResults(deniedVersion, active) }
            assertEquals("denied", JSONObject(store.tool(tool.id)!!.state).getString("outcome"))

            permitted = true
            val revoked = enqueue(store, checkNotNull(store.tool(tool.id)))
            val result = broker.executeDeviceRead(revoked, host, hosts, { error("Already permitted") }) {
                permitted = false
                DeviceReadAdapter(app, location = { DeviceReading.Available(DeviceLocation(10.5, 20.25, 5f)) }).read(it)
            }
            assertEquals("denied", result?.status)
            assertEquals(0, store.effect(revoked)!!.result.data.length())
        }
    }

    @Test fun lastVisibleHostLossCancelsTheSampleAndAReplacementCannotReplayIt(): Unit = runBlocking {
        withStore { store ->
            val tool = save(store, "sensor.sample")
            val host = DeviceReadHost(tool.id, tool.revision)
            val second = DeviceReadHost(tool.id, tool.revision)
            hosts.update(host, true)
            hosts.update(second, true)
            val started = CompletableDeferred<Unit>()
            val late = CompletableDeferred<CapabilityResult>()
            val cleaned = CompletableDeferred<Unit>()
            val key = enqueue(store, tool)
            val broker = CapabilityBroker(app, store)
            val running = async {
                broker.executeDeviceRead(key, host, hosts, { error("Permission requested") }) {
                    started.complete(Unit)
                    try { late.await() } finally { cleaned.complete(Unit) }
                }
            }
            withTimeout(5_000) { started.await() }
            hosts.update(host, null)
            assertFalse(cleaned.isCompleted)
            hosts.update(second, false)
            withTimeout(5_000) { cleaned.await() }
            hosts.update(second, true)
            late.complete(CapabilityResult("succeeded", "Late", JSONObject().put("sensor", "ambientLight").put("lux", 23)))
            assertEquals("cancelled", withTimeout(5_000) { running.await() }?.status)
            assertEquals(0, store.effect(key)!!.result.data.length())
            broker.executeDeviceRead(key, second, hosts, { error("Permission replayed") }) { error("Read replayed") }
            assertEquals("cancelled", store.effect(key)!!.result.status)
        }
    }

    @Test fun closedOriginAndStaleRevisionRejectAPermissionReturnEvenWithAnotherHost(): Unit = runBlocking {
        for (closeOrigin in listOf(true, false)) {
            var permitted = false
            withStore({ permitted }) { store ->
                val tool = save(store, "location.once")
                val origin = DeviceReadHost(tool.id, tool.revision)
                val other = DeviceReadHost(tool.id, tool.revision)
                hosts.update(origin, true)
                hosts.update(other, true)
                val key = enqueue(store, tool)
                val launched = CompletableDeferred<Unit>()
                val reply = CompletableDeferred<Boolean>()
                val broker = CapabilityBroker(app, store)
                val operation = async {
                    broker.executeDeviceRead(key, origin, hosts, {
                        launched.complete(Unit)
                        reply.await()
                    }) { error("A stale permission callback caused a sample") }
                }
                withTimeout(5_000) { launched.await() }
                if (closeOrigin) hosts.update(origin, null) else {
                    val current = checkNotNull(store.tool(tool.id))
                    store.commitTool(current.copy(revision = current.revision + 1), current, true)
                }
                permitted = true
                reply.complete(true)
                assertEquals("cancelled", withTimeout(5_000) { operation.await() }?.status)
                assertEquals(0, store.effect(key)!!.result.data.length())
                hosts.update(other, null)
                hosts.update(origin, null)
            }
        }
    }

    @Test fun closedContractsRejectForgedDataAndReportUnavailableAndInvalidSamples(): Unit = runBlocking {
        assertTrue(CapabilityCatalog.ids.containsAll(setOf("device.context", "location.once", "sensor.sample")))
        assertTrue(runCatching { CapabilityCatalog.request(request("location.once").json().put("args", JSONObject().put("provider", "gps"))) }.isFailure)
        assertTrue(runCatching { CapabilityCatalog.request(request("sensor.sample").json().put("args", JSONObject().put("sensor", "magnetometer"))) }.isFailure)
        val ambientLight = request("sensor.sample")
        val readings = listOf(
            DeviceReading.Available(DeviceSensorSample.AmbientLight(23f)) to "succeeded",
            DeviceReading.Available(DeviceSensorSample.AmbientLight(-1f)) to "failed",
            DeviceReading.Unknown to "failed",
            DeviceReading.Unavailable to "unavailable",
            DeviceReading.PermissionDenied to "denied",
        )
        for ((reading, status) in readings) {
            val result = DeviceReadAdapter(app, sensor = { reading }).read(ambientLight)
            assertEquals(status, result.status)
            val data = CapabilityResultEvent.data(ambientLight.capability, result)
            if (status == "succeeded") {
                assertEquals(setOf("sensor", "lux"), data.keys().asSequence().toSet())
                assertEquals("ambientLight", data.getString("sensor"))
                assertEquals(23.0, data.getDouble("lux"), 0.0)
            } else assertEquals(0, data.length())
        }
        val vector = DeviceReadAdapter(app, sensor = { DeviceReading.Available(DeviceSensorSample.AngularVelocity(1f, -2f, 3f)) })
        val gyroscope = request("sensor.sample").copy(args = JSONObject().put("sensor", "gyroscope"))
        assertEquals(setOf("sensor", "x", "y", "z"), vector.read(gyroscope).data.keys().asSequence().toSet())
        val forged = CapabilityResult("succeeded", "Fixture", JSONObject().put("sensor", "ambientLight").put("lux", 30).put("private", "unexpected"))
        assertTrue(runCatching { CapabilityResultEvent.data("sensor.sample", forged) }.isFailure)
        val deniedContext = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
        }
        assertFalse(AndroidCapabilities(deniedContext).permissionGranted("location.once"))
        assertEquals("denied", DeviceReadAdapter(deniedContext).read(request("location.once")).status)
        val coarseContext = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String) = if (permission == Manifest.permission.ACCESS_COARSE_LOCATION)
                PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        assertTrue(AndroidCapabilities(coarseContext).permissionGranted("location.once"))
        assertEquals("unavailable", AndroidCapabilities(coarseContext).execute(request("location.once"), true) { error("A generic adapter launched Android") }.status)
    }

    private fun request(capability: String) = CapabilityRequest("read", capability, JSONObject().apply {
        if (capability == "sensor.sample") put("sensor", "ambientLight")
    })

    private fun enqueue(store: ToolStore, old: ToolRecord): String {
        val next = old.copy(stateVersion = old.stateVersion + 1)
        store.commitTool(next, old, false, effects = listOf(request(ToolSpec.parsePersisted(old.spec).capabilities.single())))
        return store.effects(old.id).last().key
    }

    private fun save(store: ToolStore, capability: String): ToolRecord {
        val spec = ToolSpec.parse(JSONObject().put("formatVersion", 3).put("title", "Device read fixture").put("description", "")
            .put("capabilities", org.json.JSONArray(listOf(capability))).put("capabilityResults", true)
            .put("stateSchema", JSONObject().put("sample", "number").put("outcome", "string").put("locale", "string").put("connectivity", "string"))
            .put("initialState", JSONObject().put("sample", 0).put("outcome", "").put("locale", "").put("connectivity", ""))
            .put("ui", JSONObject().put("type", "button").put("text", "Read").put("action", "read"))
            .put("source", """if(input.event.type==='action'){return {state:input.state,effects:[${request(capability).json()}]};}
                if(input.event.type==='capabilityResults'){const result=input.event.entries[0];input.state.outcome=result.status;
                if(result.data.capturedAtEpochMillis!==undefined){input.state.sample=result.data.capturedAtEpochMillis;
                input.state.locale=result.data.localeTag||'';input.state.connectivity=result.data.connectivityStatus;}}
                return {state:input.state};""").toString())
        return ToolRecord("device-${UUID.randomUUID()}", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
            .also { store.commitTool(it, null, true) }
    }

    private fun engine(store: ToolStore): ToolEngine {
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                error("A native device read called the provider")
            }
        }
        return ToolEngine(store, ToolGenerationService(provider, SandboxClient(app)))
    }

    private suspend fun withStore(permission: () -> Boolean = { true }, work: suspend (ToolStore) -> Unit) {
        val name = "device-read-${UUID.randomUUID()}.db"
        try { ToolStore(app, name, permissionCheck = { permission() }).use { work(it) } }
        finally {
            app.databaseList().filter { it.startsWith(name) }.forEach(app::deleteDatabase)
            ToolLibraryStorage.selectionFile(app, name).delete()
        }
    }
}
