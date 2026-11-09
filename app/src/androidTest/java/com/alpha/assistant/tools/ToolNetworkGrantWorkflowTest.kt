package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.network.NetworkGrantPersistence
import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Covers durable writes, failed revocation, revision and archive fencing, and active library identity. */
@RunWith(AndroidJUnit4::class)
class ToolNetworkGrantWorkflowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theProductionAccessPageWritesTheSavedGrantAndShowsItAfterReopen() {
        val fixture = ShellFixture("tool-network-grant-access")
        val name = "${fixture.store.databaseName}-tools.db"
        val robot = ShellRobot("tool-network-grant-access")
        try {
            ToolStore(app,name).use { store ->
                val tool = save(store)
                fixture.install(saveKey = false)
                fun access(scenario: androidx.test.core.app.ActivityScenario<*>) {
                    robot.openDrawer(scenario)
                    robot.click(robot.text(R.string.settings))
                    robot.awaitDrawerClosed(scenario)
                    robot.click(robot.text(R.string.access))
                }
                fun clickGrant(resource: Int) {
                    val label = robot.text(resource, "Карта района")
                    if (robot.find(label) == null) robot.reveal(label)
                    robot.click(label)
                }
                fixture.launch().use { scenario ->
                    access(scenario)
                    clickGrant(R.string.mini_app_network_allow)
                    robot.waitFor(robot.text(R.string.mini_app_network_revoke, "Карта района"))
                    assertTrue(tool.id in store.networkGrantedOwners())
                }
                fixture.launch().use { scenario ->
                    access(scenario)
                    clickGrant(R.string.mini_app_network_revoke)
                    robot.waitFor(robot.text(R.string.mini_app_network_allow, "Карта района"))
                    assertFalse(tool.id in store.networkGrantedOwners())
                }
                assertEquals(0, fixture.provider.calls)
                ToolNetworkGrants.retireLibrary(app,store.library)
            }
        } finally { fixture.remove() }
    }

    @Test fun durableConsentDoesNotCopyToAnotherApp(): Unit = runBlocking {
        withStore { store ->
            val appRecord = save(store)
            val copy = save(store, appRecord.copy(id = UUID.randomUUID().toString()))
            val draftOnly = save(store, appRecord.copy(id = UUID.randomUUID().toString(), saved = false))
            val authority = ToolNetworkGrants.authority(store)
            val host = checkNotNull(authority.refreshAccess())
            assertEquals(setOf(appRecord.id,copy.id), host.apps.value.map {it.id}.toSet())
            assertNull(authority.activate(appRecord,Any(),"native-session"))
            assertTrue(authority.setGranted(draftOnly.id,true).isFailure)
            assertTrue(authority.setGranted(appRecord.id,true).isSuccess)
            assertTrue(host.apps.value.single {it.id==appRecord.id}.granted)
            assertFalse(host.apps.value.single {it.id==copy.id}.granted)
            assertNotNull(authority.activate(appRecord,Any(),"native-session"))
            assertNull(authority.activate(copy,Any(),"other-session"))
            assertEquals(setOf(appRecord.id), store.networkGrantedOwners())
            assertFalse(CapabilityCatalog.ids.contains("host.network"))
            assertFalse(store.tool(appRecord.id)!!.spec.contains("host.network"))
            ToolStore(app,store.logicalDatabase).use { reopened ->
                assertSame(authority, ToolNetworkGrants.authority(reopened))
                assertEquals(setOf(appRecord.id), reopened.networkGrantedOwners())
                val restarted = ToolNetworkAuthority(app,reopened.library)
                try {
                    assertTrue(checkNotNull(restarted.refreshAccess()).apps.value.single {it.id==appRecord.id}.granted)
                    assertNotNull(restarted.activate(appRecord,Any(),"new-process"))
                    assertNull(restarted.activate(copy,Any(),"copied-app"))
                } finally { restarted.retire() }
            }
        }
    }

    @Test fun failedRevocationStopsTheCurrentGrantBeforeTheWriteAndRemainsRetryable(): Unit = runBlocking {
        withStore { store ->
            val tool = save(store)
            val persistence = ToolNetworkGrantPersistence(app,store.library)
            val writeEntered = CompletableDeferred<Unit>()
            val allowFailure = CountDownLatch(1)
            val failNext = AtomicBoolean(true)
            val injected = object : NetworkGrantPersistence by persistence {
                override fun setEnabled(owner: NetworkOwner, enabled: Boolean): Result<Unit> {
                    if (!enabled && failNext.getAndSet(false)) {
                        writeEntered.complete(Unit)
                        check(allowFailure.await(5,TimeUnit.SECONDS))
                        return Result.failure(IOException("Fixture durable write failed"))
                    }
                    return persistence.setEnabled(owner,enabled)
                }
            }
            val authority = ToolNetworkAuthority(app,store.library,injected)
            try {
                val access = checkNotNull(authority.refreshAccess())
                assertTrue(authority.setGranted(tool.id,true).isSuccess)
                val controller = Any()
                val grant = checkNotNull(authority.activate(tool,controller,"native-session"))
                val revoked = async { authority.setGranted(tool.id,false) }
                withTimeout(5_000) { writeEntered.await() }
                assertFalse(authority.isCurrent(grant))
                assertTrue(access.apps.value.single().granted)
                assertEquals(setOf(tool.id),store.networkGrantedOwners())
                allowFailure.countDown()
                assertTrue(withTimeout(5_000) { revoked.await() }.isFailure)
                authority.closeSessions(controller)
                assertSame(access,authority.refreshAccess())
                assertTrue(access.apps.value.single().granted)
                assertNull(authority.activate(tool,Any(),"reopened-host"))
                assertTrue(authority.setGranted(tool.id,false).isSuccess)
                assertFalse(access.apps.value.single().granted)
                assertTrue(store.networkGrantedOwners().isEmpty())
            } finally {
                allowFailure.countDown()
                authority.retire()
            }
        }
    }

    @Test fun aFailedStartupReadNeverPublishesOrActivatesAuthorization(): Unit = runBlocking {
        withStore { store ->
            val tool = save(store)
            val persistence = ToolNetworkGrantPersistence(app,store.library)
            val owner = NetworkOwner(store.artifactIdentity,tool.id)
            assertTrue(persistence.setEnabled(owner,true).isSuccess)
            val unreadable = object : NetworkGrantPersistence by persistence {
                override fun loadEnabled() = Result.failure<Set<NetworkOwner>>(IOException("Fixture read failed"))
            }
            val authority = ToolNetworkAuthority(app,store.library,unreadable)
            try {
                assertNull(authority.refreshAccess())
                assertNull(authority.activate(tool,Any(),"native-session"))
                assertTrue(authority.setGranted(tool.id,false).isFailure)
                assertEquals(setOf(tool.id),store.networkGrantedOwners())
            } finally { authority.retire() }
        }
    }

    @Test fun revocationFencesTheAppWhileAnotherGrantWriteIsStillPending(): Unit = runBlocking {
        withStore { store ->
            val first = save(store)
            val second = save(store)
            val persistence = ToolNetworkGrantPersistence(app,store.library)
            val otherWriteStarted = CompletableDeferred<Unit>()
            val releaseOtherWrite = CountDownLatch(1)
            val injected = object : NetworkGrantPersistence by persistence {
                override fun setEnabled(owner: NetworkOwner, enabled: Boolean): Result<Unit> {
                    if (owner.miniAppId == second.id && enabled) {
                        otherWriteStarted.complete(Unit)
                        check(releaseOtherWrite.await(5,TimeUnit.SECONDS))
                    }
                    return persistence.setEnabled(owner,enabled)
                }
            }
            val authority = ToolNetworkAuthority(app,store.library,injected)
            try {
                checkNotNull(authority.refreshAccess())
                assertTrue(authority.setGranted(first.id,true).isSuccess)
                val grant = checkNotNull(authority.activate(first,Any(),"before-revoke"))
                val otherWrite = async { authority.setGranted(second.id,true) }
                withTimeout(5_000) { otherWriteStarted.await() }
                val revoke = async(start=CoroutineStart.UNDISPATCHED) { authority.setGranted(first.id,false) }
                assertFalse(authority.isCurrent(grant))
                assertNull(authority.activate(first,Any(),"pending-revoke"))
                assertEquals(setOf(first.id),store.networkGrantedOwners())
                releaseOtherWrite.countDown()
                assertTrue(withTimeout(5_000) { otherWrite.await() }.isSuccess)
                assertTrue(withTimeout(5_000) { revoke.await() }.isSuccess)
                assertEquals(setOf(second.id),store.networkGrantedOwners())
            } finally {
                releaseOtherWrite.countDown()
                authority.retire()
            }
        }
    }

    @Test fun revisionArchiveAndUnarchiveKeepConsentButRetireOldSessionsThenDeletionRemovesIt(): Unit = runBlocking {
        withStore { store ->
            val tool = save(store)
            val authority = ToolNetworkGrants.authority(store)
            checkNotNull(authority.refreshAccess())
            assertTrue(authority.setGranted(tool.id,true).isSuccess)
            val owner = Any()
            val first = checkNotNull(authority.activate(tool,owner,"before-edit"))
            val updated = tool.copy(revision=tool.revision+1,stateVersion=tool.stateVersion+1)
            store.commitTool(updated,tool,true)
            assertFalse(authority.isCurrent(first))
            assertNull(authority.activate(tool,owner,"old-revision"))
            val second = checkNotNull(authority.activate(updated,owner,"after-edit"))
            AutomationStore(app,store.automationDatabase).use { automations ->
                AutomationRuntime.archiveTool(store,automations,updated)
                val archived = checkNotNull(store.tool(tool.id))
                assertTrue(archived.archived)
                assertFalse(authority.isCurrent(second))
                assertNull(authority.activate(archived,owner,"archived"))
                assertEquals(setOf(tool.id),store.networkGrantedOwners())
                store.setArchived(archived,false)
                val restored = checkNotNull(store.tool(tool.id))
                assertNull(authority.activate(updated,owner,"stale-prearchive"))
                val third = checkNotNull(authority.activate(restored,owner,"restored"))
                val snapshot = AutomationRuntime.deletionSnapshot(store,automations,restored)
                AutomationRuntime.deleteTool(store,automations,snapshot)
                assertFalse(authority.isCurrent(third))
                assertTrue(store.networkGrantedOwners().isEmpty())
                assertNull(authority.activate(restored,owner,"deleted"))
            }
        }
    }

    @Test fun replacingALibraryRetiresItsAuthorityEvenWhenTheAppIdIsReused(): Unit = runBlocking {
        withStore { store ->
            val tool = save(store)
            val authority = ToolNetworkGrants.authority(store)
            checkNotNull(authority.refreshAccess())
            assertTrue(authority.setGranted(tool.id,true).isSuccess)
            val grant = checkNotNull(authority.activate(tool,Any(),"before-replacement"))
            val old = store.library
            selectOtherLibraryForTest(app, old)
            assertFalse(authority.isCurrent(grant))
            assertNull(authority.activate(tool,Any(),"stale-library"))
            ToolStore(app,store.logicalDatabase).use { replacement ->
                save(replacement,tool)
                val next = ToolNetworkGrants.authority(replacement)
                assertNotSame(authority,next)
                assertFalse(checkNotNull(next.refreshAccess()).apps.value.single().granted)
                assertNull(next.activate(tool,Any(),"replacement"))
                assertTrue(replacement.networkGrantedOwners().isEmpty())
                ToolNetworkGrants.retireLibrary(app,replacement.library)
            }
        }
    }

    private suspend fun withStore(work: suspend (ToolStore) -> Unit) = withContext(Dispatchers.IO) {
        val name = "tool-network-grant-${UUID.randomUUID()}.db"
        try {
            ToolStore(app,name).use { store ->
                try { work(store) } finally { ToolNetworkGrants.retireLibrary(app,store.library) }
            }
        } finally {
            app.databaseList().filter {it.startsWith(name)}.forEach(app::deleteDatabase)
            ToolLibraryStorage.selectionFile(app,name).delete()
        }
    }

    private fun save(store: ToolStore, copy: ToolRecord? = null): ToolRecord {
        val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Карта района","description":"","capabilities":[],"stateSchema":{"value":"number"},"initialState":{"value":0},"ui":{"type":"text","text":"Карта"},"source":"return {state:input.state};"}""")
        return (copy ?: ToolRecord(UUID.randomUUID().toString(),1,1,spec.json,spec.initialState,JSONObject(spec.ui.toString()).toString(),null,true))
            .also {store.commitTool(it,null,true)}
    }
}
