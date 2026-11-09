package com.alpha.assistant.capabilities

import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.network.MiniAppNetwork
import com.alpha.assistant.network.NetworkGrantPersistence
import com.alpha.assistant.network.NetworkLimits
import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkScope
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolLibraryStorage
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.selectOtherLibraryForTest
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Boundaries under test: declaration, Android denial, prior-process work, and old picker identity. */
@RunWith(AndroidJUnit4::class)
class DeviceAuthorizationWorkflowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pick = CapabilityRequest("pick", "photo.pick", JSONObject())
    private val vibration = CapabilityRequest("tap", "vibration.play", JSONObject().put("milliseconds", 10))

    @Test fun declaredDeviceActionRunsWithoutNetworkConsent(): Unit = runBlocking {
        withStore { store ->
            val tool = save(store, vibration)
            val network = MiniAppNetwork(NetworkLimits(16, 16, 64, 1000, 0, 1), object : NetworkGrantPersistence {
                override fun loadEnabled() = Result.success(emptySet<NetworkOwner>())
                override fun setEnabled(owner: NetworkOwner, enabled: Boolean): Result<Unit> = error("Unexpected network authorization")
            })
            val scope = NetworkScope(NetworkOwner(store.databaseName, tool.id), "1", "fixture")
            var executions = 0
            val key = enqueue(store, vibration)
            val broker = CapabilityBroker(app, store)
            assertNull(network.activate(scope))
            assertEquals("succeeded", broker.execute(key) { executions++; CapabilityResult("succeeded", "Fixture") }.status)
            assertEquals("succeeded", broker.execute(key) { error("The effect replayed") }.status)
            assertNull(network.activate(scope))
            assertEquals(1, executions)
        }
    }

    @Test fun undeclaredAndroidDeniedAndOldPendingEffectsCannotDispatch(): Unit = runBlocking {
        withStore { store ->
            save(store, vibration)
            val broker = CapabilityBroker(app, store)
            val invalid = enqueue(store, pick)
            assertEquals("denied", broker.execute(invalid) { error("An undeclared effect ran") }.status)
            val old = enqueue(store, vibration)
            store.writableDatabase.execSQL("UPDATE capability_effects SET session='old-process' WHERE effect_key=?", arrayOf(old))
            assertEquals("cancelled", broker.execute(old) { error("Old pending work ran") }.status)
            val recover = enqueue(store, vibration)
            store.writableDatabase.execSQL("UPDATE capability_effects SET session='old-process' WHERE effect_key=?", arrayOf(recover))
            store.recoverCapabilityWork()
            assertEquals("cancelled", store.effect(recover)?.result?.status)

            val cancel = enqueue(store, vibration)
            assertEquals("cancelled", broker.cancel(cancel)?.status)
            assertEquals("cancelled", broker.execute(cancel) { error("Canceled work ran") }.status)
        }
        val context = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
        }
        val read = CapabilityRequest("read", "calendar.read", JSONObject().put("calendarId", 1).put("from", 1000).put("to", 2000))
        val name = "device-android-denied-${UUID.randomUUID()}.db"
        try {
            ToolStore(context, name).use { store ->
                save(store, read)
                val key = enqueue(store, read)
                val result = CapabilityBroker(context, store).executeNative(key, { true }) { _, _ -> error("Android denied launch") }
                assertEquals("denied", result.status)
                assertEquals(result.json().toString(), store.effect(key)?.result?.json()?.toString())
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun pickerCancellationAndLibraryReplacementCannotApplyToTheSameRecord(): Unit = runBlocking {
        withStore { store ->
            save(store, pick)
            val broker = CapabilityBroker(app, store)
            val key = enqueue(store, pick)
            broker.execute(key) { waiting() }
            val old = CapabilitySelectionState(store.artifactIdentity)
            val launch = checkNotNull(old.begin(key, pick.capability))
            assertEquals(launch, old.consume(launch.token))
            assertFalse(store.finishUserEffect(key, CapabilityResult("cancelled", "Picker closed")))
            assertFalse(store.finishUserEffect(key, CapabilityResult("succeeded", "Late result")))
            assertEquals("cancelled", store.effect(key)?.result?.status)

            val waitingKey = enqueue(store, pick)
            broker.execute(waitingKey) { waiting() }
            val waitingLaunch = checkNotNull(old.begin(waitingKey, pick.capability))
            selectOtherLibraryForTest(app, store.library)
            ToolStore(app, store.logicalDatabase).use { replacement ->
                save(replacement, pick, stateVersion = 2)
                val sameKey = enqueue(replacement, pick)
                assertEquals(waitingKey, sameKey)
                CapabilityBroker(app, replacement).execute(sameKey) { waiting() }
                val current = CapabilitySelectionState(replacement.artifactIdentity)
                val currentLaunch = checkNotNull(current.begin(sameKey, pick.capability))
                assertFalse(current.owns(waitingLaunch))
                assertTrue(current.owns(currentLaunch))
                assertNull(current.consume(waitingLaunch.token))
                assertTrue(runCatching { store.finishUserEffect(waitingKey, CapabilityResult("succeeded", "Old library")) }.isFailure)
                assertEquals("waiting", replacement.effect(sameKey)?.phase)
            }
        }
    }

    @Test fun widgetActionWithDeviceEffectRequiresForegroundAndCommitsNothing(): Unit = runBlocking {
        withStore { store ->
            val tool = actionTool(store)
            val offline = object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession,
                    emit: suspend (CloudEvent) -> Unit) = error("Widget evaluation must stay offline")
            }
            val engine = ToolEngine(store, ToolGenerationService(offline, SandboxClient(app)))
            val result = runCatching {
                engine.interact(tool.id, tool.revision, tool.stateVersion, "tap", null, allowDeviceEffects = false)
            }.exceptionOrNull() as? CloudFailure
            assertEquals("widget_foreground_required", result?.code)
            assertEquals(tool, store.tool(tool.id))
            assertTrue(store.effects(tool.id).isEmpty())
            assertTrue(store.deliveries(tool.id).isEmpty())
        }
    }

    @Test fun foregroundControlDispatchesWithoutAnotherAlphyAction(): Unit {
        val fixture = ShellFixture("device-action-no-grant")
        val toolName = "${fixture.store.databaseName}-tools.db"
        val robot = ShellRobot("device-action-no-grant")
        try {
            ToolStore(app, toolName).use { store ->
                val tool = actionTool(store)
                fixture.install(saveKey = false)
                fixture.launch().use { scenario ->
                    robot.waitUntil { fixture.model?.let { !it.loading && !it.tools.busy } == true }
                    scenario.onActivity { checkNotNull(fixture.model).openSurface(tool.id) }
                    robot.waitUntil { fixture.model?.tools?.busy == false }
                    robot.click("Device action fixture")
                    robot.waitUntil { store.effects(tool.id).singleOrNull()?.phase == "terminal" }
                    assertTrue(store.effects(tool.id).single().result.status in setOf("succeeded","unavailable","unknownOutcome"))
                    assertEquals(1, JSONObject(store.tool(tool.id)!!.state).getInt("count"))
                    assertNull(robot.find("Подтвердить системное действие"))
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally { fixture.remove() }
    }

    private fun actionTool(store: ToolStore): ToolRecord {
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Device fixture","description":"",
            "capabilities":["vibration.play"],"stateSchema":{"count":"number"},"initialState":{"count":0},
            "ui":{"type":"button","text":"Device action fixture","action":"tap"},
            "source":"if(input.event.type==='action'){input.state.count++;return {state:input.state,effects:[{id:'tap',capability:'vibration.play',args:{milliseconds:10}}]};}return {state:input.state};"}""")
        return ToolRecord("owner", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
            .also { store.saveTool(it, true) }
    }

    private fun save(store: ToolStore, request: CapabilityRequest, stateVersion: Int = 1): ToolRecord {
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Device fixture","description":"",
            "capabilities":["${request.capability}"],"stateSchema":{"count":"number"},"initialState":{"count":0},
            "ui":{"type":"text","text":"Fixture"},"source":"return {state:input.state};"}""")
        return ToolRecord("owner", 1, stateVersion, spec.json, spec.initialState, spec.ui.toString(), null, true)
            .also { store.saveTool(it, true) }
    }

    private fun enqueue(store: ToolStore, request: CapabilityRequest): String {
        val tool = checkNotNull(store.tool("owner"))
        val next = tool.copy(stateVersion = tool.stateVersion + 1)
        store.commitTool(next, tool, false, effects = listOf(request))
        return store.effects(tool.id).last().key
    }

    private fun waiting() = CapabilityResult("awaitingUser", "Picker", JSONObject().put("launched", true))

    private suspend fun withStore(work: suspend (ToolStore) -> Unit) {
        val name = "device-authorization-${UUID.randomUUID()}.db"
        try { ToolStore(app, name).use { work(it) } }
        finally {
            app.databaseList().filter { it.startsWith(name) }.forEach(app::deleteDatabase)
            ToolLibraryStorage.selectionFile(app, name).delete()
        }
    }
}
